package vigil.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import tools.jackson.databind.json.JsonMapper;
import vigil.aigateway.AiFixture;
import vigil.knowledge.api.KnowledgePart;
import vigil.knowledge.api.KnowledgePart.PastIncidentPart;
import vigil.knowledge.api.KnowledgePart.SettingsPart;
import vigil.knowledge.api.KnowledgeService;
import vigil.knowledge.api.RunbookView;
import vigil.knowledge.api.SearchOutcome;
import vigil.knowledge.api.SearchRequest;
import vigil.knowledge.api.SearchResult;
import vigil.knowledge.api.SimilarityResultView;
import vigil.setup.KnowledgeParts;
import vigil.shared.Ids;
import vigil.testsupport.TestEmbedding;
import vigil.testsupport.TestSecrets;
import vigil.testsupport.VigilDatabase;
import vigil.testsupport.VigilTestApp;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SimilaritySearchIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final KnowledgePart DEMO = KnowledgeParts.demoPart();
  static final List<String> STORM_TYPES =
      List.of(
          "balance_api_errors",
          "bundle_api_errors",
          "db_write_errors",
          "disk_full",
          "sms_queue_growth",
          "ussd_synthetic_failed",
          "ussd_timeouts");
  static final List<String> STORM_SERVICES =
      List.of("svc-balance-check", "svc-bundle-purchase", "svc-sms-notifications");

  private VigilTestApp app;
  private KnowledgeService knowledge;
  private int count;

  @BeforeAll
  void start() {
    VigilDatabase database = VigilDatabase.fresh();
    TestSecrets secrets = TestSecrets.create(database, AiFixture.secrets());
    app = VigilTestApp.start(database, secrets.folder(), List.of());
    app.awaitStarted();
    knowledge = app.context().getBean(KnowledgeService.class);
  }

  @AfterAll
  void stop() {
    app.close();
  }

  private KnowledgeFixture tenant(KnowledgePart part) {
    count++;
    return KnowledgeFixture.create(
        app,
        "Search " + count,
        "s" + count + ".search.test",
        Map.of(),
        UnaryOperator.identity(),
        part);
  }

  private static SearchRequest storm(KnowledgeFixture f, UUID incident) {
    return new SearchRequest(
        f.tenant, incident, Ids.newId(), STORM_TYPES, STORM_SERVICES, "srv-db-01");
  }

  private static SimilarityResultView searched(SearchResult result) {
    assertThat(result).isInstanceOf(SearchResult.Searched.class);
    return ((SearchResult.Searched) result).result();
  }

  private static long rows(KnowledgeFixture f, String table) {
    return f.postgres()
        .sql("SELECT count(*) FROM " + table + " WHERE tenant_id = :t")
        .param("t", f.tenant.value())
        .query(Long.class)
        .single();
  }

  private void assertStormMatch() {
    KnowledgeFixture f = tenant(DEMO);
    UUID incident = Ids.newId();
    SearchRequest request = storm(f, incident);
    SearchResult result = knowledge.search(request);
    SimilarityResultView view = searched(result);
    assertThat(view.outcome()).isEqualTo(SearchOutcome.MATCH);
    assertThat(view.bestPastIncidentKey()).isEqualTo("PI-1001");
    assertThat(view.score()).isEqualByComparingTo("1.00");
    assertThat(view.score().scale()).isEqualTo(2);
    assertThat(view.successCount()).isEqualTo(4);
    assertThat(view.fixActionKey()).isEqualTo("purge-archived-txlogs");
    assertThat(view.minSimilarity()).isEqualByComparingTo("0.90");
    assertThat(view.incidentId()).isEqualTo(incident);
    assertThat(view.runId()).isEqualTo(request.runId());
    assertThat(view.providerKey()).isEqualTo("local-embed");
    assertThat(((SearchResult.Searched) result).bestPastIncident().pastIncidentKey())
        .isEqualTo("PI-1001");
    assertThat(knowledge.latestSearchResult(f.tenant, incident)).contains(view);
  }

  @Test
  @Order(1)
  @DisplayName("SL-A1-06 the storm's search gives PI-1001 with 1.00 and success count 4")
  void stormMatch() {
    assertStormMatch();
  }

  @Test
  @Order(2)
  @DisplayName("SL-T-3 similar-incident search, product side")
  void productSide() {
    assertStormMatch();
  }

  @Test
  @Order(3)
  @DisplayName("PM-M16-03 one embedding request with the settings' provider; the live embedding")
  void oneRequest() {
    KnowledgeFixture f = tenant(DEMO);
    UUID incident = Ids.newId();
    int before = TestEmbedding.shared().requestCount();
    searched(knowledge.search(storm(f, incident)));
    List<String> requests = TestEmbedding.shared().requestsSince(before);
    assertThat(requests).hasSize(1);
    assertThat(JSON.readTree(requests.getFirst()).get("input").size()).isEqualTo(1);
    List<Map<String, Object>> usage =
        f.postgres()
            .sql(
                "SELECT provider_key, data_categories::text AS categories FROM"
                    + " ai_gateway.ai_usage_record WHERE tenant_id = :t AND incident_id = :i")
            .param("t", f.tenant.value())
            .param("i", incident)
            .query()
            .listOfRows();
    assertThat(usage).hasSize(1);
    assertThat(usage.getFirst().get("provider_key")).isEqualTo("local-embed");
    assertThat(JSON.readTree((String) usage.getFirst().get("categories")))
        .isEqualTo(JSON.readTree("[\"alert_data\", \"service_context\"]"));
    List<Map<String, Object>> live =
        f.postgres()
            .sql(
                "SELECT subject_key, provider_key, model_name, search_text FROM"
                    + " knowledge.incident_embedding WHERE tenant_id = :t AND subject_kind = 'incident'")
            .param("t", f.tenant.value())
            .query()
            .listOfRows();
    assertThat(live).hasSize(1);
    assertThat(live.getFirst().get("subject_key")).isEqualTo(incident.toString());
    assertThat(live.getFirst().get("provider_key")).isEqualTo("local-embed");
    assertThat(live.getFirst().get("model_name")).isEqualTo("embeddinggemma");
    assertThat(live.getFirst().get("search_text"))
        .isEqualTo(SearchTexts.of(STORM_TYPES, STORM_SERVICES, "srv-db-01"));
  }

  @Test
  @Order(4)
  @DisplayName("DM-E-66 no_match with the best rounded score; ties to the first key")
  void noMatchAndTies() {
    KnowledgeFixture f = tenant(DEMO);
    SimilarityResultView none =
        searched(
            knowledge.search(
                new SearchRequest(
                    f.tenant, Ids.newId(), null, List.of("cpu_high"), List.of(), null)));
    assertThat(none.outcome()).isEqualTo(SearchOutcome.NO_MATCH);
    assertThat(none.score())
        .isEqualByComparingTo(best(SearchTexts.of(List.of("cpu_high"), List.of(), null)));
    assertThat(none.bestPastIncidentKey()).isNull();
    assertThat(none.successCount()).isNull();
    assertThat(none.fixActionKey()).isNull();
    assertThat(none.runId()).isNull();

    List<PastIncidentPart> rows = new ArrayList<>(DEMO.pastIncidents());
    PastIncidentPart first = rows.removeFirst();
    int i = 0;
    while (!rows.get(i).fields().get("past_incident_key").equals("PI-1011")) {
      i++;
    }
    PastIncidentPart same =
        KnowledgeParts.withFields(
            rows.remove(i),
            Map.of(
                "alert_types", String.join(";", STORM_TYPES),
                "affected_service_keys", String.join(";", STORM_SERVICES),
                "hypothesis_component_key", "srv-db-01"));
    rows.addFirst(first);
    rows.addFirst(same);
    KnowledgeFixture tie = tenant(KnowledgeParts.withPastIncidents(DEMO, rows));
    SimilarityResultView view = searched(knowledge.search(storm(tie, Ids.newId())));
    assertThat(view.score()).isEqualByComparingTo("1.00");
    assertThat(view.bestPastIncidentKey()).isEqualTo("PI-1001");
  }

  /** The best rounded score of the demo past incidents for a query text, computed here. */
  private static BigDecimal best(String query) {
    float[] q = TestEmbedding.vector(query);
    BigDecimal best = null;
    for (PastIncidentPart row : DEMO.pastIncidents()) {
      Map<String, String> fields = row.fields();
      float[] p =
          TestEmbedding.vector(
              SearchTexts.of(
                  List.of(fields.get("alert_types").split(";")),
                  List.of(fields.get("affected_service_keys").split(";")),
                  fields.get("hypothesis_component_key")));
      BigDecimal score = BigDecimal.valueOf(cosine(q, p)).setScale(2, RoundingMode.HALF_UP);
      if (best == null || score.compareTo(best) > 0) {
        best = score;
      }
    }
    return best;
  }

  private static double cosine(float[] a, float[] b) {
    double dot = 0;
    double na = 0;
    double nb = 0;
    for (int i = 0; i < a.length; i++) {
      dot += (double) a[i] * b[i];
      na += (double) a[i] * a[i];
      nb += (double) b[i] * b[i];
    }
    return dot / Math.sqrt(na * nb);
  }

  @Test
  @Order(5)
  @DisplayName("AR-P-07 the score is the cosine similarity of the stored vectors, rounded half-up")
  void score() {
    KnowledgeFixture f = tenant(DEMO);
    List<String> types = STORM_TYPES.subList(0, 5);
    SimilarityResultView view =
        searched(
            knowledge.search(
                new SearchRequest(
                    f.tenant, Ids.newId(), null, types, STORM_SERVICES, "srv-db-01")));
    BigDecimal expected = best(SearchTexts.of(types, STORM_SERVICES, "srv-db-01"));
    assertThat(view.score()).isEqualByComparingTo(expected);
    assertThat(view.score().scale()).isEqualTo(2);
  }

  @Test
  @Order(6)
  @DisplayName("DM-E-66 latestSearchResult after two searches is the second")
  void latest() {
    KnowledgeFixture f = tenant(DEMO);
    UUID incident = Ids.newId();
    searched(knowledge.search(storm(f, incident)));
    SimilarityResultView second = searched(knowledge.search(storm(f, incident)));
    assertThat(knowledge.latestSearchResult(f.tenant, incident)).contains(second);
  }

  @Test
  @Order(7)
  @DisplayName("DM-E-72 the settings' provider only: unreachable or refused gives unavailable")
  void unavailable() {
    KnowledgeFixture f = tenant(DEMO);
    TestEmbedding.shared().stop();
    try {
      assertThat(knowledge.search(storm(f, Ids.newId())))
          .isEqualTo(new SearchResult.Unavailable(SearchResult.Reason.UNREACHABLE));
    } finally {
      TestEmbedding.shared().start();
    }
    assertThat(rows(f, "knowledge.similarity_search_result")).isZero();
    assertThat(
            f.postgres()
                .sql(
                    "SELECT count(*) FROM knowledge.incident_embedding WHERE tenant_id = :t"
                        + " AND subject_kind = 'incident'")
                .param("t", f.tenant.value())
                .query(Long.class)
                .single())
        .isZero();
    List<String> others = List.of("claude", "local-llm", "openai", "openai-embed");
    f.setRouting("incident_history", others);
    searched(knowledge.search(storm(f, Ids.newId())));
    f.setRouting("alert_data", others);
    assertThat(knowledge.search(storm(f, Ids.newId())))
        .isEqualTo(new SearchResult.Unavailable(SearchResult.Reason.REFUSED_BY_DATA_ROUTING));
    assertThat(rows(f, "knowledge.similarity_search_result")).isEqualTo(1);
  }

  @Test
  @Order(8)
  @DisplayName("PM-M16-05 history holds only imported records")
  void historyOnlyImported() {
    KnowledgeFixture f = tenant(DEMO);
    searched(knowledge.search(storm(f, Ids.newId())));
    searched(knowledge.search(storm(f, Ids.newId())));
    assertThat(knowledge.pastIncidents(f.tenant)).hasSize(50);
    assertThat(rows(f, "knowledge.past_incident")).isEqualTo(50);
  }

  private void assertRunbooks() {
    KnowledgeFixture f = tenant(DEMO);
    assertThat(knowledge.applicableRunbooks(f.tenant, STORM_TYPES, "server"))
        .extracting(RunbookView::runbookKey)
        .containsExactly("RB-API-01", "RB-DB-01", "RB-USSD-01");
    assertThat(knowledge.applicableRunbooks(f.tenant, STORM_TYPES, null))
        .allMatch(runbook -> runbook.applicableComponentKinds().isEmpty())
        .extracting(RunbookView::runbookKey)
        .containsExactly("RB-API-01", "RB-USSD-01", "RB-SMS-01");
    SettingsPart s = DEMO.settings();
    KnowledgeFixture one =
        tenant(
            KnowledgeParts.withSettings(
                DEMO,
                new SettingsPart(
                    s.minSimilarity(), "1", s.embeddingProviderKey(), s.embeddingState())));
    assertThat(knowledge.applicableRunbooks(one.tenant, STORM_TYPES, "server"))
        .extracting(RunbookView::runbookKey)
        .containsExactly("RB-API-01");
  }

  @Test
  @Order(9)
  @DisplayName("DM-R-50 runbook applicability and order, limit runbook_limit")
  void runbookRules() {
    assertRunbooks();
  }

  @Test
  @Order(10)
  @DisplayName("PM-M16-02 the runbooks that apply to alert types and a component kind")
  void runbooks() {
    assertRunbooks();
  }
}
