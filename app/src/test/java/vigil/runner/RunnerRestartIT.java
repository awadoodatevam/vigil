package vigil.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.annotation.PreDestroy;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import vigil.connectors.api.ConnectorInstanceView;
import vigil.connectors.api.ConnectorKind;
import vigil.connectors.api.TargetConnector;
import vigil.connectors.api.TargetResult;
import vigil.runner.api.OperationRunState;
import vigil.runner.api.OperationRunView;
import vigil.runner.api.PermissionKind;
import vigil.runner.api.RunnerService;
import vigil.shared.Ids;
import vigil.shared.TenantId;
import vigil.testsupport.TestSecrets;
import vigil.testsupport.TestTenants;
import vigil.testsupport.VigilDatabase;
import vigil.testsupport.VigilTestApp;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RunnerRestartIT {

  private VigilTestApp second;
  private JdbcClient postgres;
  private RunnerService runner;
  private TenantId tenantA;
  private final UUID a1 = Ids.newId();
  private final UUID a2 = Ids.newId();
  private final UUID a3 = Ids.newId();
  private final UUID d1 = Ids.newId();
  private Instant a3FinishedAt;

  @BeforeAll
  void start() {
    VigilDatabase database = VigilDatabase.fresh();
    TestSecrets secrets = TestSecrets.create(database, Map.of());
    postgres = database.postgres();
    TenantId tenantD;
    try (VigilTestApp first = VigilTestApp.start(database, secrets.folder(), List.of())) {
      first.awaitStarted();
      TestTenants tenants = new TestTenants(first);
      tenantA = tenants.tenant("Runner restart A").host("runner-restart-a.test").create().id();
      tenantD = tenants.tenant("Runner restart D").create().id();
      Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
      a3FinishedAt = now;
      insert(tenantA, a1, "restart-A1", "diagnostic", "running", null, null, now);
      insert(tenantA, a2, "restart-A2", "action", "running", null, null, now);
      insert(
          tenantA,
          a3,
          "restart-A3",
          "diagnostic",
          "succeeded",
          now,
          "[{\"name\":\"value\",\"value\":95}]",
          now);
      insert(tenantD, d1, "restart-D1", "diagnostic", "running", null, null, now);
    }
    second = VigilTestApp.start(database, secrets.folder(), List.of());
    second.awaitStarted();
    runner = second.context().getBean(RunnerService.class);
  }

  @AfterAll
  void stop() {
    second.close();
  }

  private void insert(
      TenantId tenant,
      UUID id,
      String requestId,
      String kind,
      String state,
      Instant finishedAt,
      String outputValues,
      Instant now) {
    postgres
        .sql(
            "INSERT INTO runner.operation_run (id, tenant_id, created_at, updated_at,"
                + " operation_key, target_key, permission_kind, parameters, requested_by,"
                + " request_id, state, started_at, finished_at, output_values, output_truncated)"
                + " VALUES (:id, :t, :now, :now, 'fs_used_pct', 'lab-db-01', :kind,"
                + " '{\"mount\":\"/data\"}'::jsonb, 'vigil-diagnostics', :request, :state, :now,"
                + " :finished, CAST(:values AS jsonb), false)")
        .param("id", id)
        .param("t", tenant.value())
        .param("now", Timestamp.from(now))
        .param("kind", kind)
        .param("request", requestId)
        .param("state", state)
        .param("finished", finishedAt == null ? null : Timestamp.from(finishedAt))
        .param("values", outputValues)
        .update();
  }

  private Map<String, Object> row(UUID id) {
    return postgres
        .sql("SELECT state, error, finished_at FROM runner.operation_run WHERE id = :id")
        .param("id", id)
        .query()
        .singleRow();
  }

  @Test
  @Order(1)
  @DisplayName("AR-R-24 restart step 1 ends the runs still running")
  void interruptedRuns() {
    assertThat(row(a1).get("state")).isEqualTo("failed");
    assertThat(row(a2).get("state")).isEqualTo("unknown");
    for (UUID id : List.of(a1, a2)) {
      assertThat(row(id).get("error")).isEqualTo("interrupted by restart");
      assertThat(row(id).get("finished_at")).isNotNull();
    }
    Map<String, Object> succeeded = row(a3);
    assertThat(succeeded.get("state")).isEqualTo("succeeded");
    assertThat(succeeded.get("error")).isNull();
    assertThat(((Timestamp) succeeded.get("finished_at")).toInstant()).isEqualTo(a3FinishedAt);
  }

  @Test
  @Order(2)
  @DisplayName("DM-R-70 the runs of a tenant that is not active stay as they are")
  void inactiveTenant() {
    assertThat(row(d1).get("state")).isEqualTo("running");
    assertThat(row(d1).get("finished_at")).isNull();
  }

  @Test
  @Order(3)
  @DisplayName("SL-B-11 the request id of an interrupted run returns that run")
  void requestIdAfterRestart() {
    long before =
        postgres.sql("SELECT count(*) FROM runner.operation_run").query(Long.class).single();
    OperationRunView run =
        runner.run(
            tenantA,
            RunnerFixture.actor(PermissionKind.DIAGNOSTIC),
            RunnerFixture.request(
                "restart-A1", PermissionKind.DIAGNOSTIC, "fs_used_pct", "{\"mount\":\"/data\"}"));
    assertThat(run.id()).isEqualTo(a1);
    assertThat(run.state()).isEqualTo(OperationRunState.FAILED);
    assertThat(run.error()).isEqualTo("interrupted by restart");
    assertThat(postgres.sql("SELECT count(*) FROM runner.operation_run").query(Long.class).single())
        .isEqualTo(before);
  }

  /** Replaces the {@code linux_server} connector bean under its own name. */
  static class Replacing {

    @Bean("linuxServerConnector")
    ReplacingConnector linuxServerConnector() {
      return new ReplacingConnector();
    }
  }

  /**
   * A {@code linux_server} connector that throws in the modes {@code throw} and {@code empty} and
   * otherwise blocks until its own {@code @PreDestroy}, which runs after {@code Runner}'s.
   */
  static final class ReplacingConnector implements TargetConnector {

    volatile String mode = "block";
    final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch released = new CountDownLatch(1);

    @Override
    public ConnectorKind kind() {
      return ConnectorKind.LINUX_SERVER;
    }

    @Override
    public TargetResult run(
        TenantId tenantId,
        ConnectorInstanceView instance,
        UUID credentialRefId,
        String command,
        Duration timeout) {
      switch (mode) {
        case "throw" -> throw new IllegalStateException("the connector broke");
        case "empty" -> throw new IllegalArgumentException("");
        default -> {
          entered.countDown();
          try {
            released.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          return new TargetResult.Finished(0, "/data 95", false, "");
        }
      }
    }

    @PreDestroy
    void release() {
      released.countDown();
    }
  }

  private static VigilTestApp replacingApp(VigilDatabase database, TestSecrets secrets) {
    VigilTestApp app =
        VigilTestApp.start(
            database,
            secrets.folder(),
            List.of("--spring.main.allow-bean-definition-overriding=true"),
            Replacing.class);
    app.awaitStarted();
    return app;
  }

  private static TenantId runnerTenant(VigilTestApp app, String name, String host) {
    TenantId tenant = new TestTenants(app).tenant(name).host(host).create().id();
    RunnerFixture.importInto(app, tenant, "lab-db-01", 22, "SHA256:x", RunnerFixture.demoPart());
    return tenant;
  }

  private static OperationRunView run(VigilTestApp app, TenantId tenant, String requestId) {
    return app.context()
        .getBean(RunnerService.class)
        .run(
            tenant,
            RunnerFixture.actor(PermissionKind.DIAGNOSTIC),
            RunnerFixture.request(
                requestId, PermissionKind.DIAGNOSTIC, "fs_used_pct", "{\"mount\":\"/data\"}"));
  }

  @Test
  @Order(4)
  @DisplayName("DM-SM-10 a connector that throws ends the run failed with its message")
  void connectorThrows() {
    VigilDatabase database = VigilDatabase.fresh();
    TestSecrets secrets = TestSecrets.create(database, Map.of());
    try (VigilTestApp app = replacingApp(database, secrets)) {
      ReplacingConnector connector =
          app.context().getBean("linuxServerConnector", ReplacingConnector.class);
      TenantId tenant = runnerTenant(app, "Runner throws", "runner-throws.test");
      connector.mode = "throw";
      OperationRunView broke = run(app, tenant, "throws-1");
      assertThat(broke.state()).isEqualTo(OperationRunState.FAILED);
      assertThat(broke.error()).isEqualTo("the connector broke");
      assertThat(broke.finishedAt()).isNotNull();
      connector.mode = "empty";
      OperationRunView empty = run(app, tenant, "throws-2");
      assertThat(empty.state()).isEqualTo(OperationRunState.FAILED);
      assertThat(empty.error()).isEqualTo("IllegalArgumentException");
    }
  }

  @Test
  @Order(5)
  @DisplayName("DM-SM-10 a stop during a call leaves the run running; the next start ends it")
  void stopDuringCall() throws Exception {
    VigilDatabase database = VigilDatabase.fresh();
    TestSecrets secrets = TestSecrets.create(database, Map.of());
    VigilTestApp app = replacingApp(database, secrets);
    ReplacingConnector connector =
        app.context().getBean("linuxServerConnector", ReplacingConnector.class);
    TenantId tenant = runnerTenant(app, "Runner stop", "runner-stop.test");
    CompletableFuture<OperationRunView> call = new CompletableFuture<>();
    Thread.ofVirtual()
        .start(
            () -> {
              try {
                call.complete(run(app, tenant, "stop-1"));
              } catch (Throwable failure) {
                call.completeExceptionally(failure);
              }
            });
    assertThat(connector.entered.await(60, TimeUnit.SECONDS)).isTrue();
    app.close();
    assertThatThrownBy(() -> call.get(60, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class)
        .cause()
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("the application is closing");
    JdbcClient jdbc = database.postgres();
    String query =
        "SELECT state, error, finished_at FROM runner.operation_run"
            + " WHERE tenant_id = :t AND request_id = 'stop-1'";
    Map<String, Object> running = jdbc.sql(query).param("t", tenant.value()).query().singleRow();
    assertThat(running.get("state")).isEqualTo("running");
    assertThat(running.get("finished_at")).isNull();
    try (VigilTestApp next = VigilTestApp.start(database, secrets.folder(), List.of())) {
      next.awaitStarted();
      Map<String, Object> ended = jdbc.sql(query).param("t", tenant.value()).query().singleRow();
      assertThat(ended.get("state")).isEqualTo("failed");
      assertThat(ended.get("error")).isEqualTo("interrupted by restart");
      assertThat(ended.get("finished_at")).isNotNull();
    }
  }
}
