package vigil.correlation;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import vigil.correlation.api.CorrelationScheduling;
import vigil.correlation.api.CorrelationService;
import vigil.intake.IntakeFixture;
import vigil.intake.api.AlertReceived;
import vigil.intake.api.AlertView;
import vigil.routing.RoutingFixture;
import vigil.routing.api.RoutingImport;
import vigil.shared.TenantId;
import vigil.tenancy.api.TenantAliasChanged;
import vigil.tenancy.api.TenantService;
import vigil.testsupport.DoubleDelivery;
import vigil.testsupport.LogLines;
import vigil.testsupport.MutableClock;
import vigil.testsupport.TestSecrets;
import vigil.testsupport.TestTenants;
import vigil.testsupport.VigilDatabase;
import vigil.testsupport.VigilTestApp;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@ExtendWith(OutputCaptureExtension.class)
class CorrelationWorkerIT {

  private static final MutableClock CLOCK = new MutableClock(Instant.parse("2026-10-22T10:00:00Z"));
  private static final String HOST_A = "a.worker.test";
  private static final String HOST_C = "c.worker.test";

  private WireMockServer wiremock;
  private VigilDatabase database;
  private JdbcClient postgres;
  private TestSecrets secrets;
  private VigilTestApp app;
  private CorrelationFixture a;
  private TenantId b;
  private final List<String> observed = new CopyOnWriteArrayList<>();

  /** The test clock. */
  static class TestBeans {

    @Bean
    @Primary
    Clock testClock() {
      return CLOCK;
    }
  }

