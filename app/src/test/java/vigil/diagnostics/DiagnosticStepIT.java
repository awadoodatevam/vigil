package vigil.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import vigil.diagnostics.api.DiagnosticDefinitionView;
import vigil.diagnostics.api.DiagnosticOutcome;
import vigil.diagnostics.api.DiagnosticResultView;
import vigil.diagnostics.api.DiagnosticsService;
import vigil.diagnostics.api.DiagnosticsStepHandler;
import vigil.engine.EngineFixture;
import vigil.engine.TestContents;
import vigil.engine.api.EngineService;
import vigil.engine.api.RunState;
import vigil.engine.api.StepExecutionState;
import vigil.engine.api.StepExecutionView;
import vigil.incidents.api.DataCategory;
import vigil.incidents.api.EvidenceKind;
import vigil.incidents.api.EvidenceView;
import vigil.incidents.api.IncidentService;
import vigil.runner.LabTarget;
import vigil.runner.RunnerFixture;
import vigil.runner.api.OperationRunState;
import vigil.runner.api.OperationRunView;
import vigil.runner.api.PermissionKind;
import vigil.shared.Actor;
import vigil.shared.Completed;
import vigil.shared.Ids;
import vigil.shared.Key;
import vigil.shared.StepAnswer;
import vigil.shared.StepCall;
import vigil.shared.StepCancel;
import vigil.testsupport.TestSecrets;
import vigil.testsupport.VigilDatabase;
import vigil.testsupport.VigilTestApp;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DiagnosticStepIT {

  private static final List<String> KEYS = List.of("disk_usage", "largest_folders", "disk_growth");
  private static final Map<String, String> OPERATIONS =
      Map.of(
          "disk_usage",
          "fs_usage",
          "largest_folders",
          "largest_dirs",
          "disk_growth",
          "recent_growth");

  private LabTarget lab;
  private VigilTestApp app;
  private JdbcClient postgres;
  private DiagnosticsStepHandler handler;
  private IncidentService incidents;
  private DiagnosticsFixture a;
  private DiagnosticsFixture b;
  private DiagnosticsFixture c;
  private final List<StepAnswer> answers = new ArrayList<>();

  private LabTarget.Injection injection;
  private final UUID r1 = Ids.newId();
  private final UUID s1 = Ids.newId();
  private UUID i1;

  @BeforeAll
  void start() {
    lab = LabTarget.shared();
    VigilDatabase database = VigilDatabase.fresh();
    TestSecrets secrets = TestSecrets.create(database, lab.secrets());
    app = VigilTestApp.start(database, secrets.folder(), List.of());
    app.awaitStarted();
    postgres = database.postgres();
    handler = app.context().getBean(DiagnosticsStepHandler.class);
    incidents = app.context().getBean(IncidentService.class);
    a =
        DiagnosticsFixture.create(
            app,
            "Diagnostics A",
            "diagnostics-a.test",
            lab.host(),
            lab.port(),
            lab.fingerprint(),
            RunnerFixture.demoPart());
    b =
        DiagnosticsFixture.create(
            app,
            "Diagnostics B",
            "diagnostics-b.test",
            lab.host(),
            lab.port(),
            lab.fingerprint(),
            RunnerFixture.withGrants(RunnerFixture.demoPart(), 1));
    byte[] random = new byte[32];
    new SecureRandom().nextBytes(random);
    c =
        DiagnosticsFixture.create(
            app,
            "Diagnostics C",
            "diagnostics-c.test",
            lab.host(),
            lab.port(),
            "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(random),
            RunnerFixture.demoPart());
  }

  @AfterAll
  void stop() {
    lab.clear();
    app.close();
  }

  private StepAnswer start(StepCall call) {
    StepAnswer answer = handler.start(call);
    answers.add(answer);
    return answer;
  }

  private static String output(StepAnswer answer) {
    assertThat(answer).isInstanceOf(Completed.class);
    assertThat(((Completed) answer).resultRef()).isNull();
    return ((Completed) answer).namedOutput().value();
  }

  private static JsonNode tree(String json) {
    return RunnerFixture.JSON.readTree(json);
  }

  private List<EvidenceView> evidence(DiagnosticsFixture fixture, UUID incident) {
    return incidents.evidence(fixture.tenant, incident);
  }

  private OperationRunView runOf(DiagnosticsFixture fixture, UUID stepExecution, String suffix) {
    return fixture.runs(stepExecution).stream()
        .filter(run -> run.requestId().equals(stepExecution + ":" + suffix))
        .findFirst()
        .orElseThrow();
  }

  private static JsonNode value(DiagnosticResultView result, String name) {
    for (JsonNode value : tree(result.valuesJson())) {
      if (value.get("name").stringValue().equals(name)) {
        return value.get("value");
      }
    }
    throw new AssertionError("no value " + name);
  }

  private Map<String, Long> counts() {
    Map<String, Long> counts = new LinkedHashMap<>();
    for (String table :
        List.of(
            "diagnostics.diagnostic_definition",
            "diagnostics.diagnostic_result",
            "runner.operation",
            "runner.target_access_grant",
            "runner.operation_run")) {
      counts.put(table, postgres.sql("SELECT count(*) FROM " + table).query(Long.class).single());
    }
    return counts;
  }

  @Test
  @Order(1)
  @DisplayName("SL-A1-03 read-only diagnostics on the lab database server become evidence")
  void diagnosticsAsEvidence() {
    injection = lab.inject();
    i1 = a.openIncident("srv-db-01");
    StepAnswer answer = start(a.call(r1, i1, s1, 1, "start", KEYS.toArray(String[]::new)));
    assertThat(output(answer)).isEqualTo("done");
    List<EvidenceView> entries = evidence(a, i1);
    assertThat(entries).hasSize(3);
    assertThat(entries)
        .allSatisfy(e -> assertThat(e.kind()).isEqualTo(EvidenceKind.DIAGNOSTIC_RESULT));
    assertThat(entries)
        .extracting(EvidenceView::summary)
        .satisfiesExactlyInAnyOrder(
            s ->
                assertThat(s)
                    .matches(
                        "^/data is \\d+% full \\(\\d+\\.\\d of \\d+\\.\\d GiB used, \\d+\\.\\d GiB"
                            + " free\\)$"),
            s ->
                assertThat(s)
                    .matches(
                        "^Largest folder under /data: /data/txlog_archive \\(\\d+\\.\\d GiB\\)$"),
            s ->
                assertThat(s)
                    .matches(
                        "^\\d+\\.\\d GiB written to \\d+ files under /data in the last 15"
                            + " minutes$"));
  }

  @Test
  @Order(2)
  @DisplayName("PM-M13-02 each definition runs with diagnostic permission and its result is stored")
  void resultsStored() {
    List<DiagnosticResultView> results = a.results(i1, s1);
    assertThat(results)
        .extracting(DiagnosticResultView::diagnosticKey)
        .containsExactlyElementsOf(KEYS);
    DiagnosticsService diagnostics = app.context().getBean(DiagnosticsService.class);
    for (DiagnosticResultView result : results) {
      assertThat(result.outcome()).isEqualTo(DiagnosticOutcome.SUCCEEDED);
      assertThat(result.componentKey()).isEqualTo("srv-db-01");
      assertThat(result.targetKey()).isEqualTo("lab-db-01");
      assertThat(result.runId()).isEqualTo(r1);
      assertThat(result.incidentId()).isEqualTo(i1);
      OperationRunView run = runOf(a, s1, result.diagnosticKey() + ":1");
      assertThat(result.operationRunId()).isEqualTo(run.id());
      assertThat(tree(result.valuesJson())).isEqualTo(tree(run.outputValuesJson()));
      assertThat(result.collectedAt()).isEqualTo(run.finishedAt());
      DiagnosticDefinitionView definition =
          diagnostics.definition(a.tenant, result.diagnosticKey()).orElseThrow();
      assertThat(run.permissionKind()).isEqualTo(PermissionKind.DIAGNOSTIC);
      assertThat(run.requestedBy()).isEqualTo("vigil-diagnostics");
      assertThat(run.operationKey()).isEqualTo(definition.operationKey());
      assertThat(tree(run.parametersJson())).isEqualTo(tree(definition.parameterValuesJson()));
    }
  }

  @Test
  @Order(3)
  @DisplayName("DM-E-54 diagnostic results hold their values")
  void resultRows() {
    List<Map<String, Object>> rows =
        postgres
            .sql(
                "SELECT error, values::text AS values FROM diagnostics.diagnostic_result"
                    + " WHERE step_execution_id = :s")
            .param("s", s1)
            .query()
            .listOfRows();
    assertThat(rows).hasSize(3);
    assertThat(rows)
        .allSatisfy(
            row -> {
              assertThat(row.get("error")).isNull();
              assertThat(row.get("values")).isNotNull();
            });
    List<DiagnosticResultView> results = a.results(i1, s1);
    assertThat(value(results.get(0), "used_pct").asLong()).isGreaterThanOrEqualTo(90);
    assertThat(value(results.get(1), "top1_path").stringValue()).isEqualTo("/data/txlog_archive");
    assertThat(value(results.get(2), "files").asLong()).isGreaterThanOrEqualTo(injection.files());
  }

  @Test
  @Order(4)
  @DisplayName("DC-TX-03 the diagnostic_result evidence summary and source")
  void evidenceEntries() {
    DiagnosticsService diagnostics = app.context().getBean(DiagnosticsService.class);
    List<EvidenceView> entries = evidence(a, i1);
    for (DiagnosticResultView result : a.results(i1, s1)) {
      EvidenceView entry =
          entries.stream()
              .filter(e -> e.producerRecordId().equals(result.id()))
              .findFirst()
              .orElseThrow();
      DiagnosticDefinitionView definition =
          diagnostics.definition(a.tenant, result.diagnosticKey()).orElseThrow();
      assertThat(entry.producerModule()).isEqualTo("vigil-diagnostics");
      assertThat(entry.sourceSystem())
          .isEqualTo("lab-db-01 via runner, operation " + OPERATIONS.get(result.diagnosticKey()));
      assertThat(entry.collectedAt()).isEqualTo(result.collectedAt());
      assertThat(entry.dataCategory()).isEqualTo(DataCategory.DIAGNOSTIC_OUTPUT);
      String summary = definition.summaryTemplate();
      for (JsonNode value : tree(result.valuesJson())) {
        JsonNode v = value.get("value");
        summary =
            summary.replace(
                "{" + value.get("name").stringValue() + "}",
                v.isString() ? v.stringValue() : v.toString());
      }
      assertThat(entry.summary()).isEqualTo(summary);
      assertThat(tree(entry.contentJson()))
          .isEqualTo(
              tree(
                  "{\"diagnostic_key\":\""
                      + result.diagnosticKey()
                      + "\",\"diagnostic_name\":\""
                      + definition.name()
                      + "\",\"component_key\":\"srv-db-01\",\"target_key\":\"lab-db-01\","
                      + "\"operation_key\":\""
                      + definition.operationKey()
                      + "\",\"operation_run_id\":\""
                      + result.operationRunId()
                      + "\",\"values\":"
                      + result.valuesJson()
                      + "}"));
    }
  }

  @Test
  @Order(5)
  @DisplayName("DM-R-64 an evaluation again continues with the stored results")
  void evaluatedAgain() {
    assertThat(output(start(a.call(r1, i1, s1, 1, "start", KEYS.toArray(String[]::new)))))
        .isEqualTo("done");
    assertThat(a.results(i1, s1)).hasSize(3);
    assertThat(a.runs(s1)).hasSize(3);
    assertThat(evidence(a, i1)).hasSize(3);
  }

  @Test
  @Order(6)
  @DisplayName("DM-S-03 restart, no hypothesis and no target")
  void diagnosticStep() {
    UUID s2 = Ids.newId();
    assertThat(output(start(a.call(r1, i1, s2, 1, "start", "disk_usage")))).isEqualTo("done");
    Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    postgres
        .sql(
            "INSERT INTO runner.operation_run (id, tenant_id, created_at, updated_at,"
                + " operation_key, target_key, permission_kind, parameters, requested_by,"
                + " request_id, state, started_at, finished_at, output_truncated, error) VALUES"
                + " (:id, :t, :now, :now, 'recent_growth', 'lab-db-01', 'diagnostic',"
                + " '{\"path\":\"/data\",\"minutes\":15}'::jsonb, 'vigil-diagnostics', :r,"
                + " 'failed', :now, :now, false, 'interrupted by restart')")
        .param("id", Ids.newId())
        .param("t", a.tenant.value())
        .param("now", Timestamp.from(now))
        .param("r", s2 + ":disk_growth:1")
        .update();
    assertThat(output(start(a.call(r1, i1, s2, 2, "resume", KEYS.toArray(String[]::new)))))
        .isEqualTo("done");
    assertThat(a.runs(s2))
        .extracting(OperationRunView::requestId)
        .containsExactly(
            s2 + ":disk_growth:1",
            s2 + ":disk_growth:2",
            s2 + ":disk_usage:1",
            s2 + ":largest_folders:2");
    List<DiagnosticResultView> results = a.results(i1, s2);
    assertThat(results).hasSize(3);
    DiagnosticResultView growth =
        results.stream()
            .filter(r -> r.diagnosticKey().equals("disk_growth"))
            .findFirst()
            .orElseThrow();
    assertThat(growth.operationRunId()).isEqualTo(runOf(a, s2, "disk_growth:2").id());

    UUID none = a.openIncident(null);
    UUID s3 = Ids.newId();
    assertThat(output(start(a.call(r1, none, s3, 1, "start", KEYS.toArray(String[]::new)))))
        .isEqualTo("failed");
    assertThat(a.results(none, s3)).isEmpty();
    assertThat(a.runs(s3)).isEmpty();

    UUID noTarget = a.openIncident("srv-api-01");
    UUID s4 = Ids.newId();
    assertThat(output(start(a.call(r1, noTarget, s4, 1, "start", KEYS.toArray(String[]::new)))))
        .isEqualTo("failed");
    List<DiagnosticResultView> failed = a.results(noTarget, s4);
    assertThat(failed).hasSize(3);
    assertThat(failed)
        .allSatisfy(
            result -> {
              assertThat(result.outcome()).isEqualTo(DiagnosticOutcome.FAILED);
              assertThat(result.targetKey()).isNull();
              assertThat(result.operationRunId()).isNull();
              assertThat(result.error()).isEqualTo("component srv-api-01 has no target");
            });
    assertThat(a.runs(s4)).isEmpty();
  }

  @Test
  @Order(7)
  @DisplayName("PM-M13-03 failed with the reason when the runner refuses or fails")
  void failedWithReason() {
    UUID incidentB = b.openIncident("srv-db-01");
    UUID sB = Ids.newId();
    assertThat(output(start(b.call(r1, incidentB, sB, 1, "start", KEYS.toArray(String[]::new)))))
        .isEqualTo("failed");
    List<DiagnosticResultView> refused = b.results(incidentB, sB);
    assertThat(refused)
        .extracting(DiagnosticResultView::diagnosticKey)
        .containsExactlyElementsOf(KEYS);
    for (DiagnosticResultView result : refused) {
      assertThat(result.outcome()).isEqualTo(DiagnosticOutcome.FAILED);
      assertThat(result.error()).isEqualTo("target lab-db-01 has no active diagnostic grant");
      assertThat(b.runs(sB).stream().filter(run -> run.id().equals(result.operationRunId())))
          .singleElement()
          .satisfies(run -> assertThat(run.state()).isEqualTo(OperationRunState.REFUSED));
    }
    assertThat(evidence(b, incidentB)).isEmpty();
    UUID incidentC = c.openIncident("srv-db-01");
    UUID sC = Ids.newId();
    assertThat(output(start(c.call(r1, incidentC, sC, 1, "start", KEYS.toArray(String[]::new)))))
        .isEqualTo("failed");
    List<DiagnosticResultView> wrongKey = c.results(incidentC, sC);
    assertThat(wrongKey).hasSize(3);
    assertThat(wrongKey)
        .allSatisfy(
            result ->
                assertThat(result.error())
                    .isEqualTo(
                        "host key "
                            + lab.fingerprint()
                            + " of "
                            + lab.host()
                            + ":"
                            + lab.port()
                            + " does not match host_key_sha256"));
  }

  @Test
  @Order(8)
  @DisplayName("PM-S-03 the Diagnostic step leaves by done or failed")
  void outputs() {
    assertThat(answers).isNotEmpty();
    assertThat(answers).allSatisfy(answer -> assertThat(output(answer)).isIn("done", "failed"));
  }

  @Test
  @Order(9)
  @DisplayName("AR-R-21 the Diagnostic step handler: start, evaluate, cancel")
  void handler() {
    EngineFixture.await(() -> EngineFixture.isScheduled(app, a.tenant));
    UUID i3 = a.openIncident("db-subscriber");
    EngineService engine = app.context().getBean(EngineService.class);
    UUID run =
        engine
            .startLiveRun(
                a.tenant,
                Actor.module("vigil-workflows"),
                i3,
                1,
                Ids.newId(),
                TestContents.linear("diagnostic"),
                false)
            .orElseThrow();
    EngineFixture.await(() -> engine.run(a.tenant, run).orElseThrow().state() == RunState.FINISHED);
    StepExecutionView step =
        engine.stepExecutions(a.tenant, run).stream()
            .filter(execution -> execution.stepId().equals("s-1"))
            .findFirst()
            .orElseThrow();
    assertThat(step.state()).isEqualTo(StepExecutionState.COMPLETED);
    assertThat(step.namedOutput()).isEqualTo("done");
    List<DiagnosticResultView> results = a.results(i3, step.id());
    assertThat(results).hasSize(1);
    assertThat(results.getFirst().diagnosticKey()).isEqualTo("disk_usage");
    assertThat(results.getFirst().targetKey()).isEqualTo("lab-db-01");
    assertThat(results.getFirst().operationRunId())
        .isEqualTo(runOf(a, step.id(), "disk_usage:1").id());

    Map<String, Long> before = counts();
    StepCancel cancel =
        new StepCancel(a.tenant, r1, i1, s1, new Key("s-diagnose"), StepCall.StepType.DIAGNOSTIC);
    new TransactionTemplate(app.context().getBean(PlatformTransactionManager.class))
        .executeWithoutResult(
            status -> {
              handler.cancel(cancel);
              handler.cancel(cancel);
            });
    assertThat(counts()).isEqualTo(before);
  }
}
