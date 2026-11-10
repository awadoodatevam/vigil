package vigil.investigation;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import vigil.investigation.api.IncidentSummaryView;
import vigil.investigation.api.InvestigationResultView;
import vigil.investigation.api.Origin;
import vigil.investigation.api.RunbookRef;
import vigil.investigation.api.SummarySource;
import vigil.investigation.api.Validation;
import vigil.investigation.api.Verdict;
import vigil.shared.Actor;
import vigil.shared.TenantId;

/** The tables of {@code investigation}; every statement filters by {@code tenant_id}. */
@Component
class InvestigationRecords {

  static final JsonMapper JSON = JsonMapper.builder().build();

  private static final String RESULT_COLUMNS =
      "id, created_at, incident_id, origin, run_id, requested_by_kind, requested_by_user_id,"
          + " requested_by_module, request_group_id, provider_key, model_name, likely_cause,"
          + " verdict, evidence_entry_ids::text AS evidence_entry_ids, uncertainty,"
          + " runbooks_supplied::text AS runbooks_supplied, runbook_refs::text AS runbook_refs,"
          + " raw_output, raw_output_truncated, validation,"
          + " rejection_reasons::text AS rejection_reasons";
  private static final String SUMMARY_COLUMNS =
      "id, created_at, incident_id, run_id, text, source, provider_key";

  private final JdbcClient jdbc;

