package vigil.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import vigil.runner.api.RunRequest;
import vigil.runner.api.RunnerService;
import vigil.shared.Actor;
import vigil.shared.TenantId;
import vigil.tenancy.api.TenantService;
import vigil.testsupport.TestSecrets;
import vigil.testsupport.VigilDatabase;
import vigil.testsupport.VigilTestApp;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RunnerRequestIT {

  private static final Actor DIAGNOSTICS = Actor.module("vigil-diagnostics");
  private static final Actor ACTIONS = Actor.module("vigil-actions");
  private static final String PURGE =
      "{\"path\": \"/data/txlog_archive\", \"older_than_minutes\": 1440,"
          + " \"max_bytes\": 12884901888}";

  private VigilTestApp app;
  private TestSecrets secrets;
  private JdbcClient postgres;
  private RunnerService runner;
  private TenantId tenantA;
  private TenantId tenantB;
  private int requests;
  private OperationRunView nowhere;
  private OperationRunView etc;
  private String etcRequest;

  @BeforeAll
  void start() {
    VigilDatabase database = VigilDatabase.fresh();
    secrets =
        TestSecrets.create(
            database, Map.of("lab-db-01-diag", RunnerFixture.keySecret("vigil-diag")));
    app = VigilTestApp.start(database, secrets.folder(), List.of());
    app.awaitStarted();
    postgres = database.postgres();
    runner = app.context().getBean(RunnerService.class);
    TenantService tenants = app.context().getBean(TenantService.class);
    tenantA = tenants.createTenant("Runner requests A", Actor.module("vigil-setup"));
    tenantB = tenants.createTenant("Runner requests B", Actor.module("vigil-setup"));
    RunnerFixture.importInto(
        app, tenantA, "nowhere.invalid", 22, "SHA256:x", RunnerFixture.demoPart());
    RunnerFixture.importInto(
        app,
        tenantB,
        "nowhere.invalid",
        22,
        "SHA256:x",
        RunnerFixture.withGrants(RunnerFixture.demoPart(), 0));
  }

  @AfterAll
  void stop() {
    app.close();
  }

  private String newId() {
    requests++;
    return "request-" + requests + "-" + UUID.randomUUID();
  }

  private OperationRunView run(
      TenantId tenant, Actor actor, PermissionKind kind, String key, String json) {
    return runner.run(tenant, actor, RunnerFixture.request(newId(), kind, key, json));
  }

  private OperationRunView diag(String key, String json) {
    return run(tenantA, DIAGNOSTICS, PermissionKind.DIAGNOSTIC, key, json);
  }

  private long runsOf(TenantId tenant) {
    return postgres
        .sql("SELECT count(*) FROM runner.operation_run WHERE tenant_id = :t")
        .param("t", tenant.value())
        .query(Long.class)
        .single();
  }

  private static void refused(OperationRunView run, String error) {
    assertThat(run.state()).isEqualTo(OperationRunState.REFUSED);
    assertThat(run.error()).isEqualTo(error);
  }

  @Test
  @Order(1)
  @DisplayName("PM-M05-01 only active operations of the catalogue run")
  void onlyActiveOperations() {
    nowhere = diag("fs_nowhere", "{}");
    refused(nowhere, "operation fs_nowhere is not an active operation");
  }

  @Test
  @Order(2)
  @DisplayName("DM-E12-R1 a request names an operation, never a command")
  void noCommand() {
    assertThat(nowhere.exitStatus()).isNull();
    assertThat(nowhere.outputText()).isNull();
    assertThat(nowhere.outputTruncated()).isFalse();
    assertThat(RunRequest.class.getRecordComponents())
        .extracting(component -> component.getName())
        .containsExactly(
            "requestId", "permissionKind", "operationKey", "targetKey", "parametersJson");
  }

  @Test
  @Order(3)
  @DisplayName("DM-V-06 parameters are checked against their definitions before anything runs")
  void parameterRefusals() {
    etcRequest = newId();
    etc =
        runner.run(
            tenantA,
            DIAGNOSTICS,
            RunnerFixture.request(
                etcRequest, PermissionKind.DIAGNOSTIC, "fs_usage", "{\"mount\": \"/etc\"}"));
    refused(etc, "parameter mount: not under /data");
    refused(
        diag("largest_dirs", "{\"x\": 1}"),
        "parameter x: not a parameter of largest_dirs; parameter path: missing;"
            + " parameter count: missing");
    OperationRunView list = diag("fs_usage", "[1]");
    refused(list, "parameters: not an object");
    assertThat(RunnerFixture.JSON.readTree(list.parametersJson()))
        .isEqualTo(RunnerFixture.JSON.readTree("{}"));
  }

  @Test
  @Order(4)
  @DisplayName("DM-E-14 the operation run records its request")
  void operationRun() {
    OperationRunView view = runner.operationRun(tenantA, etc.id()).orElseThrow();
    assertThat(view.operationKey()).isEqualTo("fs_usage");
    assertThat(view.targetKey()).isEqualTo("lab-db-01");
    assertThat(view.permissionKind()).isEqualTo(PermissionKind.DIAGNOSTIC);
    assertThat(RunnerFixture.JSON.readTree(view.parametersJson()))
        .isEqualTo(RunnerFixture.JSON.readTree("{\"mount\":\"/etc\"}"));
    assertThat(view.requestedBy()).isEqualTo("vigil-diagnostics");
    assertThat(view.requestId()).isEqualTo(etcRequest);
    assertThat(view.startedAt()).isEqualTo(view.finishedAt());
    assertThat(view.exitStatus()).isNull();
    assertThat(view.outputValuesJson()).isNull();
    assertThat(view.outputText()).isNull();
    OperationRunView nul = diag("fs_usage", "{\"mount\": \"/data/\\u0000\"}");
    refused(nul, "parameter mount: holds the character U+0000");
    assertThat(RunnerFixture.JSON.readTree(nul.parametersJson()))
        .isEqualTo(RunnerFixture.JSON.readTree("{}"));
    long before = runsOf(tenantA);
    assertThatThrownBy(
            () ->
                runner.run(
                    tenantA,
                    DIAGNOSTICS,
                    RunnerFixture.request(
                        "r-\u0000",
                        PermissionKind.DIAGNOSTIC,
                        "fs_usage",
                        "{\"mount\":\"/data\"}")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("requestId: 1 to 150 characters without U+0000");
    assertThatThrownBy(() -> diag("fs_usage", "{\"a\\u0000\": 1}"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("parameters: a name holds U+0000");
    assertThat(runsOf(tenantA)).isEqualTo(before);
  }

  @Test
  @Order(5)
  @DisplayName("DM-E14-R1 a request id that exists returns the earlier run")
  void requestIds() {
    OperationRunView first =
        runner.run(
            tenantA,
            DIAGNOSTICS,
            RunnerFixture.request(
                "r-1", PermissionKind.DIAGNOSTIC, "fs_usage", "{\"mount\": \"/etc\"}"));
    refused(first, "parameter mount: not under /data");
    OperationRunView again =
        runner.run(
            tenantA,
            DIAGNOSTICS,
            RunnerFixture.request(
                "r-1", PermissionKind.DIAGNOSTIC, "fs_usage", "{\"mount\": \"/data\"}"));
    assertThat(again.id()).isEqualTo(first.id());
    assertThat(again.error()).isEqualTo(first.error());
    assertThat(
            postgres
                .sql("SELECT count(*) FROM runner.operation_run WHERE request_id = 'r-1'")
                .query(Long.class)
                .single())
        .isEqualTo(1);
  }

  @Test
  @Order(6)
  @DisplayName("SL-B-05 a request runs only operations of its own permission kind")
  void permissionKinds() {
    long before = runsOf(tenantA);
    assertThatThrownBy(
            () -> run(tenantA, DIAGNOSTICS, PermissionKind.DIAGNOSTIC, "purge_old_archives", PURGE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("operation purge_old_archives is not a diagnostic operation");
    assertThatThrownBy(
            () ->
                run(tenantA, ACTIONS, PermissionKind.ACTION, "fs_usage", "{\"mount\": \"/data\"}"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("operation fs_usage is not an action operation");
    assertThatThrownBy(
            () ->
                run(
                    tenantA,
                    DIAGNOSTICS,
                    PermissionKind.ACTION,
                    "fs_usage",
                    "{\"mount\": \"/data\"}"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("vigil-diagnostics runs diagnostic operations only");
    assertThat(runsOf(tenantA)).isEqualTo(before);
  }

  @Test
  @Order(7)
  @DisplayName("PM-M05-02 an action is refused on a target with only a diagnostic grant")
  void actionGrant() {
    refused(
        run(tenantB, ACTIONS, PermissionKind.ACTION, "purge_old_archives", PURGE),
        "target lab-db-01 has no active action grant");
  }

  @Test
  @Order(8)
  @DisplayName("AR-S-04 the runner reads the grant's secret at each run")
  void secretReadAtEachRun() {
    OperationRunView missing =
        run(tenantA, ACTIONS, PermissionKind.ACTION, "purge_old_archives", PURGE);
    assertThat(missing.state()).isEqualTo(OperationRunState.FAILED);
    assertThat(missing.error()).isEqualTo("Secret lab-db-01-action is not available.");
    secrets.replace("lab-db-01-action", RunnerFixture.keySecret("vigil-action"));
    OperationRunView noHost =
        run(tenantA, ACTIONS, PermissionKind.ACTION, "purge_old_archives", PURGE);
    assertThat(noHost.state()).isEqualTo(OperationRunState.FAILED);
    assertThat(noHost.error()).isEqualTo("could not connect to nowhere.invalid:22");
  }
}
