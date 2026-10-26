package vigil.runner;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import vigil.connectors.api.ConnectorKind;
import vigil.connectors.api.ConnectorService;
import vigil.connectors.api.ConnectorType;
import vigil.connectors.api.TargetConnector;
import vigil.connectors.api.TargetResult;
import vigil.runner.api.OperationRunState;
import vigil.runner.api.OperationRunView;
import vigil.runner.api.PermissionKind;
import vigil.runner.api.RunnerPart;
import vigil.runner.api.RunnerService;
import vigil.shared.TenantId;
import vigil.testsupport.TestSecrets;
import vigil.testsupport.TestTenants;
import vigil.testsupport.VigilDatabase;
import vigil.testsupport.VigilTestApp;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RunnerLabIT {

  private static final String DATA = "{\"mount\":\"/data\"}";
  private static final String PURGE =
      "{\"path\":\"/data/txlog_archive\",\"older_than_minutes\":1440,\"max_bytes\":12884901888}";

  private LabTarget lab;
  private TestSecrets secrets;
  private VigilTestApp app;
  private JdbcClient postgres;
  private RunnerService runner;
  private TenantId tenantA;
  private TenantId tenantC;
  private Map<String, UUID> handlesA;
  private int requests;

  private LabTarget.Injection injection;
  private final List<OperationRunView> dataRuns = new ArrayList<>();
  private OperationRunView usedBefore;
  private OperationRunView usedAfter;
  private OperationRunView bigOutput;
  private OperationRunView sleepDiag;
  private OperationRunView sleepAction;
  private long sleepDiagMillis;
  private long sleepActionMillis;
  private TargetConnector connector;
  private TargetResult connectorResult;

  @BeforeAll
  void start() {
    lab = LabTarget.shared();
    VigilDatabase database = VigilDatabase.fresh();
    secrets = TestSecrets.create(database, lab.secrets());
    app = VigilTestApp.start(database, secrets.folder(), List.of());
    app.awaitStarted();
    postgres = database.postgres();
    runner = app.context().getBean(RunnerService.class);
    TestTenants tenants = new TestTenants(app);
    tenantA = tenants.tenant("Runner lab A").create().id();
    tenantC = tenants.tenant("Runner lab C").create().id();
    handlesA =
        RunnerFixture.importInto(app, tenantA, lab.host(), lab.port(), lab.fingerprint(), part());
    byte[] random = new byte[32];
    new SecureRandom().nextBytes(random);
    RunnerFixture.importInto(
        app,
        tenantC,
        lab.host(),
        lab.port(),
        "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(random),
        part());
  }

  @AfterAll
  void stop() {
    lab.clear();
    app.close();
  }

  private static RunnerPart part() {
    return RunnerFixture.withOperations(
        RunnerFixture.demoPart(),
        RunnerFixture.operation("uid", "diagnostic", "id -u", "[]", "single_number", "30"),
        RunnerFixture.operation("uid_action", "action", "id -u", "[]", "single_number", "30"),
        RunnerFixture.operation(
            "quote_echo",
            "diagnostic",
            "set -o pipefail; printf '%s' {text} | wc -c",
            "[{\"name\":\"text\",\"value_type\":\"text\",\"required\":true,\"max_length\":100}]",
            "single_number",
            "30"),
        RunnerFixture.operation(
            "exit_diag", "diagnostic", "echo oops >&2; exit 3", "[]", "single_number", "30"),
        RunnerFixture.operation(
            "big_output",
            "diagnostic",
            "head -c 70000 /dev/zero | tr '\\0' a",
            "[]",
            "single_number",
            "30"),
        RunnerFixture.operation(
            "nul_output", "diagnostic", "printf 'a\\0b'", "[]", "single_number", "30"),
        RunnerFixture.operation("sleep_diag", "diagnostic", "sleep 10", "[]", "single_number", "3"),
        RunnerFixture.operation("sleep_action", "action", "sleep 10", "[]", "removed_files", "3"),
        RunnerFixture.operation(
            "big_action",
            "action",
            "head -c 70000 /dev/zero | tr '\\0' a",
            "[]",
            "removed_files",
            "30"));
  }

  private OperationRunView run(TenantId tenant, PermissionKind kind, String key, String json) {
    requests++;
    return runner.run(
        tenant,
        RunnerFixture.actor(kind),
        RunnerFixture.request("lab-" + requests + "-" + UUID.randomUUID(), kind, key, json));
  }

  private OperationRunView diag(String key, String json) {
    return run(tenantA, PermissionKind.DIAGNOSTIC, key, json);
  }

  private static JsonNode values(OperationRunView run) {
    assertThat(run.outputValuesJson()).as("values of %s: %s", run.operationKey(), run).isNotNull();
    return RunnerFixture.JSON.readTree(run.outputValuesJson());
  }

  private static List<String> names(OperationRunView run) {
    List<String> names = new ArrayList<>();
    values(run).forEach(value -> names.add(value.get("name").stringValue()));
    return names;
  }

  private static JsonNode value(OperationRunView run, String name) {
    for (JsonNode value : values(run)) {
      if (value.get("name").stringValue().equals(name)) {
        return value.get("value");
      }
    }
    throw new AssertionError("no value " + name + " in " + run.outputValuesJson());
  }

  private String where() {
    return lab.host() + ":" + lab.port();
  }

  @Test
  @Order(1)
  @DisplayName("AR-K-11 SSH with only the pinned host key")
  void pinnedHostKey() {
    assertThat(lab.fingerprint()).isEqualTo(lab.sshKeygenFingerprint());
    OperationRunView uid = diag("uid", "{}");
    assertThat(uid.state()).as(uid.toString()).isEqualTo(OperationRunState.SUCCEEDED);
    assertThat(values(uid))
        .isEqualTo(RunnerFixture.JSON.readTree("[{\"name\":\"value\",\"value\":2001}]"));
    OperationRunView wrong = run(tenantC, PermissionKind.DIAGNOSTIC, "uid", "{}");
    assertThat(wrong.state()).isEqualTo(OperationRunState.FAILED);
    assertThat(wrong.error())
        .isEqualTo(
            "host key " + lab.fingerprint() + " of " + where() + " does not match host_key_sha256");
  }

  @Test
  @Order(2)
  @DisplayName("AR-B-07 the host key fingerprint has the form of lab_db_host_key_sha256")
  void fingerprintForm() {
    assertThat(lab.fingerprint()).matches("^SHA256:[A-Za-z0-9+/]{43}$");
  }

  @Test
  @Order(3)
  @DisplayName("AR-S-13 the target secrets are read in their JSON form")
  void secretForm() {
    for (String secret : lab.secrets().values()) {
      JsonNode tree = RunnerFixture.JSON.readTree(secret);
      assertThat(tree.isObject()).isTrue();
      assertThat(tree.propertyNames()).containsExactlyInAnyOrder("username", "private_key");
    }
    String original = lab.secrets().get("lab-db-01-diag");
    secrets.replace("lab-db-01-diag", "{\"user\":\"vigil-diag\"}");
    try {
      OperationRunView bad = diag("uid", "{}");
      assertThat(bad.state()).isEqualTo(OperationRunState.FAILED);
      assertThat(bad.error())
          .isEqualTo("the credential for " + where() + " is not a username and private key");
    } finally {
      secrets.replace("lab-db-01-diag", original);
    }
    assertThat(value(diag("uid", "{}"), "value").asLong()).isEqualTo(2001);
  }

  @Test
  @Order(4)
  @DisplayName("DM-E13-R1 the action grant connects as its own user with its own key")
  void actionUser() {
    OperationRunView uid = run(tenantA, PermissionKind.ACTION, "uid_action", "{}");
    assertThat(uid.state()).as(uid.toString()).isEqualTo(OperationRunState.SUCCEEDED);
    assertThat(value(uid, "value").asLong()).isEqualTo(2002);
  }

  @Test
  @Order(5)
  @DisplayName("DD-F-04 the five operations run on the lab after an injection like SL-F-1")
  void demoOperations() {
    injection = lab.inject();
    OperationRunView usage = diag("fs_usage", DATA);
    assertThat(usage.state()).as(usage.toString()).isEqualTo(OperationRunState.SUCCEEDED);
    assertThat(names(usage))
        .containsExactly(
            "mount",
            "size_bytes",
            "used_bytes",
            "avail_bytes",
            "used_pct",
            "size_gib",
            "used_gib",
            "avail_gib");
    assertThat(value(usage, "mount").stringValue()).isEqualTo("/data");
    assertThat(value(usage, "used_pct").asLong()).isGreaterThanOrEqualTo(90);
    for (String gib : List.of("size_gib", "used_gib", "avail_gib")) {
      assertThat(value(usage, gib).stringValue()).matches("^\\d+\\.\\d$");
    }
    OperationRunView largest = diag("largest_dirs", "{\"path\":\"/data\",\"count\":4}");
    assertThat(largest.state()).as(largest.toString()).isEqualTo(OperationRunState.SUCCEEDED);
    assertThat(names(largest))
        .containsExactly("top1_path", "top1_gib", "top2_path", "top2_gib", "top3_path", "top3_gib");
    assertThat(value(largest, "top1_path").stringValue()).isEqualTo("/data/txlog_archive");
    assertThat(
            List.of(
                value(largest, "top1_path").stringValue(),
                value(largest, "top2_path").stringValue(),
                value(largest, "top3_path").stringValue()))
        .containsExactlyInAnyOrder("/data/txlog_archive", "/data/db", "/data/export");
    OperationRunView growth = diag("recent_growth", "{\"path\":\"/data\",\"minutes\":15}");
    assertThat(growth.state()).as(growth.toString()).isEqualTo(OperationRunState.SUCCEEDED);
    assertThat(names(growth)).containsExactly("files", "bytes", "gib");
    assertThat(value(growth, "files").asLong()).isGreaterThanOrEqualTo(injection.files());
    assertThat(value(growth, "bytes").asLong()).isGreaterThanOrEqualTo(injection.bytes());
    usedBefore = diag("fs_used_pct", DATA);
    assertThat(usedBefore.state()).as(usedBefore.toString()).isEqualTo(OperationRunState.SUCCEEDED);
    assertThat(value(usedBefore, "value").asLong()).isEqualTo(value(usage, "used_pct").asLong());
    OperationRunView purge = run(tenantA, PermissionKind.ACTION, "purge_old_archives", PURGE);
    assertThat(purge.state()).as(purge.toString()).isEqualTo(OperationRunState.SUCCEEDED);
    assertThat(names(purge)).containsExactly("files_removed", "bytes_removed", "gib_removed");
    assertThat(value(purge, "files_removed").asLong()).isEqualTo(injection.files());
    assertThat(value(purge, "bytes_removed").asLong()).isEqualTo(injection.bytes());
    assertThat(lab.exec("ls /data/txlog_archive | wc -l").strip()).isEqualTo("0");
    usedAfter = diag("fs_used_pct", DATA);
    assertThat(value(usedAfter, "value").asLong()).isLessThan(85);
    dataRuns.addAll(List.of(usage, largest, growth, usedBefore, purge, usedAfter));
  }

  @Test
  @Order(6)
  @DisplayName("PM-M05-03 each run is returned with target, operation, collection time and outcome")
  void runsReturned() {
    List<String> operations =
        List.of(
            "fs_usage",
            "largest_dirs",
            "recent_growth",
            "fs_used_pct",
            "purge_old_archives",
            "fs_used_pct");
    assertThat(dataRuns).hasSize(6);
    for (int i = 0; i < dataRuns.size(); i++) {
      OperationRunView run = dataRuns.get(i);
      assertThat(run.operationKey()).isEqualTo(operations.get(i));
      assertThat(run.targetKey()).isEqualTo("lab-db-01");
      assertThat(run.finishedAt()).isAfterOrEqualTo(run.startedAt());
      assertThat(run.state()).isEqualTo(OperationRunState.SUCCEEDED);
    }
  }

  @Test
  @Order(7)
  @DisplayName("PM-M05-04 state queries are answered by run before and after the action")
  void stateQueries() {
    assertThat(value(usedBefore, "value").asLong()).isGreaterThanOrEqualTo(90);
    assertThat(value(usedAfter, "value").asLong()).isLessThan(85);
  }

  @Test
  @Order(8)
  @DisplayName("AR-K-12 a quoted value arrives as written")
  void quotedValue() {
    OperationRunView echo = diag("quote_echo", "{\"text\": \"it's $(echo 5) \\\"q\\\"\"}");
    assertThat(echo.state()).as(echo.toString()).isEqualTo(OperationRunState.SUCCEEDED);
    assertThat(value(echo, "value").asLong()).isEqualTo(18);
  }

  @Test
  @Order(9)
  @DisplayName("AR-K-13 error output, exit status and unreadable output")
  void outputs() {
    OperationRunView exit = diag("exit_diag", "{}");
    assertThat(exit.state()).isEqualTo(OperationRunState.FAILED);
    assertThat(exit.exitStatus()).isEqualTo(3L);
    assertThat(exit.error()).isEqualTo("oops");
    assertThat(exit.outputText()).isEmpty();
    bigOutput = diag("big_output", "{}");
    assertThat(bigOutput.state()).isEqualTo(OperationRunState.FAILED);
    assertThat(bigOutput.exitStatus()).isEqualTo(0L);
    assertThat(bigOutput.error()).isEqualTo("the output could not be read by single_number");
    OperationRunView bigAction = run(tenantA, PermissionKind.ACTION, "big_action", "{}");
    assertThat(bigAction.state()).isEqualTo(OperationRunState.FAILED);
    assertThat(bigAction.exitStatus()).isEqualTo(0L);
    assertThat(bigAction.error()).isEqualTo("the output could not be read by removed_files");
    assertThat(bigAction.outputTruncated()).isTrue();
  }

  @Test
  @Order(10)
  @DisplayName("DM-C-11 output longer than 65,536 characters is cut; U+0000 is replaced")
  void outputStorage() {
    assertThat(bigOutput.outputText()).isEqualTo("a".repeat(65_536));
    assertThat(bigOutput.outputTruncated()).isTrue();
    OperationRunView nul = diag("nul_output", "{}");
    assertThat(nul.state()).isEqualTo(OperationRunState.FAILED);
    assertThat(nul.outputText()).isEqualTo("a�b");
  }

  @Test
  @Order(11)
  @DisplayName("DM-SM-10 operation run transitions T1 to T5")
  void transitions() {
    assertThat(diag("fs_nowhere", "{}").state()).isEqualTo(OperationRunState.REFUSED);
    assertThat(diag("uid", "{}").state()).isEqualTo(OperationRunState.SUCCEEDED);
    assertThat(diag("exit_diag", "{}").state()).isEqualTo(OperationRunState.FAILED);
    long start = System.nanoTime();
    sleepDiag = diag("sleep_diag", "{}");
    sleepDiagMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    assertThat(sleepDiag.state()).isEqualTo(OperationRunState.FAILED);
    assertThat(sleepDiag.error()).isEqualTo("no result within 3 s");
    start = System.nanoTime();
    sleepAction = run(tenantA, PermissionKind.ACTION, "sleep_action", "{}");
    sleepActionMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    assertThat(sleepAction.state()).isEqualTo(OperationRunState.UNKNOWN);
    assertThat(sleepAction.error()).isEqualTo("no result within 3 s");
  }

  @Test
  @Order(12)
  @DisplayName("AR-TM-01 the operation's timeout bounds the whole call")
  void timeouts() {
    assertThat(sleepDiagMillis).isLessThan(10_000);
    assertThat(sleepActionMillis).isLessThan(10_000);
    for (OperationRunView run : List.of(sleepDiag, sleepAction)) {
      Duration took = Duration.between(run.startedAt(), run.finishedAt());
      assertThat(took).isGreaterThanOrEqualTo(Duration.ofSeconds(3));
      assertThat(took).isLessThan(Duration.ofSeconds(6));
    }
  }

  @Test
  @Order(13)
  @DisplayName("AR-R-09 the run is recorded before the call and its result after it")
  void recordedAroundTheCall() throws Exception {
    String requestId = "lab-guard-" + UUID.randomUUID();
    CompletableFuture<OperationRunView> call =
        CompletableFuture.supplyAsync(
            () ->
                runner.run(
                    tenantA,
                    RunnerFixture.actor(PermissionKind.DIAGNOSTIC),
                    RunnerFixture.request(
                        requestId, PermissionKind.DIAGNOSTIC, "sleep_diag", "{}")));
    boolean seenRunning = false;
    while (!call.isDone()) {
      List<Map<String, Object>> rows =
          postgres
              .sql(
                  "SELECT state, finished_at FROM runner.operation_run"
                      + " WHERE tenant_id = :t AND request_id = :r")
              .param("t", tenantA.value())
              .param("r", requestId)
              .query()
              .listOfRows();
      if (!rows.isEmpty() && !call.isDone()) {
        assertThat(rows.getFirst().get("state")).isEqualTo("running");
        assertThat(rows.getFirst().get("finished_at")).isNull();
        seenRunning = true;
      }
      Thread.sleep(50);
    }
    assertThat(seenRunning).isTrue();
    assertThat(call.get(10, TimeUnit.SECONDS).state()).isEqualTo(OperationRunState.FAILED);
    assertThat(
            postgres
                .sql(
                    "SELECT state FROM runner.operation_run"
                        + " WHERE tenant_id = :t AND request_id = :r")
                .param("t", tenantA.value())
                .param("r", requestId)
                .query(String.class)
                .single())
        .isEqualTo("failed");
  }

  @Test
  @Order(14)
  @DisplayName("AR-K-03 the linux_server kind runs a command with the grant's credential")
  void linuxServerKind() {
    var beans = app.context().getBeansOfType(TargetConnector.class);
    assertThat(beans).hasSize(1);
    connector = beans.values().iterator().next();
    connectorResult =
        connector.run(
            tenantA,
            app.context()
                .getBean(ConnectorService.class)
                .instance(tenantA, handlesA.get("linux-lab-db-01"))
                .orElseThrow(),
            handlesA.get("cred-lab-db-01-diag"),
            "id -u",
            Duration.ofSeconds(30));
    assertThat(connectorResult).isEqualTo(new TargetResult.Finished(0, "2001\n", false, ""));
  }

  @Test
  @Order(15)
  @DisplayName("AR-K-00b the target connector type is registered by kind")
  void registeredByKind() {
    assertThat(connector.kind()).isEqualTo(ConnectorKind.LINUX_SERVER);
    assertThat(connector.kind().connectorType()).isEqualTo(ConnectorType.TARGET);
  }

  @Test
  @Order(16)
  @DisplayName("PM-K-03 the Linux server connector")
  void linuxServerConnector() {
    assertThat(connectorResult).isInstanceOf(TargetResult.Finished.class);
    assertThat(connector.getClass().getPackageName()).isEqualTo("vigil.runner");
  }

  @Test
  @Order(17)
  @DisplayName("PM-M04-02 vigil-runner is the registered implementation of the target type")
  void runnerImplementsTheType() {
    assertThat(connector.getClass().getName()).startsWith("vigil.runner.");
    assertThat(app.context().getBeansOfType(TargetConnector.class)).hasSize(1);
  }
}