  InvestigationRecords(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  void insertResult(TenantId tenantId, InvestigationResultView result) {
    Actor actor = result.requestedBy();
    jdbc.sql(
            "INSERT INTO investigation.ai_investigation_result (id, tenant_id, created_at,"
                + " incident_id, origin, run_id, requested_by_kind, requested_by_user_id,"
                + " requested_by_module, request_group_id, provider_key, model_name,"
                + " likely_cause, verdict, evidence_entry_ids, uncertainty, runbooks_supplied,"
                + " runbook_refs, raw_output, raw_output_truncated, validation,"
                + " rejection_reasons) VALUES (:id, :t, :now, :incident, :origin, :run, :kind,"
                + " :user, :module, :group, :provider, :model, :cause, :verdict,"
                + " CAST(:ids AS jsonb), :uncertainty, CAST(:supplied AS jsonb),"
                + " CAST(:refs AS jsonb), :raw, :truncated, :validation, CAST(:reasons AS jsonb))")
        .param("id", result.id())
        .param("t", tenantId.value())
        .param("now", Timestamp.from(result.createdAt()))
        .param("incident", result.incidentId())
        .param("origin", result.origin().key())
        .param("run", result.runId())
        .param("kind", actor.kind() == Actor.Kind.USER ? "user" : "module")
        .param("user", actor.userId())
        .param("module", actor.module())
        .param("group", result.requestGroupId())
        .param("provider", result.providerKey())
        .param("model", result.modelName())
        .param("cause", result.likelyCause())
        .param("verdict", result.verdict() == null ? null : result.verdict().key())
        .param("ids", JSON.writeValueAsString(result.evidenceEntryIds()))
        .param("uncertainty", result.uncertainty())
        .param("supplied", refsJson(result.runbooksSupplied()).toString())
        .param("refs", refsJson(result.runbookRefs()).toString())
        .param("raw", result.rawOutput())
        .param("truncated", result.rawOutputTruncated())
        .param("validation", result.validation().key())
        .param("reasons", JSON.writeValueAsString(result.rejectionReasons()))
        .update();
  }

  List<InvestigationResultView> results(TenantId tenantId, UUID incidentId) {
    return jdbc.sql(
            "SELECT "
                + RESULT_COLUMNS
                + " FROM investigation.ai_investigation_result WHERE tenant_id = :t"
                + " AND incident_id = :i ORDER BY created_at, id")
        .param("t", tenantId.value())
        .param("i", incidentId)
        .query(InvestigationRecords::result)
        .list();
  }

  Optional<InvestigationResultView> latestAccepted(TenantId tenantId, UUID incidentId) {
    return jdbc.sql(
            "SELECT "
                + RESULT_COLUMNS
                + " FROM investigation.ai_investigation_result WHERE tenant_id = :t"
                + " AND incident_id = :i AND validation = 'accepted'"
                + " ORDER BY created_at DESC, id DESC LIMIT 1")
        .param("t", tenantId.value())
        .param("i", incidentId)
        .query(InvestigationRecords::result)
        .optional();
  }

  void insertSummary(TenantId tenantId, IncidentSummaryView summary) {
    jdbc.sql(
            "INSERT INTO investigation.incident_summary (id, tenant_id, created_at, incident_id,"
                + " run_id, text, source, provider_key) VALUES (:id, :t, :now, :incident, :run,"
                + " :text, :source, :provider)")
        .param("id", summary.id())
        .param("t", tenantId.value())
        .param("now", Timestamp.from(summary.createdAt()))
        .param("incident", summary.incidentId())
        .param("run", summary.runId())
        .param("text", summary.text())
        .param("source", summary.source().key())
        .param("provider", summary.providerKey())
        .update();
  }

  Optional<IncidentSummaryView> latestSummary(TenantId tenantId, UUID incidentId) {
    return jdbc.sql(
            "SELECT "
                + SUMMARY_COLUMNS
                + " FROM investigation.incident_summary WHERE tenant_id = :t"
                + " AND incident_id = :i ORDER BY created_at DESC, id DESC LIMIT 1")
        .param("t", tenantId.value())
        .param("i", incidentId)
        .query(InvestigationRecords::summary)
        .optional();
  }

  /** {@code [{"runbook_key", "version"}, …]}. */
  static ArrayNode refsJson(List<RunbookRef> refs) {
    ArrayNode array = JSON.createArrayNode();
    for (RunbookRef ref : refs) {
      ObjectNode node = array.addObject();
      node.put("runbook_key", ref.runbookKey());
      node.put("version", ref.version());
    }
    return array;
  }

  private static List<RunbookRef> refs(String json) {
    List<RunbookRef> refs = new ArrayList<>();
    for (JsonNode node : JSON.readTree(json)) {
      refs.add(new RunbookRef(node.get("runbook_key").stringValue(), node.get("version").asLong()));
    }
    return List.copyOf(refs);
  }

  private static List<String> strings(String json) {
    List<String> values = new ArrayList<>();
    JSON.readTree(json).forEach(node -> values.add(node.stringValue()));
    return List.copyOf(values);
  }

  private static InvestigationResultView result(ResultSet rs, int row) throws SQLException {
    String kind = rs.getString("requested_by_kind");
    Actor actor =
        "user".equals(kind)
            ? Actor.user(rs.getObject("requested_by_user_id", UUID.class))
            : Actor.module(rs.getString("requested_by_module"));
    String verdict = rs.getString("verdict");
    return new InvestigationResultView(
        rs.getObject("id", UUID.class),
        rs.getObject("incident_id", UUID.class),
        Origin.fromKey(rs.getString("origin")).orElseThrow(),
        rs.getObject("run_id", UUID.class),
        actor,
        rs.getObject("request_group_id", UUID.class),
        rs.getString("provider_key"),
        rs.getString("model_name"),
        rs.getString("likely_cause"),
        verdict == null ? null : Verdict.fromKey(verdict).orElseThrow(),
        strings(rs.getString("evidence_entry_ids")).stream().map(UUID::fromString).toList(),
        rs.getString("uncertainty"),
        refs(rs.getString("runbooks_supplied")),
        refs(rs.getString("runbook_refs")),
        rs.getString("raw_output"),
        rs.getBoolean("raw_output_truncated"),
        Validation.fromKey(rs.getString("validation")).orElseThrow(),
        strings(rs.getString("rejection_reasons")),
        instant(rs, "created_at"));
  }

  private static IncidentSummaryView summary(ResultSet rs, int row) throws SQLException {
    return new IncidentSummaryView(
        rs.getObject("id", UUID.class),
        rs.getObject("incident_id", UUID.class),
        rs.getObject("run_id", UUID.class),
        rs.getString("text"),
        SummarySource.fromKey(rs.getString("source")).orElseThrow(),
        rs.getString("provider_key"),
        instant(rs, "created_at"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp timestamp = rs.getTimestamp(column);
    return timestamp == null ? null : timestamp.toInstant();
  }
}
