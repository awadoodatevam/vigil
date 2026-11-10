package vigil.knowledge;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vigil.knowledge.api.EmbeddingState;
import vigil.knowledge.api.KnowledgeSettingsView;
import vigil.knowledge.api.PastIncidentView;
import vigil.knowledge.api.RunbookView;
import vigil.knowledge.api.SearchOutcome;
import vigil.knowledge.api.SimilarityResultView;
import vigil.shared.Ids;
import vigil.shared.TenantId;

/** The tables of {@code knowledge}; every statement filters by {@code tenant_id}. */
@Component
class KnowledgeRecords {

  static final JsonMapper JSON = JsonMapper.builder().build();
  static final int SEARCH_TEXT_MAX = 2000;

  private static final String RUNBOOK_COLUMNS =
      "id, runbook_key, version, title, applicable_alert_types::text AS alert_types,"
          + " applicable_component_kinds::text AS component_kinds, text, active";
  private static final String PAST_COLUMNS =
      "id, past_incident_key, title, record_label, opened_at, acknowledged_at, recovered_at,"
          + " alert_count, alert_types::text AS alert_types,"
          + " affected_service_keys::text AS service_keys, hypothesis_component_key, summary,"
          + " fix_description, fix_action_key, fix_success_count, active";
  private static final String SETTINGS_COLUMNS =
      "min_similarity, runbook_limit, embedding_provider_key, embedding_state,"
          + " reembedding_provider_key";
  private static final String RESULT_COLUMNS =
      "id, incident_id, run_id, search_text, provider_key, outcome, best_past_incident_key,"
          + " score, success_count, fix_action_key, min_similarity, searched_at";

  /** One past incident's embedding as inserted (DM-E-65). */
  record Embedding(String subjectKind, String subjectKey, String searchText, float[] vector) {}

  /** A past incident's rounded score against a query vector. */
  record Score(String pastIncidentKey, BigDecimal score) {}

  private final JdbcClient jdbc;

