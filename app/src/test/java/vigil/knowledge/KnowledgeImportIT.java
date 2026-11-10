package vigil.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vigil.aigateway.AiFixture;
import vigil.knowledge.api.EmbeddingState;
import vigil.knowledge.api.KnowledgeImport;
import vigil.knowledge.api.KnowledgePart;
import vigil.knowledge.api.KnowledgePart.PastIncidentPart;
import vigil.knowledge.api.KnowledgePart.RunbookPart;
import vigil.knowledge.api.KnowledgePart.SettingsPart;
import vigil.knowledge.api.KnowledgeService;
import vigil.knowledge.api.KnowledgeSettingsView;
import vigil.knowledge.api.PastIncidentView;
import vigil.knowledge.api.RunbookView;
import vigil.setup.KnowledgeParts;
import vigil.shared.TenantId;
import vigil.shared.VigilError;
import vigil.testsupport.TestEmbedding;
import vigil.testsupport.TestSecrets;
import vigil.testsupport.VigilDatabase;
import vigil.testsupport.VigilTestApp;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class KnowledgeImportIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final KnowledgePart DEMO = KnowledgeParts.demoPart();
  private static final Instant T = Instant.parse("2026-11-10T08:00:00Z");

  private VigilTestApp app;
  private KnowledgeImport importer;
  private KnowledgeService knowledge;
  private int count;

  @BeforeAll
  void start() {
    VigilDatabase database = VigilDatabase.fresh();
    TestSecrets secrets = TestSecrets.create(database, AiFixture.secrets());
    app = VigilTestApp.start(database, secrets.folder(), List.of());
    app.awaitStarted();
    importer = app.context().getBean(KnowledgeImport.class);
    knowledge = app.context().getBean(KnowledgeService.class);
  }

  @AfterAll
  void stop() {
    app.close();
  }

  /** A new tenant with parts 3, 5 and 7 and no part 8. */
  private KnowledgeFixture tenant() {
    count++;
    return KnowledgeFixture.create(
        app,
        "Knowledge " + count,
        "k" + count + ".import.test",
        Map.of(),
        UnaryOperator.identity(),
        null);
  }

  private KnowledgeFixture imported() {
    KnowledgeFixture f = tenant();
    importer.importPart(f.tenant, DEMO, T, Map.of());
    return f;
  }

  @SuppressWarnings("unchecked")
  private static List<String> problems(Runnable call) {
    try {
      call.run();
    } catch (VigilError e) {
      assertThat(e.code()).isEqualTo(VigilError.Code.INVALID);
      assertThat(e.message()).isEqualTo("Part knowledge refused.");
      return (List<String>) e.details().get("problems");
    }
    throw new AssertionError("the part was not refused");
  }

  private List<String> problems(TenantId tenant, KnowledgePart part) {
    return problems(() -> importer.importPart(tenant, part, T, Map.of()));
  }

  private static long rows(KnowledgeFixture f, String table) {
    return f.postgres()
        .sql("SELECT count(*) FROM " + table + " WHERE tenant_id = :t")
        .param("t", f.tenant.value())
        .query(Long.class)
        .single();
  }

  private static List<String> texts() {
    List<String> texts = new ArrayList<>();
    for (PastIncidentPart row : DEMO.pastIncidents()) {
      Map<String, String> f = row.fields();
      texts.add(
          SearchTexts.of(
              List.of(f.get("alert_types").split(";")),
              List.of(f.get("affected_service_keys").split(";")),
              f.get("hypothesis_component_key")));
    }
    return texts;
  }

  private static RunbookPart runbook(RunbookPart r, int field, Object value) {
    return new RunbookPart(
        field == 0 ? (String) value : r.runbookKey(),
        field == 1 ? (String) value : r.version(),
        field == 2 ? (String) value : r.title(),
        field == 3 ? cast(value) : r.applicableAlertTypes(),
        field == 4 ? cast(value) : r.applicableComponentKinds(),
        field == 5 ? (String) value : r.text());
  }

  @SuppressWarnings("unchecked")
  private static List<String> cast(Object value) {
    return (List<String>) value;
  }

  private static KnowledgePart runbook0(int field, Object value) {
    List<RunbookPart> runbooks = new ArrayList<>(DEMO.runbooks());
    runbooks.set(0, runbook(runbooks.get(0), field, value));
    return KnowledgeParts.withRunbooks(DEMO, runbooks);
  }

  private static KnowledgePart past0(String column, String value) {
    List<PastIncidentPart> rows = new ArrayList<>(DEMO.pastIncidents());
    rows.set(0, KnowledgeParts.withFields(rows.get(0), Map.of(column, value)));
    return KnowledgeParts.withPastIncidents(DEMO, rows);
  }

  private static KnowledgePart settings(int field, String value) {
    SettingsPart s = DEMO.settings();
    return KnowledgeParts.withSettings(
        DEMO,
        new SettingsPart(
            field == 0 ? value : s.minSimilarity(),
            field == 1 ? value : s.runbookLimit(),
            field == 2 ? value : s.embeddingProviderKey(),
            field == 3 ? value : s.embeddingState()));
  }

  @Test
  @Order(1)
  @DisplayName("PM-M16-01 the demo part 8 imports its runbooks, past incidents and settings")
  void demoPartImports() {
    KnowledgeFixture f = imported();
    assertThat(knowledge.runbooks(f.tenant)).hasSize(7);
    assertThat(knowledge.pastIncidents(f.tenant)).hasSize(50);
    assertThat(knowledge.pastIncidents(f.tenant).getFirst().pastIncidentKey()).isEqualTo("PI-1001");
    assertThat(f.settings().embeddingState()).isEqualTo(EmbeddingState.READY);
  }

  @Test
  @Order(2)
  @DisplayName("DM-E-63 runbook fields, sorted lists and active")
  void runbookFields() {
    KnowledgeFixture f = imported();
    List<RunbookView> runbooks = knowledge.runbooks(f.tenant);
    assertThat(runbooks)
        .extracting(RunbookView::runbookKey)
        .containsExactly(
            "RB-API-01",
            "RB-DB-01",
            "RB-DB-02",
            "RB-RPT-01",
            "RB-SMS-01",
            "RB-SRV-01",
            "RB-USSD-01");
    RunbookView db = runbooks.get(1);
    assertThat(db.version()).isEqualTo(1);
    assertThat(db.title()).isEqualTo("Subscriber DB server: /data filling up");
    assertThat(db.applicableAlertTypes())
        .containsExactly("db_write_errors", "disk_full", "disk_space_low");
    assertThat(db.applicableComponentKinds()).containsExactly("database", "server");
    assertThat(db.text()).isEqualTo(DEMO.runbooks().get(1).text());
    assertThat(runbooks).allMatch(RunbookView::active);
    assertThat(runbooks.getFirst().applicableComponentKinds()).isEmpty();
  }

  @Test
  @Order(3)
  @DisplayName("DM-E-64 past incident fields; fix_action_key null when empty")
  void pastIncidentFields() {
    KnowledgeFixture f = imported();
    PastIncidentView first = knowledge.pastIncident(f.tenant, "PI-1001").orElseThrow();
    assertThat(first.title()).isEqualTo("Subscriber DB /data full after archive catch-up run");
    assertThat(first.recordLabel()).isEqualTo("Fictional record");
    assertThat(first.alertCount()).isEqualTo(120);
    assertThat(first.alertTypes())
        .containsExactly(
            "balance_api_errors",
            "bundle_api_errors",
            "db_write_errors",
            "disk_full",
            "sms_queue_growth",
            "ussd_synthetic_failed",
            "ussd_timeouts");
    assertThat(first.affectedServiceKeys())
        .containsExactly("svc-balance-check", "svc-bundle-purchase", "svc-sms-notifications");
    assertThat(first.hypothesisComponentKey()).isEqualTo("srv-db-01");
    assertThat(first.fixActionKey()).isEqualTo("purge-archived-txlogs");
    assertThat(first.fixSuccessCount()).isEqualTo(4);
    assertThat(first.active()).isTrue();
    PastIncidentView second = knowledge.pastIncident(f.tenant, "PI-1002").orElseThrow();
    assertThat(second.fixActionKey()).isNull();
    assertThat(second.fixSuccessCount()).isEqualTo(3);
  }

  @Test
  @Order(4)
  @DisplayName("AR-B-08 past incident times from the minute offsets and the import's start time")
  void times() {
    KnowledgeFixture f = tenant();
    List<PastIncidentPart> rows = new ArrayList<>(DEMO.pastIncidents());
    rows.set(
        1,
        KnowledgeParts.withFields(
            rows.get(1), Map.of("acknowledged_after_minutes", "", "recovered_after_minutes", "")));
    importer.importPart(f.tenant, KnowledgeParts.withPastIncidents(DEMO, rows), T, Map.of());
    PastIncidentView first = knowledge.pastIncident(f.tenant, "PI-1001").orElseThrow();
    Instant opened = T.minus(Duration.ofMinutes(42639));
    assertThat(first.openedAt()).isEqualTo(opened);
    assertThat(first.acknowledgedAt()).isEqualTo(opened.plus(Duration.ofMinutes(4)));
    assertThat(first.recoveredAt()).isEqualTo(opened.plus(Duration.ofMinutes(31)));
    PastIncidentView second = knowledge.pastIncident(f.tenant, "PI-1002").orElseThrow();
    assertThat(second.openedAt()).isEqualTo(T.minus(Duration.ofMinutes(41900)));
    assertThat(second.acknowledgedAt()).isNull();
    assertThat(second.recoveredAt()).isNull();
  }

  @Test
  @Order(5)
  @DisplayName(
      "AR-B-09 one batched embedding call before the transaction; refused when unreachable")
  void batchedEmbedding() {
    KnowledgeFixture f = tenant();
    TestEmbedding embedding = TestEmbedding.shared();
    int before = embedding.requestCount();
    importer.importPart(f.tenant, DEMO, T, Map.of());
    List<String> requests = embedding.requestsSince(before);
    assertThat(requests).hasSize(1);
    JsonNode body = JSON.readTree(requests.getFirst());
    List<String> inputs = new ArrayList<>();
    body.get("input").forEach(text -> inputs.add(text.stringValue()));
    assertThat(inputs).isEqualTo(texts());
    List<Map<String, Object>> usage =
        f.postgres()
            .sql(
                "SELECT task, provider_key, cost, data_categories::text AS categories, outcome"
                    + " FROM ai_gateway.ai_usage_record WHERE tenant_id = :t")
            .param("t", f.tenant.value())
            .query()
            .listOfRows();
    assertThat(usage).hasSize(1);
    assertThat(usage.getFirst().get("task")).isEqualTo("SL-T-3");
    assertThat(usage.getFirst().get("provider_key")).isEqualTo("local-embed");
    assertThat((BigDecimal) usage.getFirst().get("cost")).isEqualByComparingTo("0");
    assertThat(JSON.readTree((String) usage.getFirst().get("categories")))
        .isEqualTo(JSON.readTree("[\"incident_history\"]"));
    assertThat(usage.getFirst().get("outcome")).isEqualTo("succeeded");
    List<Map<String, Object>> embeddings = f.embeddings("local-embed");
    assertThat(embeddings).hasSize(50);
    assertThat(embeddings).allMatch(e -> e.get("model_name").equals("embeddinggemma"));

    KnowledgeFixture refused = tenant();
    embedding.stop();
    try {
      assertThat(problems(refused.tenant, DEMO))
          .containsExactly(
              "knowledge_settings.embedding_provider_key: the past incidents could not be"
                  + " embedded with local-embed (unreachable)");
    } finally {
      embedding.start();
    }
    for (String table :
        List.of(
            "knowledge.runbook",
            "knowledge.past_incident",
            "knowledge.incident_embedding",
            "knowledge.knowledge_settings")) {
      assertThat(rows(refused, table)).as(table).isZero();
    }
  }

  @Test
  @Order(6)
  @DisplayName("AR-G-30 the search texts of the embeddings are those of DM-R-51")
  void searchTexts() {
    KnowledgeFixture f = imported();
    Map<String, String> byKey = new java.util.HashMap<>();
    f.embeddings("local-embed")
        .forEach(e -> byKey.put((String) e.get("subject_key"), (String) e.get("search_text")));
    List<String> texts = texts();
    for (int i = 0; i < DEMO.pastIncidents().size(); i++) {
      assertThat(byKey.get(DEMO.pastIncidents().get(i).fields().get("past_incident_key")))
          .isEqualTo(texts.get(i));
    }
    assertThat(byKey.get("PI-1001"))
        .isEqualTo(
            "alert types: balance_api_errors, bundle_api_errors, db_write_errors, disk_full,"
                + " sms_queue_growth, ussd_synthetic_failed, ussd_timeouts; services:"
                + " svc-balance-check, svc-bundle-purchase, svc-sms-notifications; component:"
                + " srv-db-01");
  }

  @Test
  @Order(7)
  @DisplayName("DM-E-67 settings fields and each settings check")
  void settings() {
    KnowledgeFixture f = imported();
    KnowledgeSettingsView settings = f.settings();
    assertThat(settings.minSimilarity()).isEqualByComparingTo("0.90");
    assertThat(settings.runbookLimit()).isEqualTo(3);
    assertThat(settings.embeddingProviderKey()).isEqualTo("local-embed");
    assertThat(settings.embeddingState()).isEqualTo(EmbeddingState.READY);
    assertThat(settings.reembeddingProviderKey()).isNull();
    TenantId t = tenant().tenant;
    String decimal =
        "knowledge_settings.min_similarity: a decimal from 0 to 1 with at most 2 decimal places";
    for (String value : List.of("1.01", "0.905", "-0.5", ".9", "x", "")) {
      assertThat(problems(t, settings(0, value))).as(value).containsExactly(decimal);
    }
    assertThat(problems(t, settings(0, null))).containsExactly(decimal);
    assertThat(problems(t, settings(1, "0")))
        .containsExactly("knowledge_settings.runbook_limit: a whole number of at least 1");
    assertThat(problems(t, settings(2, "bad key")))
        .containsExactly("knowledge_settings.embedding_provider_key: not a key");
    assertThat(problems(t, settings(3, "reembedding")))
        .containsExactly("knowledge_settings.embedding_state: must be ready");
  }

  @Test
  @Order(8)
  @DisplayName("DM-R-57 each runbook and past-incident check, U+0000, and a second import refused")
  void checks() {
    TenantId t = tenant().tenant;
    String r = "runbooks[0].";
    assertThat(problems(t, runbook0(0, "bad key"))).containsExactly(r + "runbook_key: not a key");
    assertThat(problems(t, runbook0(0, "RB-DB-01")))
        .containsExactly("runbooks[1].runbook_key: used more than once");
    assertThat(problems(t, runbook0(1, "0")))
        .containsExactly(r + "version: a whole number of at least 1");
    assertThat(problems(t, runbook0(2, "x".repeat(201))))
        .containsExactly(r + "title: 1 to 200 characters");
    assertThat(problems(t, runbook0(3, List.of())))
        .containsExactly(r + "applicable_alert_types: at least one alert type");
    assertThat(problems(t, runbook0(3, List.of("a b", "disk_full", "disk_full"))))
        .containsExactly(
            r + "applicable_alert_types: 'a b' is not a key",
            r + "applicable_alert_types: 'disk_full' appears more than once");
    assertThat(problems(t, runbook0(4, List.of("router", "server", "server"))))
        .containsExactly(
            r
                + "applicable_component_kinds: 'router' is not one of server, application,"
                + " database, queue, check",
            r + "applicable_component_kinds: 'server' appears more than once");
    assertThat(problems(t, runbook0(5, ""))).containsExactly(r + "text: 1 to 20000 characters");
    assertThat(problems(t, runbook0(2, "a\u0000b")))
        .containsExactly(r + "title: holds the character U+0000");
    String p = "past_incidents[0].";
    Map<String, String> cases = new java.util.LinkedHashMap<>();
    cases.put("past_incident_key", "not a key");
    cases.put("title", "title: 1 to 200 characters");
    cases.put("record_label", "record_label: 1 to 60 characters");
    cases.put(
        "opened_minutes_before_import",
        "opened_minutes_before_import: a whole number from 1 to 43200");
    cases.put(
        "acknowledged_after_minutes",
        "acknowledged_after_minutes: empty or a whole number of at least 0");
    cases.put(
        "recovered_after_minutes",
        "recovered_after_minutes: empty or a whole number of at least 0");
    cases.put("alert_count", "alert_count: a whole number of at least 1");
    cases.put("hypothesis_component_key", "hypothesis_component_key: not a key");
    cases.put("summary", "summary: 1 to 2000 characters");
    cases.put("fix_description", "fix_description: 1 to 2000 characters");
    cases.put("fix_action_key", "fix_action_key: empty or a key");
    cases.put("fix_success_count", "fix_success_count: a whole number of at least 0");
    Map<String, String> values = new java.util.HashMap<>();
    values.put("past_incident_key", "bad key");
    values.put("title", "");
    values.put("record_label", "x".repeat(61));
    values.put("opened_minutes_before_import", "43201");
    values.put("acknowledged_after_minutes", "-1");
    values.put("recovered_after_minutes", "x");
    values.put("alert_count", "0");
    values.put("hypothesis_component_key", "");
    values.put("summary", "x".repeat(2001));
    values.put("fix_description", "");
    values.put("fix_action_key", "bad key");
    values.put("fix_success_count", "-1");
    cases.forEach(
        (column, problem) ->
            assertThat(problems(t, past0(column, values.get(column))))
                .as(column)
                .containsExactly(
                    p
                        + (column.equals("past_incident_key") ? "past_incident_key: " : "")
                        + problem));
    assertThat(problems(t, past0("opened_minutes_before_import", "0")))
        .containsExactly(p + "opened_minutes_before_import: a whole number from 1 to 43200");
    assertThat(problems(t, past0("past_incident_key", "PI-1002")))
        .containsExactly("past_incidents[1].past_incident_key: used more than once");
    assertThat(problems(t, past0("alert_types", "disk_full;x y;disk_full")))
        .containsExactly(
            p + "alert_types: 'x y' is not a key",
            p + "alert_types: 'disk_full' appears more than once");
    assertThat(problems(t, past0("affected_service_keys", "svc a")))
        .containsExactly(p + "affected_service_keys: 'svc a' is not a key");
    assertThat(problems(t, past0("summary", "a\u0000")))
        .containsExactly(p + "summary: holds the character U+0000");
    assertThat(rows(new KnowledgeFixtureView(app, t), "knowledge.runbook")).isZero();

    KnowledgeFixture f = imported();
    assertThat(problems(f.tenant, DEMO)).containsExactly("knowledge records already exist");
    assertThatThrownBy(() -> importer.importPart(f.tenant, null, T, Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @Order(9)
  @DisplayName("DM-E-65 the stored embeddings of the past incidents")
  void embeddings() {
    KnowledgeFixture f = imported();
    List<Map<String, Object>> embeddings = f.embeddings("local-embed");
    assertThat(embeddings).hasSize(50);
    for (Map<String, Object> e : embeddings) {
      assertThat(e.get("subject_kind")).isEqualTo("past_incident");
      assertThat(e.get("provider_key")).isEqualTo("local-embed");
      float[] expected = TestEmbedding.vector((String) e.get("search_text"));
      assertThat(parse((String) e.get("vector"))).isEqualTo(expected);
    }
    assertThat(T.truncatedTo(ChronoUnit.MILLIS)).isEqualTo(T);
  }

  static float[] parse(String vector) {
    String[] numbers = vector.substring(1, vector.length() - 1).split(",");
    float[] values = new float[numbers.length];
    for (int i = 0; i < numbers.length; i++) {
      values[i] = Float.parseFloat(numbers[i]);
    }
    return values;
  }

  /** Row counts of a tenant without a fixture. */
  private record KnowledgeFixtureView(VigilTestApp app, TenantId tenant) {}

  private static long rows(KnowledgeFixtureView f, String table) {
    return f.app()
        .database()
        .postgres()
        .sql("SELECT count(*) FROM " + table + " WHERE tenant_id = :t")
        .param("t", f.tenant().value())
        .query(Long.class)
        .single();
  }
}