  @BeforeAll
  void start() {
    wiremock = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort());
    wiremock.start();
    IntakeFixture.stub(wiremock);
    database = VigilDatabase.fresh();
    postgres = database.postgres();
    secrets = IntakeFixture.secrets(database, "", "-c");
    startApp();
  }

  private void startApp() {
    app = VigilTestApp.start(database, secrets.folder(), List.of(), TestBeans.class);
    app.awaitStarted();
  }

  @AfterAll
  void stop() {
    app.close();
    wiremock.stop();
  }

  private String address() {
    return "http://127.0.0.1:" + wiremock.port();
  }

  private CorrelationWork work() {
    return CorrelationFixture.work(app);
  }

  private CorrelationService correlation() {
    return app.context().getBean(CorrelationService.class);
  }

  private long decisions(TenantId tenant) {
    return postgres
        .sql("SELECT count(*) FROM correlation.grouping_decision WHERE tenant_id = :t")
        .param("t", tenant.value())
        .query(Long.class)
        .single();
  }

  private static void await(java.util.function.BooleanSupplier condition) {
    CorrelationFixture.await(condition, CorrelationFixture.WAIT);
  }

  @Test
  @Order(1)
  @DisplayName("AR-R-26 one virtual thread per active tenant, named after it")
  void virtualThread() {
    a = CorrelationFixture.create(app, secrets, "Tenant A", HOST_A, address(), "");
    Thread thread = work().workerThread(a.tenant).orElseThrow();
    assertThat(thread.isAlive()).isTrue();
    assertThat(thread.isVirtual()).isTrue();
    assertThat(thread.getName()).isEqualTo("correlation-" + a.tenant.value());
  }

  @Test
  @Order(2)
  @DisplayName("DM-R-70 correlation work only for active tenants")
  void activeTenantsOnly() {
    b = new TestTenants(app).tenant("Tenant B").create().id();
    CorrelationFixture.importSettings(app, b, "300", "40", "300", "1");
    app.context().getBean(CorrelationScheduling.class).matchActiveTenants();
    assertThat(work().runningTenants()).contains(a.tenant).doesNotContain(b);
    TenantService tenants = app.context().getBean(TenantService.class);
    tenants.setAlias(HOST_A, b, "test");
    await(() -> work().runningTenants().contains(b) && !work().runningTenants().contains(a.tenant));
    observed.add("b started, a stopped");
    long cursor = correlation().cursor(a.tenant);
    for (int n = 1; n <= 101; n++) {
      a.post(
          IntakeFixture.ALERTMANAGER, a.alertmanager("nowhere-z", "i-" + n, "NodeFilesystemFull"));
    }
    IntakeFixture.sleep(Duration.ofSeconds(3));
    assertThat(correlation().cursor(a.tenant)).isEqualTo(cursor);
    tenants.setAlias(HOST_A, a.tenant, "test");
    await(() -> work().runningTenants().contains(a.tenant) && !work().runningTenants().contains(b));
    observed.add("a started, b stopped");
    a.awaitDecided();
    List<AlertView> alerts = a.alerts();
    assertThat(correlation().cursor(a.tenant)).isEqualTo(alerts.getLast().sequence());
    assertThat(decisions(a.tenant)).isEqualTo(alerts.size());
    List<Long> order =
        postgres
            .sql(
                "SELECT alert_sequence FROM correlation.grouping_decision"
                    + " WHERE tenant_id = :t ORDER BY id")
            .param("t", a.tenant.value())
            .query(Long.class)
            .list();
    assertThat(order).isSorted().hasSize(alerts.size());
  }

  @Test
  @Order(3)
  @DisplayName("AR-R-19 the worker started and stopped by TenantAliasChanged")
  void startedAndStopped() {
    assertThat(observed).containsExactly("b started, a stopped", "a started, b stopped");
    assertThat(work().runningTenants()).contains(a.tenant).doesNotContain(b);
  }

  private CorrelationFixture c;
  private AlertView x1;
  private AlertView x2;
  private Instant attempt2;

  private static List<JsonNode> lines(CapturedOutput output, String text) {
    return LogLines.application(output.getAll()).stream()
        .filter(line -> line.path("message").asString("").equals(text))
        .toList();
  }

  private JsonNode awaitLine(CapturedOutput output, String text) {
    List<JsonNode> found = new ArrayList<>();
    await(
        () -> {
          found.clear();
          found.addAll(lines(output, text));
          return !found.isEmpty();
        });
    return found.getFirst();
  }

  private Health health() {
    return app.context().getBean("correlationHealthIndicator", HealthIndicator.class).health();
  }

  @Test
  @Order(4)
  @DisplayName("AR-R-17 a failed decision is retried after 1 s and 5 s, never skipped")
  void retries(CapturedOutput output) {
    c = CorrelationFixture.create(app, secrets, "Tenant C", HOST_C, address(), "-c", false);
    x1 = c.postAlertmanager("nowhere-c", "c-1", "NodeFilesystemFull");
    String prefix = "correlation decision of alert " + x1.id() + " for tenant " + c.tenant.value();
    JsonNode first = awaitLine(output, prefix + " failed (attempt 1)");
    assertThat(first.path("log").path("level").asString()).isEqualTo("ERROR");
    x2 = c.postAlertmanager("nowhere-c", "c-2", "NodeFilesystemFull");
    AlertView other = a.postAlertmanager("nowhere-a", "a-1", "NodeFilesystemFull");
    JsonNode second = awaitLine(output, prefix + " failed (attempt 2)");
    Instant firstAt = Instant.parse(first.path("@timestamp").asString());
    attempt2 = Instant.parse(second.path("@timestamp").asString());
    assertThat(attempt2).isAfterOrEqualTo(firstAt.plusMillis(900));
    Health health = health();
    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails())
        .isEqualTo(
            Map.of(
                "retries",
                List.of(
                    Map.of(
                        "tenant_id",
                        c.tenant.value().toString(),
                        "alert_id",
                        x1.id().toString(),
                        "attempt",
                        2))));
    assertThat(correlation().cursor(c.tenant)).isZero();
    await(() -> correlation().decision(a.tenant, other.id()).isPresent());
    app.context()
        .getBean(RoutingImport.class)
        .importPart(c.tenant, RoutingFixture.demoPart(), Instant.now(), Map.of());
    assertThat(Instant.now()).isBefore(attempt2.plusSeconds(4));
    await(() -> correlation().decision(c.tenant, x1.id()).isPresent());
    assertThat(Instant.now()).isAfterOrEqualTo(attempt2.plusMillis(4500));
    c.awaitDecided();
    assertThat(lines(output, prefix + " failed (attempt 3)")).isEmpty();
    assertThat(correlation().decision(c.tenant, x1.id()).orElseThrow().decidedAt())
        .isEqualTo(CLOCK.instant());
    assertThat(correlation().decision(c.tenant, x2.id()).orElseThrow().alertSequence())
        .isGreaterThan(x1.sequence());
    assertThat(correlation().cursor(c.tenant)).isEqualTo(x2.sequence());
  }

  @Test
  @Order(5)
  @DisplayName("AR-H-12 the correlation indicator is DOWN while a retry is pending, then UP")
  void healthIndicator() {
    assertThat(attempt2).isNotNull();
    assertThat(health().getStatus()).isEqualTo(Status.UP);
    assertThat(health().getDetails()).isEmpty();
  }

  @Test
  @Order(6)
  @DisplayName("AR-Q-05 AlertReceived and TenantAliasChanged delivered twice give the same result")
  void doubleDelivery() {
    TransactionTemplate tx =
        new TransactionTemplate(app.context().getBean(PlatformTransactionManager.class));
    CorrelationWakeListener wake =
        AopTestUtils.getUltimateTargetObject(app.context().getBean(CorrelationWakeListener.class));
    CorrelationAliasListener alias =
        AopTestUtils.getUltimateTargetObject(app.context().getBean(CorrelationAliasListener.class));
    AlertView decided = a.alerts().getLast();
    List<Set<TenantId>> running = new CopyOnWriteArrayList<>();
    DoubleDelivery.assertSameResult(
        postgres,
        "correlation",
        tx,
        () -> {
          wake.on(new AlertReceived(a.tenant, decided.id(), decided.sequence()));
          IntakeFixture.sleep(Duration.ofMillis(500));
          running.add(work().runningTenants());
        });
    DoubleDelivery.assertSameResult(
        postgres,
        "correlation",
        tx,
        () -> {
          alias.on(new TenantAliasChanged(HOST_A, a.tenant, b));
          running.add(work().runningTenants());
        });
    assertThat(running).hasSize(4);
    assertThat(running).allSatisfy(set -> assertThat(set).isEqualTo(running.getFirst()));
    assertThat(running.getFirst()).contains(a.tenant, c.tenant).doesNotContain(b);
  }

  @Test
  @Order(7)
  @DisplayName(
      "AR-R-24 restart step (5) starts the workers of the active tenants from their cursors")
  void restartStep5() {
    long before = decisions(a.tenant);
    app.close();
    startApp();
    await(() -> work().runningTenants().contains(a.tenant));
    a = CorrelationFixture.of(app, secrets, a.tenant, "");
    AlertView alert = a.postAlertmanager("nowhere-r", "r-1", "NodeFilesystemFull");
    a.awaitDecided();
    assertThat(correlation().decision(a.tenant, alert.id())).isPresent();
    assertThat(decisions(a.tenant)).isEqualTo(before + 1);
  }
}
