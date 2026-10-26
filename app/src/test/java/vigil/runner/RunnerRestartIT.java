package vigil.runner;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.jdbc.core.simple.JdbcClient;
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
}