  KnowledgeRecords(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Check 1 of the import: any runbook, past incident or knowledge settings. */
  boolean anyRecords(TenantId tenantId) {
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM knowledge.runbook WHERE tenant_id = :t)"
                + " OR EXISTS (SELECT 1 FROM knowledge.past_incident WHERE tenant_id = :t)"
                + " OR EXISTS (SELECT 1 FROM knowledge.knowledge_settings WHERE tenant_id = :t)")
        .param("t", tenantId.value())
        .query(Boolean.class)
        .single();
  }

  void insertRunbook(TenantId tenantId, RunbookView runbook, Instant now) {
    jdbc.sql(
            "INSERT INTO knowledge.runbook (id, tenant_id, created_at, updated_at, runbook_key,"
                + " version, title, applicable_alert_types, applicable_component_kinds, text,"
                + " active) VALUES (:id, :t, :now, :now, :key, :version, :title,"
                + " CAST(:types AS jsonb), CAST(:kinds AS jsonb), :text, :active)")
        .param("id", Ids.newId())
        .param("t", tenantId.value())
        .param("now", Timestamp.from(now))
        .param("key", runbook.runbookKey())
        .param("version", runbook.version())
        .param("title", runbook.title())
        .param("types", json(runbook.applicableAlertTypes()))
        .param("kinds", json(runbook.applicableComponentKinds()))
        .param("text", runbook.text())
        .param("active", runbook.active())
        .update();
  }

  void insertPastIncident(TenantId tenantId, PastIncidentView past, Instant now) {
    jdbc.sql(
            "INSERT INTO knowledge.past_incident (id, tenant_id, created_at, updated_at,"
                + " past_incident_key, title, record_label, opened_at, acknowledged_at,"
                + " recovered_at, alert_count, alert_types, affected_service_keys,"
                + " hypothesis_component_key, summary, fix_description, fix_action_key,"
                + " fix_success_count, active) VALUES (:id, :t, :now, :now, :key, :title,"
                + " :label, :opened, :acknowledged, :recovered, :count, CAST(:types AS jsonb),"
                + " CAST(:services AS jsonb), :component, :summary, :fix, :action, :successes,"
                + " :active)")
        .param("id", Ids.newId())
        .param("t", tenantId.value())
        .param("now", Timestamp.from(now))
        .param("key", past.pastIncidentKey())
        .param("title", past.title())
        .param("label", past.recordLabel())
        .param("opened", Timestamp.from(past.openedAt()))
        .param("acknowledged", timestamp(past.acknowledgedAt()))
        .param("recovered", timestamp(past.recoveredAt()))
        .param("count", past.alertCount())
        .param("types", json(past.alertTypes()))
        .param("services", json(past.affectedServiceKeys()))
        .param("component", past.hypothesisComponentKey())
        .param("summary", past.summary())
        .param("fix", past.fixDescription())
        .param("action", past.fixActionKey())
        .param("successes", past.fixSuccessCount())
        .param("active", past.active())
        .update();
  }

  /** Inserts an embedding; with {@code ignoreConflict} a past incident's existing one stays. */
  void insertEmbedding(
      TenantId tenantId,
      Embedding embedding,
      String providerKey,
      String modelName,
      Instant now,
      boolean ignoreConflict) {
    jdbc.sql(
            "INSERT INTO knowledge.incident_embedding (id, tenant_id, created_at, subject_kind,"
                + " subject_key, provider_key, model_name, search_text, vector) VALUES (:id, :t,"
                + " :now, :kind, :key, :provider, :model, :text, CAST(:vector AS public.vector))"
                + (ignoreConflict
                    ? " ON CONFLICT (tenant_id, subject_key, provider_key)"
                        + " WHERE subject_kind = 'past_incident' DO NOTHING"
                    : ""))
        .param("id", Ids.newId())
        .param("t", tenantId.value())
        .param("now", Timestamp.from(now))
        .param("kind", embedding.subjectKind())
        .param("key", embedding.subjectKey())
        .param("provider", providerKey)
        .param("model", modelName)
        .param("text", SearchTexts.cut(embedding.searchText(), SEARCH_TEXT_MAX))
        .param("vector", Vectors.text(embedding.vector()))
        .update();
  }

  void insertSettings(TenantId tenantId, KnowledgeSettingsView settings, Instant now) {
    jdbc.sql(
            "INSERT INTO knowledge.knowledge_settings (id, tenant_id, created_at, updated_at,"
                + " min_similarity, runbook_limit, embedding_provider_key, embedding_state,"
                + " reembedding_provider_key) VALUES (:id, :t, :now, :now, :min, :limit,"
                + " :provider, :state, :reembedding)")
        .param("id", Ids.newId())
        .param("t", tenantId.value())
        .param("now", Timestamp.from(now))
        .param("min", settings.minSimilarity())
        .param("limit", settings.runbookLimit())
        .param("provider", settings.embeddingProviderKey())
        .param("state", settings.embeddingState().key())
        .param("reembedding", settings.reembeddingProviderKey())
        .update();
  }

  Optional<KnowledgeSettingsView> settings(TenantId tenantId) {
    return jdbc.sql(
            "SELECT "
                + SETTINGS_COLUMNS
                + " FROM knowledge.knowledge_settings WHERE tenant_id = :t")
        .param("t", tenantId.value())
        .query(KnowledgeRecords::settings)
        .optional();
  }

  /** The settings, locked for the rest of the transaction. */
  Optional<KnowledgeSettingsView> lockSettings(TenantId tenantId) {
    return jdbc.sql(
            "SELECT "
                + SETTINGS_COLUMNS
                + " FROM knowledge.knowledge_settings WHERE tenant_id = :t FOR UPDATE")
        .param("t", tenantId.value())
        .query(KnowledgeRecords::settings)
        .optional();
  }

  /**
   * DM-SM15-T1: the settings become {@code reembedding} toward {@code providerKey}, when they still
   * are as {@code expected}; false when they changed meanwhile.
   */
  boolean startReembedding(
      TenantId tenantId, KnowledgeSettingsView expected, String providerKey, Instant now) {
    boolean ready = expected.embeddingState() == EmbeddingState.READY;
    return jdbc.sql(
                "UPDATE knowledge.knowledge_settings SET embedding_state = 'reembedding',"
                    + " reembedding_provider_key = :key, updated_at = :now WHERE tenant_id = :t"
                    + " AND embedding_state = :state"
                    + " AND reembedding_provider_key IS NOT DISTINCT FROM CAST(:expected AS varchar)"
                    + (ready ? " AND embedding_provider_key = :current" : ""))
            .param("t", tenantId.value())
            .param("key", providerKey)
            .param("now", Timestamp.from(now))
            .param("state", expected.embeddingState().key())
            .param("expected", expected.reembeddingProviderKey())
            .param("current", expected.embeddingProviderKey())
            .update()
        == 1;
  }

  /**
   * DM-SM15-T2: the settings become {@code ready} with {@code providerKey}, when they are still
   * {@code reembedding} toward it; false when they changed meanwhile.
   */
  boolean finishReembedding(TenantId tenantId, String providerKey, Instant now) {
    return jdbc.sql(
                "UPDATE knowledge.knowledge_settings SET embedding_provider_key = :key,"
                    + " embedding_state = 'ready', reembedding_provider_key = NULL,"
                    + " updated_at = :now WHERE tenant_id = :t"
                    + " AND embedding_state = 'reembedding'"
                    + " AND reembedding_provider_key IS NOT DISTINCT FROM CAST(:key AS varchar)")
            .param("t", tenantId.value())
            .param("key", providerKey)
            .param("now", Timestamp.from(now))
            .update()
        == 1;
  }

  List<RunbookView> runbooks(TenantId tenantId) {
    return jdbc.sql(
            "SELECT "
                + RUNBOOK_COLUMNS
                + " FROM knowledge.runbook WHERE tenant_id = :t"
                + " ORDER BY runbook_key COLLATE \"C\", version")
        .param("t", tenantId.value())
        .query(KnowledgeRecords::runbook)
        .list();
  }

  List<PastIncidentView> pastIncidents(TenantId tenantId) {
    return jdbc.sql(
            "SELECT "
                + PAST_COLUMNS
                + " FROM knowledge.past_incident WHERE tenant_id = :t"
                + " ORDER BY past_incident_key COLLATE \"C\"")
        .param("t", tenantId.value())
        .query(KnowledgeRecords::pastIncident)
        .list();
  }

  Optional<PastIncidentView> pastIncident(TenantId tenantId, String pastIncidentKey) {
    return jdbc.sql(
            "SELECT "
                + PAST_COLUMNS
                + " FROM knowledge.past_incident WHERE tenant_id = :t AND past_incident_key = :k")
        .param("t", tenantId.value())
        .param("k", pastIncidentKey)
        .query(KnowledgeRecords::pastIncident)
        .optional();
  }

  /** The active past incidents without an embedding with {@code providerKey}, by key. */
  List<PastIncidentView> withoutEmbedding(TenantId tenantId, String providerKey) {
    return jdbc.sql(
            "SELECT "
                + PAST_COLUMNS
                + " FROM knowledge.past_incident p WHERE p.tenant_id = :t AND p.active"
                + " AND NOT EXISTS (SELECT 1 FROM knowledge.incident_embedding e"
                + " WHERE e.tenant_id = :t AND e.subject_kind = 'past_incident'"
                + " AND e.subject_key = p.past_incident_key AND e.provider_key = :p)"
                + " ORDER BY p.past_incident_key COLLATE \"C\"")
        .param("t", tenantId.value())
        .param("p", providerKey)
        .query(KnowledgeRecords::pastIncident)
        .list();
  }

  /**
   * AR-P-07: the exact scan of the active past incidents with an embedding of {@code providerKey}
   * and {@code dimensions} numbers, by key; scores rounded half-up to 2 places in Java.
   */
  List<Score> scores(TenantId tenantId, String providerKey, float[] query) {
    return jdbc.sql(
            "SELECT p.past_incident_key,"
                + " 1 - (e.vector OPERATOR(public.<=>) CAST(:q AS public.vector)) AS score"
                + " FROM knowledge.past_incident p JOIN knowledge.incident_embedding e"
                + " ON e.tenant_id = p.tenant_id AND e.subject_kind = 'past_incident'"
                + " AND e.subject_key = p.past_incident_key AND e.provider_key = :p"
                + " WHERE p.tenant_id = :t AND p.active AND public.vector_dims(e.vector) = :n"
                + " ORDER BY p.past_incident_key COLLATE \"C\"")
        .param("t", tenantId.value())
        .param("p", providerKey)
        .param("q", Vectors.text(query))
        .param("n", query.length)
        .query(
            (rs, row) ->
                new Score(
                    rs.getString("past_incident_key"),
                    rs.getBigDecimal("score").setScale(2, RoundingMode.HALF_UP)))
        .list();
  }

  void insertResult(TenantId tenantId, SimilarityResultView result, Instant now) {
    jdbc.sql(
            "INSERT INTO knowledge.similarity_search_result (id, tenant_id, created_at,"
                + " incident_id, run_id, search_text, provider_key, outcome,"
                + " best_past_incident_key, score, success_count, fix_action_key, min_similarity,"
                + " searched_at) VALUES (:id, :t, :now, :incident, :run, :text, :provider,"
                + " :outcome, :best, :score, :successes, :action, :min, :searched)")
        .param("id", result.id())
        .param("t", tenantId.value())
        .param("now", Timestamp.from(now))
        .param("incident", result.incidentId())
        .param("run", result.runId())
        .param("text", result.searchText())
        .param("provider", result.providerKey())
        .param("outcome", result.outcome().key())
        .param("best", result.bestPastIncidentKey())
        .param("score", result.score())
        .param("successes", result.successCount())
        .param("action", result.fixActionKey())
        .param("min", result.minSimilarity())
        .param("searched", Timestamp.from(result.searchedAt()))
        .update();
  }

  Optional<SimilarityResultView> latestResult(TenantId tenantId, UUID incidentId) {
    return jdbc.sql(
            "SELECT "
                + RESULT_COLUMNS
                + " FROM knowledge.similarity_search_result WHERE tenant_id = :t"
                + " AND incident_id = :i ORDER BY searched_at DESC, id DESC LIMIT 1")
        .param("t", tenantId.value())
        .param("i", incidentId)
        .query(KnowledgeRecords::result)
        .optional();
  }

  static String json(List<String> values) {
    return JSON.writeValueAsString(values);
  }

  static List<String> list(String json) {
    List<String> values = new ArrayList<>();
    for (JsonNode node : JSON.readTree(json)) {
      values.add(node.stringValue());
    }
    return Collections.unmodifiableList(values);
  }

  private static Timestamp timestamp(Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp timestamp = rs.getTimestamp(column);
    return timestamp == null ? null : timestamp.toInstant();
  }

  private static RunbookView runbook(ResultSet rs, int row) throws SQLException {
    return new RunbookView(
        rs.getObject("id", UUID.class),
        rs.getString("runbook_key"),
        rs.getLong("version"),
        rs.getString("title"),
        list(rs.getString("alert_types")),
        list(rs.getString("component_kinds")),
        rs.getString("text"),
        rs.getBoolean("active"));
  }

  private static PastIncidentView pastIncident(ResultSet rs, int row) throws SQLException {
    return new PastIncidentView(
        rs.getObject("id", UUID.class),
        rs.getString("past_incident_key"),
        rs.getString("title"),
        rs.getString("record_label"),
        instant(rs, "opened_at"),
        instant(rs, "acknowledged_at"),
        instant(rs, "recovered_at"),
        rs.getLong("alert_count"),
        list(rs.getString("alert_types")),
        list(rs.getString("service_keys")),
        rs.getString("hypothesis_component_key"),
        rs.getString("summary"),
        rs.getString("fix_description"),
        rs.getString("fix_action_key"),
        rs.getLong("fix_success_count"),
        rs.getBoolean("active"));
  }

  private static KnowledgeSettingsView settings(ResultSet rs, int row) throws SQLException {
    return new KnowledgeSettingsView(
        rs.getBigDecimal("min_similarity"),
        rs.getLong("runbook_limit"),
        rs.getString("embedding_provider_key"),
        EmbeddingState.fromKey(rs.getString("embedding_state")).orElseThrow(),
        rs.getString("reembedding_provider_key"));
  }

  private static SimilarityResultView result(ResultSet rs, int row) throws SQLException {
    long successes = rs.getLong("success_count");
    Long successCount = rs.wasNull() ? null : successes;
    return new SimilarityResultView(
        rs.getObject("id", UUID.class),
        rs.getObject("incident_id", UUID.class),
        rs.getObject("run_id", UUID.class),
        rs.getString("search_text"),
        rs.getString("provider_key"),
        SearchOutcome.fromKey(rs.getString("outcome")).orElseThrow(),
        rs.getString("best_past_incident_key"),
        rs.getBigDecimal("score"),
        successCount,
        rs.getString("fix_action_key"),
        rs.getBigDecimal("min_similarity"),
        instant(rs, "searched_at"));
  }
}
