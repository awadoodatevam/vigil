package vigil.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vigil.aigateway.AiFixture;
import vigil.aigateway.api.AiTask;
import vigil.aigateway.api.TaskProviderChanged;
import vigil.knowledge.api.EmbeddingState;
import vigil.knowledge.api.KnowledgePart;
import vigil.knowledge.api.KnowledgeService;
import vigil.knowledge.api.KnowledgeSettingsView;
import vigil.knowledge.api.SearchRequest;
import vigil.knowledge.api.SearchResult;
import vigil.setup.KnowledgeParts;
import vigil.shared.Ids;
import vigil.shared.TenantId;
import vigil.tenancy.api.TenantAliasChanged;
import vigil.tenancy.api.TenantService;
import vigil.testsupport.DoubleDelivery;
import vigil.testsupport.LogLines;
import vigil.testsupport.TestEmbedding;
import vigil.testsupport.TestSecrets;
import vigil.testsupport.VigilDatabase;
import vigil.testsupport.VigilTestApp;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@ExtendWith(OutputCaptureExtension.class)
class ReembeddingIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final KnowledgePart DEMO = KnowledgeParts.demoPart();
  private static final Duration WAIT = Duration.ofSeconds(10);

  private VigilTestApp app;
  private KnowledgeService knowledge;
  private Reembedding reembedding;
  private KnowledgeListeners listeners;
  private TransactionTemplate tx;
  private int count;
  private KnowledgeFixture first;
  private int firstRequests;

  @BeforeAll
  void start() {
    VigilDatabase database = VigilDatabase.fresh();
    TestSecrets secrets = TestSecrets.create(database, AiFixture.secrets());
    app = VigilTestApp.start(database, secrets.folder(), List.of());
    app.awaitStarted();
    knowledge = app.context().getBean(KnowledgeService.class);
    reembedding = app.context().getBean(Reembedding.class);
    listeners =
        AopTestUtils.getUltimateTargetObject(app.context().getBean(KnowledgeListeners.class));
    tx = new TransactionTemplate(app.context().getBean(PlatformTransactionManager.class));
  }

  @AfterAll
  void stop() {
    app.close();
  }

  private String host() {
    return "r" + count + ".reembed.test";
  }

  private KnowledgeFixture tenant() {
    count++;
    return KnowledgeFixture.create(
        app, "Reembed " + count, host(), Map.of(), UnaryOperator.identity(), DEMO);
  }

  private void publish(TenantId tenant, AiTask task, String providerKey) {
    tx.executeWithoutResult(
        status ->
            ((ApplicationEventPublisher) app.context())
                .publishEvent(new TaskProviderChanged(tenant, task, providerKey)));
  }

  private static void await(BooleanSupplier condition) {
    Instant end = Instant.now().plus(WAIT);
    while (!condition.getAsBoolean()) {
      if (Instant.now().isAfter(end)) {
        throw new AssertionError("condition not met within 10 s");
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError(e);
      }
    }
  }

  /** Waits until every event publication is completed. */
  private void awaitPublications() {
    await(
        () ->
            app.database()
                    .postgres()
                    .sql(
                        "SELECT count(*) FROM platform.event_publication WHERE completion_date IS NULL")
                    .query(Long.class)
                    .single()
                == 0);
  }

  private static boolean reembeddingToward(KnowledgeFixture f, String providerKey) {
    KnowledgeSettingsView settings = f.settings();
    return settings.embeddingState() == EmbeddingState.REEMBEDDING
        && providerKey.equals(settings.reembeddingProviderKey());
  }

  private void awaitWorkerEnded(TenantId tenant) {
    await(() -> reembedding.workerThread(tenant).map(t -> !t.isAlive()).orElse(true));
  }

  private static List<JsonNode> warnings(CapturedOutput output, TenantId tenant) {
    String text =
        "re-embedding of tenant "
            + tenant.value()
            + " with provider openai-embed did not succeed (unreachable); retried within 30 s";
    return LogLines.application(output.getAll()).stream()
        .filter(line -> line.path("message").asString("").equals(text))
        .toList();
  }

  @Test
  @Order(1)
  @DisplayName("DM-SM-15 task provider changed starts re-embedding; searches refused meanwhile")
  void stateMachine() {
    first = tenant();
    firstRequests = TestEmbedding.shared().requestCount();
    TestEmbedding.shared().stop();
    try {
      publish(first.tenant, AiTask.SL_T_3, "openai-embed");
      await(() -> reembeddingToward(first, "openai-embed"));
      assertThat(first.settings().embeddingProviderKey()).isEqualTo("local-embed");
      UUID incident = Ids.newId();
      assertThat(
              knowledge.search(
                  new SearchRequest(
                      first.tenant, incident, null, List.of("disk_full"), List.of(), null)))
          .isEqualTo(new SearchResult.Unavailable(SearchResult.Reason.REEMBEDDING));
      assertThat(
              first
                  .postgres()
                  .sql("SELECT count(*) FROM ai_gateway.ai_usage_record WHERE incident_id = :i")
                  .param("i", incident)
                  .query(Long.class)
                  .single())
          .isZero();
      awaitWorkerEnded(first.tenant);
    } finally {
      TestEmbedding.shared().start();
    }
  }

  @Test
  @Order(2)
  @DisplayName("PM-M16-04 the worker embeds the past incidents with the new provider, then ready")
  void reembeds() {
    reembedding.tick(first.tenant);
    await(() -> first.settings().embeddingState() == EmbeddingState.READY);
    KnowledgeSettingsView settings = first.settings();
    assertThat(settings.embeddingProviderKey()).isEqualTo("openai-embed");
    assertThat(settings.reembeddingProviderKey()).isNull();
    assertThat(first.embeddings("openai-embed")).hasSize(50);
    assertThat(first.embeddings("local-embed")).hasSize(50);
    List<JsonNode> batches = new ArrayList<>();
    for (String body : TestEmbedding.shared().requestsSince(firstRequests)) {
      JsonNode request = JSON.readTree(body);
      if (request.path("model").asString("").equals("text-embedding-3-small")) {
        batches.add(request);
      }
    }
    assertThat(batches).isNotEmpty().allMatch(batch -> batch.get("input").size() <= 50);
    SearchResult result =
        knowledge.search(
            new SearchRequest(
                first.tenant,
                Ids.newId(),
                null,
                SimilaritySearchIT.STORM_TYPES,
                SimilaritySearchIT.STORM_SERVICES,
                "srv-db-01"));
    assertThat(((SearchResult.Searched) result).result().providerKey()).isEqualTo("openai-embed");
    assertThat(((SearchResult.Searched) result).result().bestPastIncidentKey())
        .isEqualTo("PI-1001");
  }

  @Test
  @Order(3)
  @DisplayName("AR-TM-07 a failed re-embedding stays reembedding and is retried at the next tick")
  void retry(CapturedOutput output) {
    KnowledgeFixture f = tenant();
    TestEmbedding.shared().stop();
    try {
      publish(f.tenant, AiTask.SL_T_3, "openai-embed");
      await(() -> reembeddingToward(f, "openai-embed"));
      awaitWorkerEnded(f.tenant);
      int before = warnings(output, f.tenant).size();
      reembedding.tick(f.tenant);
      await(() -> warnings(output, f.tenant).size() > before);
      awaitWorkerEnded(f.tenant);
      assertThat(reembeddingToward(f, "openai-embed")).isTrue();
    } finally {
      TestEmbedding.shared().start();
    }
    reembedding.tick(f.tenant);
    await(() -> f.settings().embeddingState() == EmbeddingState.READY);
    assertThat(f.settings().embeddingProviderKey()).isEqualTo("openai-embed");
    assertThat(Reembedding.INTERVAL).isEqualTo(Duration.ofSeconds(30));
  }

  @Test
  @Order(4)
  @DisplayName("AR-R-30 re-embedding runs on one virtual thread per tenant")
  void virtualThread() {
    KnowledgeFixture f = tenant();
    TestEmbedding.shared().delay(Duration.ofSeconds(2));
    try {
      publish(f.tenant, AiTask.SL_T_3, "openai-embed");
      await(() -> reembedding.workerThread(f.tenant).isPresent());
      reembedding.start(f.tenant);
      reembedding.start(f.tenant);
      Thread worker = reembedding.workerThread(f.tenant).orElseThrow();
      assertThat(worker.isVirtual()).isTrue();
      assertThat(worker.getName()).isEqualTo("reembed-" + f.tenant.value());
      awaitWorkerEnded(f.tenant);
      assertThat(reembedding.workerThread(f.tenant)).containsSame(worker);
    } finally {
      TestEmbedding.shared().delay(Duration.ZERO);
    }
  }

  @Test
  @Order(5)
  @DisplayName("AR-R-23 the re-embedding task of each active tenant with knowledge settings")
  void tasks() {
    count++;
    KnowledgeFixture none =
        KnowledgeFixture.create(
            app, "Reembed " + count, host(), Map.of(), UnaryOperator.identity(), null);
    KnowledgeFixture f = tenant();
    assertThat(reembedding.scheduledTenants()).contains(f.tenant).doesNotContain(none.tenant);
  }

  @Test
  @Order(6)
  @DisplayName("AR-E-11 an event for the current provider or another task changes nothing")
  void noChange() {
    KnowledgeFixture f = tenant();
    List<Map<String, Object>> before = settingsRow(f);
    publish(f.tenant, AiTask.SL_T_3, "local-embed");
    publish(f.tenant, AiTask.SL_T_1, "openai-embed");
    awaitPublications();
    assertThat(settingsRow(f)).isEqualTo(before);
  }

  private static List<Map<String, Object>> settingsRow(KnowledgeFixture f) {
    return f.postgres()
        .sql("SELECT * FROM knowledge.knowledge_settings WHERE tenant_id = :t")
        .param("t", f.tenant.value())
        .query()
        .listOfRows();
  }

  @Test
  @Order(7)
  @DisplayName("AR-Q-05 both listeners give the same result for a second delivery")
  void doubleDelivery() {
    KnowledgeFixture f = tenant();
    TestEmbedding.shared().stop();
    try {
      DoubleDelivery.assertSameResult(
          f.postgres(),
          "knowledge",
          tx,
          () -> listeners.on(new TaskProviderChanged(f.tenant, AiTask.SL_T_3, "openai-embed")));
      awaitWorkerEnded(f.tenant);
    } finally {
      TestEmbedding.shared().start();
    }
    assertThat(reembeddingToward(f, "openai-embed")).isTrue();
    DoubleDelivery.assertSameResult(
        f.postgres(),
        "knowledge",
        tx,
        () -> listeners.on(new TenantAliasChanged(host(), f.tenant, null)));
    count++;
    KnowledgeFixture inactive =
        KnowledgeFixture.create(
            app, "Reembed " + count, null, Map.of(), UnaryOperator.identity(), DEMO);
    List<Map<String, Object>> before = settingsRow(inactive);
    tx.executeWithoutResult(
        status ->
            listeners.on(new TaskProviderChanged(inactive.tenant, AiTask.SL_T_3, "openai-embed")));
    assertThat(settingsRow(inactive)).isEqualTo(before);
  }

  @Test
  @Order(8)
  @DisplayName("AR-E-14 the task of a tenant that is no longer active is cancelled")
  void aliasMoved() {
    KnowledgeFixture moved = tenant();
    String movedHost = host();
    KnowledgeFixture other = tenant();
    assertThat(reembedding.scheduledTenants()).contains(moved.tenant, other.tenant);
    app.context()
        .getBean(TenantService.class)
        .setAlias(movedHost, other.tenant, "installation token");
    await(() -> !reembedding.scheduledTenants().contains(moved.tenant));
    assertThat(reembedding.scheduledTenants()).contains(other.tenant);
  }
}
