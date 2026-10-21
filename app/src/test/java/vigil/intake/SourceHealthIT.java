package vigil.intake;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vigil.intake.IntakeFixture.Recording;
import vigil.intake.api.Health;
import vigil.intake.api.IntakePart.SourcePart;
import vigil.intake.api.IntakeService;
import vigil.intake.api.SourceHealthChanged;
import vigil.intake.api.SourceTestResult;
import vigil.intake.api.TestOutcome;
import vigil.shared.TenantId;
import vigil.shared.VigilError;
import vigil.tenancy.api.TenantAliasChanged;
import vigil.tenancy.api.TenantService;
import vigil.testsupport.DoubleDelivery;
import vigil.testsupport.MutableClock;
import vigil.testsupport.TestSecrets;
import vigil.testsupport.TestTenants;
import vigil.testsupport.VigilDatabase;
import vigil.testsupport.VigilTestApp;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SourceHealthIT {

  private static final MutableClock CLOCK = new MutableClock(Instant.parse("2026-10-21T12:00:00Z"));
  private static final String HOST = "a.health.test";
  private static final String HOST_C = "c.health.test";

  private WireMockServer wiremock;
  private WireMockServer wiremockC;
  private VigilDatabase database;
  private TestSecrets secrets;
  private VigilTestApp app;
  private JdbcClient postgres;
  private IntakeService intake;
  private IntakeFixture.Events events;
  private TenantId tenant;
  private Instant t0;
  private UUID zabbixId;
  private UUID alertmanagerId;

  private Duration firstTestsWithin;
  private int zabbixTestsAfterWait;
  private int alertmanagerTestsAfterWait;
  private int zabbixTestsAfter31;
  private int alertmanagerTestsAfter31;
  private List<Map<String, Object>> zabbixChanges;
  private List<Object> zabbixEvents;

  /** The test clock and the event recorder. */
  static class TestBeans {

    @Bean
    @Primary
    Clock testClock() {
      return CLOCK;
    }

    @Bean
    IntakeFixture.Events intakeEvents() {
      return new IntakeFixture.Events();
    }
  }

  @BeforeAll
  void start() {
    wiremock = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort());
    wiremock.start();
    IntakeFixture.stub(wiremock);
    zabbixAnswers(200, "{\"jsonrpc\":\"2.0\",\"result\":[],\"id\":1}");
    wiremockC = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort());
    wiremockC.start();
    IntakeFixture.stub(wiremockC);
    database = VigilDatabase.fresh();
    secrets = IntakeFixture.secrets(database, "", "-c");
    start(database);
    t0 = CLOCK.instant();
  }

  private void start(VigilDatabase on) {
    app = VigilTestApp.start(on, secrets.folder(), List.of(), TestBeans.class);
    app.awaitStarted();
    postgres = on.postgres();
    intake = app.context().getBean(IntakeService.class);
    events = app.context().getBean(IntakeFixture.Events.class);
  }

  @AfterAll
  void stop() {
    app.close();
    wiremock.stop();
    wiremockC.stop();
  }

  /** Zabbix's test answers after a fixed delay of 1 s. */
  private void zabbixAnswers(int status, String body) {
    wiremock.stubFor(
        post(urlEqualTo(IntakeFixture.ZABBIX_PATH))
            .willReturn(aResponse().withStatus(status).withBody(body).withFixedDelay(1000)));
  }

  private int zabbixTests() {
    return wiremock.findAll(postRequestedFor(urlEqualTo(IntakeFixture.ZABBIX_PATH))).size();
  }

  private int alertmanagerTests() {
    return wiremock.findAll(getRequestedFor(urlEqualTo(IntakeFixture.ALERTMANAGER_PATH))).size();
  }

  private SourceHealthWork work() {
    return app.context().getBean(SourceHealthWork.class);
  }

  private Map<String, Object> row(UUID sourceId) {
    return postgres
        .sql("SELECT * FROM intake.monitoring_source WHERE id = :id")
        .param("id", sourceId)
        .query()
        .singleRow();
  }

  private Instant at(Map<String, Object> row, String column) {
    Timestamp value = (Timestamp) row.get(column);
    return value == null ? null : value.toInstant();
  }

  private List<Map<String, Object>> changes(UUID sourceId) {
    return postgres
        .sql(
            "SELECT from_health, to_health, at, cause FROM intake.source_health_change"
                + " WHERE tenant_id = :t AND source_id = :id ORDER BY at, id")
        .param("t", tenant.value())
        .param("id", sourceId)
        .query()
        .listOfRows();
  }

  private List<String> causes(UUID sourceId) {
    return changes(sourceId).stream()
        .map(change -> change.get("to_health") + " " + change.get("cause"))
        .toList();
  }

  private Instant plus(long seconds) {
    return t0.plusSeconds(seconds);
  }

  @Test
  @Order(1)
  @DisplayName("DM-R-39 each source is tested when created and then every health_check_interval")
  void testsAtCreationAndEveryInterval() {
    tenant = new TestTenants(app).tenant("Tenant A").host(HOST).create().id();
    long imported = System.nanoTime();
    IntakeFixture.importInto(
        app,
        tenant,
        "http://127.0.0.1:" + wiremock.port(),
        "",
        IntakeFixture.source(
            IntakeFixture.demoPart(),
            IntakeFixture.ZABBIX,
            s ->
                new SourcePart(
                    s.name(),
                    s.sourceKey(),
                    s.sourceKind(),
                    s.connectorInstance(),
                    s.webhookTokenRef(),
                    "100",
                    s.staleAfterS(),
                    s.disconnectedAfterS())));
    zabbixId = sourceId(IntakeFixture.ZABBIX);
    alertmanagerId = sourceId(IntakeFixture.ALERTMANAGER);
    IntakeFixture.await(
        () ->
            intake.sources(tenant).stream()
                .allMatch(s -> s.health() == Health.HEALTHY && s.lastTestAt() != null));
    firstTestsWithin = Duration.ofNanos(System.nanoTime() - imported);
    for (UUID source : List.of(zabbixId, alertmanagerId)) {
      Map<String, Object> row = row(source);
      assertThat(at(row, "last_contact_at")).isEqualTo(t0);
      assertThat(at(row, "last_test_at")).isEqualTo(t0);
      assertThat(causes(source)).containsExactly("disconnected source_created", "healthy contact");
    }
    IntakeFixture.sleep(Duration.ofSeconds(3));
    zabbixTestsAfterWait = zabbixTests();
    alertmanagerTestsAfterWait = alertmanagerTests();
    CLOCK.advance(Duration.ofSeconds(31));
    IntakeFixture.await(() -> plus(31).equals(at(row(alertmanagerId), "last_test_at")));
    IntakeFixture.sleep(Duration.ofMillis(1500));
    zabbixTestsAfter31 = zabbixTests();
    alertmanagerTestsAfter31 = alertmanagerTests();
    assertThat(List.of(zabbixTestsAfterWait, alertmanagerTestsAfterWait)).containsExactly(1, 1);
    assertThat(List.of(zabbixTestsAfter31, alertmanagerTestsAfter31)).containsExactly(1, 2);
  }

  private UUID sourceId(String key) {
    return intake.sources(tenant).stream()
        .filter(s -> s.sourceKey().equals(key))
        .findFirst()
        .orElseThrow()
        .id();
  }

  @Test
  @Order(2)
  @DisplayName(
      "AR-R-23 health every 1 s and the test every health_check_interval, per active tenant")
  void intakeTasks() {
    assertThat(work().scheduledTenants()).contains(tenant);
    assertThat(zabbixTestsAfterWait).as("no test while the clock stands still").isEqualTo(1);
    assertThat(alertmanagerTestsAfter31).as("alertmanager-lab due after 30 s").isEqualTo(2);
    assertThat(zabbixTestsAfter31).as("zabbix-lab due after 100 s").isEqualTo(1);
    assertThat(causes(alertmanagerId))
        .containsExactly("disconnected source_created", "healthy contact");
  }

  @Test
  @Order(3)
  @DisplayName("AR-R-19 intake's work starts when the tenant becomes active (AR-E-14)")
  void startedByAliasChange() {
    assertThat(work().scheduledTenants()).contains(tenant);
    assertThat(firstTestsWithin).isLessThan(IntakeFixture.WAIT);
  }

  @Test
  @Order(4)
  @DisplayName("AR-Q-05 a second delivery of TenantAliasChanged to intake gives the same result")
  void doubleDelivery() {
    IntakeAliasListener listener =
        AopTestUtils.getUltimateTargetObject(app.context().getBean(IntakeAliasListener.class));
    TransactionTemplate tx =
        new TransactionTemplate(app.context().getBean(PlatformTransactionManager.class));
    List<Set<TenantId>> scheduled = new CopyOnWriteArrayList<>();
    DoubleDelivery.assertSameResult(
        postgres,
        "intake",
        tx,
        () -> {
          listener.on(new TenantAliasChanged(HOST, tenant, null));
          scheduled.add(work().scheduledTenants());
        });
    assertThat(scheduled).hasSize(2);
    assertThat(scheduled.get(1)).isEqualTo(scheduled.get(0)).contains(tenant);
  }

  @Test
  @Order(5)
  @DisplayName("DM-R-40 the health rule applied: its change records and events")
  void healthRule() {
    CLOCK.advance(Duration.ofSeconds(91));
    IntakeFixture.await(() -> causes(zabbixId).size() == 4);
    assertThat(causes(zabbixId))
        .containsExactly(
            "disconnected source_created",
            "healthy contact",
            "stale contact_age",
            "healthy contact");

    zabbixAnswers(500, "");
    CLOCK.advance(Duration.ofSeconds(100));
    IntakeFixture.await(() -> causes(zabbixId).size() == 5);
    assertThat(causes(zabbixId).getLast()).isEqualTo("disconnected test_failed");
    assertThat(row(zabbixId).get("last_test_reason")).isEqualTo("HTTP 500");

    CLOCK.advance(Duration.ofSeconds(1));
    Recording zabbix = IntakeFixture.recording("0001-zabbix-lab.json");
    intake.receiveWebhook(
        tenant,
        IntakeFixture.ZABBIX,
        secrets.value("webhook-zabbix"),
        zabbix.contentType(),
        zabbix.bytes());
    assertThat(causes(zabbixId).getLast()).isEqualTo("healthy contact");

    CLOCK.advance(Duration.ofSeconds(301));
    IntakeFixture.await(() -> plus(524).equals(at(row(zabbixId), "last_test_at")));
    zabbixChanges = changes(zabbixId);
    assertThat(causes(zabbixId))
        .containsExactly(
            "disconnected source_created",
            "healthy contact",
            "stale contact_age",
            "healthy contact",
            "disconnected test_failed",
            "healthy contact",
            "disconnected contact_age");
    assertThat(row(zabbixId).get("health")).isEqualTo("disconnected");
    assertThat(row(zabbixId).get("last_test_outcome")).isEqualTo("failed");
    IntakeFixture.await(() -> events.of(tenant).stream().filter(this::ofZabbix).count() == 6);
    zabbixEvents = events.of(tenant).stream().filter(this::ofZabbix).toList();
  }

  private boolean ofZabbix(Object event) {
    return event instanceof SourceHealthChanged changed && changed.sourceId().equals(zabbixId);
  }

  @Test
  @Order(6)
  @DisplayName("PM-M06-06 source health is computed and its changes are published")
  void healthPublished() {
    assertThat(zabbixEvents).hasSize(zabbixChanges.size() - 1);
    for (int i = 1; i < zabbixChanges.size(); i++) {
      assertThat(((SourceHealthChanged) zabbixEvents.get(i - 1)).health().key())
          .isEqualTo(zabbixChanges.get(i).get("to_health"));
    }
  }

  @Test
  @Order(7)
  @DisplayName("AR-E-03 SourceHealthChanged with the source id and its new health")
  void sourceHealthChanged() {
    assertThat(zabbixEvents)
        .containsExactly(
            new SourceHealthChanged(tenant, zabbixId, Health.HEALTHY),
            new SourceHealthChanged(tenant, zabbixId, Health.STALE),
            new SourceHealthChanged(tenant, zabbixId, Health.HEALTHY),
            new SourceHealthChanged(tenant, zabbixId, Health.DISCONNECTED),
            new SourceHealthChanged(tenant, zabbixId, Health.HEALTHY),
            new SourceHealthChanged(tenant, zabbixId, Health.DISCONNECTED));
  }

  @Test
  @Order(8)
  @DisplayName("DM-E-23 the source health changes: from, to, time and cause")
  void healthChangeRecords() {
    List<String> rows = new ArrayList<>();
    for (Map<String, Object> change : zabbixChanges) {
      rows.add(
          change.get("from_health")
              + " "
              + change.get("to_health")
              + " "
              + Duration.between(t0, ((Timestamp) change.get("at")).toInstant()).toSeconds()
              + " "
              + change.get("cause"));
    }
    assertThat(rows)
        .containsExactly(
            "null disconnected 0 source_created",
            "disconnected healthy 0 contact",
            "healthy stale 122 contact_age",
            "stale healthy 122 contact",
            "healthy disconnected 222 test_failed",
            "disconnected healthy 223 contact",
            "healthy disconnected 524 contact_age");
  }

  @Test
  @Order(9)
  @DisplayName("DM-E23-R1 whether a source was healthy during a whole interval")
  void healthyThroughout() {
    assertThat(intake.healthyThroughout(tenant, zabbixId, plus(10), plus(100))).isTrue();
    assertThat(intake.healthyThroughout(tenant, zabbixId, plus(100), plus(122))).isFalse();
    assertThat(intake.healthyThroughout(tenant, zabbixId, plus(-1), plus(10))).isFalse();
    assertThat(intake.healthyThroughout(tenant, UUID.randomUUID(), plus(10), plus(100))).isFalse();
    assertThatThrownBy(() -> intake.healthyThroughout(tenant, zabbixId, plus(100), plus(10)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @Order(10)
  @DisplayName("PM-M06-07 the connection test on request, recorded as a periodic test")
  void testOnRequest() {
    SourceTestResult passed = intake.testSource(tenant, IntakeFixture.ALERTMANAGER);
    assertThat(passed).isEqualTo(new SourceTestResult(TestOutcome.PASSED, null, Health.HEALTHY));
    assertThat(at(row(alertmanagerId), "last_test_at")).isEqualTo(CLOCK.instant());
    wiremock.stubFor(
        get(urlEqualTo(IntakeFixture.ALERTMANAGER_PATH)).willReturn(aResponse().withStatus(401)));
    try {
      SourceTestResult failed = intake.testSource(tenant, IntakeFixture.ALERTMANAGER);
      assertThat(failed)
          .isEqualTo(new SourceTestResult(TestOutcome.FAILED, "HTTP 401", Health.DISCONNECTED));
      assertThat(row(alertmanagerId).get("last_test_reason")).isEqualTo("HTTP 401");
    } finally {
      wiremock.stubFor(
          get(urlEqualTo(IntakeFixture.ALERTMANAGER_PATH)).willReturn(aResponse().withStatus(200)));
    }
    VigilError notFound =
        catchThrowableOfType(VigilError.class, () -> intake.testSource(tenant, "nope"));
    assertThat(notFound.code()).isEqualTo(VigilError.Code.NOT_FOUND);
  }

  @Test
  @Order(11)
  @DisplayName("DM-R-70 scheduled health work runs only for active tenants")
  void onlyActiveTenants() {
    TenantId tenantC =
        IntakeFixture.create(app, "Tenant C", HOST_C, "http://127.0.0.1:" + wiremockC.port(), "-c");
    IntakeFixture.await(() -> work().scheduledTenants().contains(tenantC));
    IntakeFixture.awaitFirstTests(intake, tenantC);
    TenantId tenantD = new TestTenants(app).tenant("D").create().id();
    app.context().getBean(TenantService.class).setAlias(HOST_C, tenantD, "installation token");
    IntakeFixture.await(() -> !work().scheduledTenants().contains(tenantC));
    int baseline = wiremockC.findAll(anyRequestedFor(anyUrl())).size();
    int alertmanagerBefore = alertmanagerTests();
    int zabbixBefore = zabbixTests();
    CLOCK.advance(Duration.ofSeconds(31));
    IntakeFixture.await(() -> alertmanagerTests() == alertmanagerBefore + 1);
    IntakeFixture.sleep(Duration.ofMillis(1500));
    assertThat(wiremockC.findAll(anyRequestedFor(anyUrl()))).hasSize(baseline);
    assertThat(zabbixTests()).isEqualTo(zabbixBefore);
    assertThat(work().scheduledTenants()).contains(tenantD, tenant).doesNotContain(tenantC);
    assertThat(intake.sources(tenantD)).isEmpty();
  }

  @Test
  @Order(12)
  @DisplayName("AR-R-24 restart step (6) starts intake's scheduled work")
  void restartStep6() {
    app.close();
    start(database);
    IntakeFixture.await(() -> work().scheduledTenants().contains(tenant));
  }
}
