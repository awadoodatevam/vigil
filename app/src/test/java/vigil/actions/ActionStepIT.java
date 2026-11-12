package vigil.actions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import vigil.actions.api.ActionRuns;
import vigil.actions.api.ActionsService;
import vigil.actions.api.ActionsStepHandler;
import vigil.actions.api.Decision;
import vigil.audit.api.AuditEventType;
import vigil.audit.api.AuditEventView;
import vigil.incidents.api.EvidenceKind;
import vigil.incidents.api.EvidenceView;
import vigil.notifications.NotificationsApp;
import vigil.runner.LabTarget;
import vigil.runner.RunnerFixture;
import vigil.runner.api.GrantView;
import vigil.runner.api.OperationRunState;
import vigil.runner.api.OperationRunView;
import vigil.runner.api.OperationView;
import vigil.runner.api.PermissionKind;
import vigil.runner.api.RunRequest;
import vigil.runner.api.RunnerService;
import vigil.shared.Actor;
import vigil.shared.Completed;
import vigil.shared.Completed.ResultRef;
import vigil.shared.Ids;
import vigil.shared.Key;
import vigil.shared.StepAnswer;
import vigil.shared.StepCall;
import vigil.shared.TenantId;
import vigil.testsupport.MutableClock;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ActionStepIT {

  static final Instant T0 = Instant.parse("2026-11-12T09:00:00Z");
  static final MutableClock CLOCK = new MutableClock(T0);
  static final String ON = "Purge archived transaction logs on Subscriber DB server";
  static final String DATA = "{\"mount\":\"/data\"}";
  static final TestRunner RUNNER = new TestRunner();

  /** The test clock and the test runner. */
  static class TestBeans {
    @Bean
    @Primary
    Clock testClock() {
      return CLOCK;
    }

    @Bean
    @Primary
    RunnerService testRunner(@Qualifier("runner") RunnerService real, JdbcClient jdbc) {
      RUNNER.real = real;
      RUNNER.jdbc = jdbc;
      return RUNNER;
    }
  }

  /**
   * Delegates to the real runner; records each request id; answers {@code unknown} for an execute
   * after running it, or {@code failed} for an execute or a reconciliation without running it, when
   * the test switches the mode on; reads the execution row at the first precondition request.
   */
  static final class TestRunner implements RunnerService {
    volatile RunnerService real;
    volatile JdbcClient jdbc;
    volatile boolean executeUnknown;
    volatile boolean executeFailed;
    volatile boolean reconcileFailed;
    final List<String> requests = new CopyOnWriteArrayList<>();
    final List<Long> reconcileNanos = new CopyOnWriteArrayList<>();
    volatile Map<String, Object> firstSeen;

    void reset() {
      executeUnknown = false;
      executeFailed = false;
      reconcileFailed = false;
      requests.clear();
      reconcileNanos.clear();
      firstSeen = null;
    }

    @Override
    public OperationRunView run(TenantId tenantId, Actor requester, RunRequest request) {
      String id = request.requestId();
      requests.add(id);
      if (id.endsWith(":precondition:1") && firstSeen == null) {
        firstSeen =
            jdbc.sql(
                    "SELECT state, recheck_pass, operation_run_id, started_at FROM"
                        + " actions.action_execution WHERE id = :id")
                .param("id", UUID.fromString(id.substring(0, id.indexOf(':'))))
                .query()
                .singleRow();
        firstSeen.put("at", Instant.now());
      }
      if (id.contains(":reconcile:")) {
        reconcileNanos.add(System.nanoTime());
        if (reconcileFailed) {
          return fake(request, requester);
        }
      }
      if (id.endsWith(":execute") && executeFailed) {
        return fake(request, requester);
      }
      OperationRunView run = real.run(tenantId, requester, request);
      if (id.endsWith(":execute") && executeUnknown) {
        return new OperationRunView(
            run.id(),
            run.operationKey(),
            run.targetKey(),
            run.permissionKind(),
            run.parametersJson(),
            run.requestedBy(),
            run.requestId(),
            OperationRunState.UNKNOWN,
            run.startedAt(),
            run.finishedAt(),
            null,
            null,
            null,
            false,
            "test unknown");
      }
      return run;
    }

    private static OperationRunView fake(RunRequest request, Actor requester) {
      return new OperationRunView(
          Ids.newId(),
          request.operationKey(),
          request.targetKey(),
          request.permissionKind(),
          request.parametersJson(),
          requester.module(),
          request.requestId(),
          OperationRunState.FAILED,
          CLOCK.instant(),
          CLOCK.instant(),
          null,
          null,
          null,
          false,
          "test failure");
    }

    @Override
    public Optional<OperationView> operation(TenantId tenantId, String operationKey) {
      return real.operation(tenantId, operationKey);
    }

    @Override
    public List<GrantView> grants(TenantId tenantId) {
      return real.grants(tenantId);
    }

    @Override
    public Optional<OperationRunView> operationRun(TenantId tenantId, UUID id) {
      return real.operationRun(tenantId, id);
    }

    @Override
    public List<String> parameterProblems(
        TenantId tenantId, String operationKey, String parametersJson) {
      return real.parameterProblems(tenantId, operationKey, parametersJson);
    }
  }

  private NotificationsApp servers;
  private ActionsStepHandler step;
  private ActionRuns runs;
  private ActionsService actions;
  private LabTarget lab;
  private ActionsFixture demo;
  private ActionsFixture limitTwo;

  @BeforeAll
  void start() {
    servers = NotificationsApp.start(TestBeans.class);
    step = servers.app.context().getBean(ActionsStepHandler.class);
    runs = servers.app.context().getBean(ActionRuns.class);
    actions = servers.app.context().getBean(ActionsService.class);
    lab = LabTarget.shared();
    demo = ActionsFixture.create(servers, "Act");
    limitTwo = ActionsFixture.create(servers, "Act twice", ActionsFixture.part(2));
  }

  @AfterAll
  void stop() {
    servers.close();
  }

  @BeforeEach
  void reset() {
    servers.reset();
    CLOCK.set(T0);
    RUNNER.reset();
    lab.inject();
  }

  @AfterEach
  void clear() {
    lab.clear();
  }

  /** An approved request of a new run; {@code approver} decides. */
  record Case(
      ActionsFixture f, UUID incident, UUID run, UUID request, UUID proposal, StepCall action) {}

  private Case approved(ActionsFixture f) {
    UUID incident = f.openIncident();
    f.match(incident);
    UUID run = Ids.newId();
    return approve(f, incident, run, List.of(), "dbops-engineer");
  }

  private Case approve(
      ActionsFixture f, UUID incident, UUID run, List<UUID> earlier, String approver) {
    StepCall approval = f.approval(run, incident, ActionsFixture.APPROVAL, earlier);
    step.start(approval);
    Map<String, Object> row =
        f.rowsOfRun("approval_request", run).stream()
            .filter(r -> r.get("step_execution_id").equals(approval.stepExecutionId()))
            .findFirst()
            .orElseThrow();
    UUID request = (UUID) row.get("id");
    actions.decide(
        f.tenant, request, f.userId(approver), Decision.APPROVE, (String) row.get("snapshot_hash"));
    assertThat(step.evaluate(approval)).isInstanceOf(Completed.class);
    return new Case(
        f, incident, run, request, (UUID) row.get("proposal_id"), f.action(run, incident));
  }

  private static Completed completed(StepAnswer answer, String output) {
    assertThat(answer).isInstanceOf(Completed.class);
    Completed completed = (Completed) answer;
    assertThat(completed.namedOutput()).isEqualTo(new Key(output));
    return completed;
  }

  private Map<String, Object> execution(Case c) {
    return execution(c.f(), c.action().stepExecutionId());
  }

  private static Map<String, Object> execution(ActionsFixture f, UUID stepExecution) {
    return f.postgres()
        .sql(
            "SELECT *, recheck::text AS recheck_text FROM actions.action_execution WHERE"
                + " step_execution_id = :s")
        .param("s", stepExecution)
        .query()
        .singleRow();
  }

  private static JsonNode json(Object value) {
    return ActionsFixture.JSON.readTree(value.toString());
  }

  private static List<String> checks(Map<String, Object> execution) {
    return json(execution.get("recheck_text"))
        .valueStream()
        .map(e -> e.get("check").asString() + " " + e.get("passed").asBoolean())
        .toList();
  }

  private static String detail(Map<String, Object> execution, int index) {
    return json(execution.get("recheck_text")).get(index).get("detail").asString();
  }

  private String usedPct(ActionsFixture f) {
    OperationRunView run =
        RUNNER.real.run(
            f.tenant,
            Actor.module("vigil-diagnostics"),
            RunnerFixture.request(
                "pct:" + Ids.newId(), PermissionKind.DIAGNOSTIC, "fs_used_pct", DATA));
    assertThat(run.state()).as(run.toString()).isEqualTo(OperationRunState.SUCCEEDED);
    return StateChecks.value(run, "value");
  }

  private List<Map<String, Object>> operationRuns(ActionsFixture f, UUID execution) {
    return f.postgres()
        .sql(
            "SELECT request_id, permission_kind, requested_by, state FROM runner.operation_run"
                + " WHERE tenant_id = :t AND request_id LIKE :p ORDER BY started_at, id")
        .param("t", f.tenant.value())
        .param("p", execution + ":%")
        .query()
        .listOfRows();
  }

  private static UUID id(Map<String, Object> row) {
    return (UUID) row.get("id");
  }

  @Test
  @DisplayName("PM-M14-07 the four rechecks, then the runner call with action permission")
  void storyline() {
    Case c = approved(demo);
    String before = usedPct(demo);
    assertThat(Long.parseLong(before)).isGreaterThanOrEqualTo(90);
    Completed done = completed(step.start(c.action()), "succeeded");
    Map<String, Object> e = execution(c);
    assertThat(done.resultRef())
        .isEqualTo(new ResultRef("vigil-actions", "ActionExecution", id(e)));
    assertThat(e.get("state")).isEqualTo("succeeded");
    assertThat(e.get("attempt_number")).isEqualTo(1L);
    assertThat(RUNNER.requests).containsExactly(id(e) + ":precondition:1", id(e) + ":execute");
    assertThat(operationRuns(demo, id(e)))
        .extracting(
            r -> r.get("request_id") + " " + r.get("permission_kind") + " " + r.get("requested_by"))
        .containsExactly(
            id(e) + ":precondition:1 diagnostic vigil-actions",
            id(e) + ":execute action vigil-actions");
    assertThat(checks(e))
        .containsExactly(
            "approval_valid true", "unchanged true", "incident_open true", "precondition true");
    assertThat(detail(e, 0))
        .isEqualTo("approved by Usman Tariq (DB Ops); valid until 2026-11-12 09:15:00 UTC");
    assertThat(detail(e, 1)).isEqualTo("unchanged since approval");
    assertThat(detail(e, 2)).isEqualTo("Open");
    assertThat(detail(e, 3))
        .isEqualTo("Used percentage of one filesystem (mount=/data): " + before + " ≥ 90");
    List<EvidenceView> evidence = demo.evidenceOf(c.incident(), EvidenceKind.ACTION_EXECUTION);
    assertThat(evidence).hasSize(1);
    assertThat(evidence.getFirst().summary())
        .startsWith(ON + ": Succeeded; ")
        .matches(".*; \\d+ files? removed \\(\\d+\\.\\d GiB\\)\\.$");
    assertThat(demo.audits(c.incident(), AuditEventType.ACTION_EXECUTED))
        .extracting(AuditEventView::summary)
        .containsExactly(ON + ", attempt 1: Succeeded.");
    assertThat(Long.parseLong(usedPct(demo))).isLessThan(85);
  }

  @Test
  @DisplayName("PM-S-06 the outputs succeeded and failed")
  void outputs() {
    completed(step.start(approved(demo).action()), "succeeded");
    lab.inject();
    Case c = approved(demo);
    lab.clear();
    completed(step.start(c.action()), "failed");
    assertThat(execution(c).get("state")).isEqualTo("not_executed");
  }

  @Test
  @DisplayName("DM-S-06 never waits; another step type is refused; any stored state is continued")
  void neverWaits() {
    Case c = approved(demo);
    StepAnswer answer = step.start(c.action());
    assertThat(answer).isInstanceOf(Completed.class);
    assertThat(step.evaluate(c.action())).isEqualTo(answer);
    assertThat(runs.reconcile(c.action())).isEqualTo(answer);
    StepCall other =
        new StepCall(
            demo.tenant,
            c.run(),
            c.incident(),
            Ids.newId(),
            new Key("s-x"),
            StepCall.StepType.WAIT,
            "{}",
            1,
            null,
            new StepCall.WakeReason("start"),
            null,
            null);
    assertThatThrownBy(() -> step.start(other)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> runs.reconcile(other)).isInstanceOf(IllegalArgumentException.class);
    for (String state : List.of("succeeded", "failed", "not_executed", "unresolved")) {
      UUID stepExecution = Ids.newId();
      demo.insertExecution(
          c.run(),
          c.incident(),
          stepExecution,
          c.proposal(),
          List.of(c.request()),
          state,
          1,
          1,
          T0);
      Completed completed = runs.reconcile(demo.action(c.run(), c.incident(), stepExecution));
      assertThat(completed.namedOutput().value())
          .isEqualTo(state.equals("succeeded") ? "succeeded" : "failed");
      assertThat(execution(demo, stepExecution).get("state")).isEqualTo(state);
    }
  }

  @Test
  @DisplayName("DM-E-60 the execution row after a success")
  void executionRow() {
    Case c = approved(demo);
    CLOCK.advance(Duration.ofSeconds(5));
    step.start(c.action());
    Map<String, Object> e = execution(c);
    assertThat(e.get("incident_id")).isEqualTo(c.incident());
    assertThat(e.get("run_id")).isEqualTo(c.run());
    assertThat(e.get("step_execution_id")).isEqualTo(c.action().stepExecutionId());
    assertThat(e.get("proposal_id")).isEqualTo(c.proposal());
    assertThat(e.get("action_key")).isEqualTo("purge-archived-txlogs");
    assertThat(json(e.get("approval_request_ids")).get(0).asString())
        .isEqualTo(c.request().toString());
    assertThat(json(e.get("approval_request_ids"))).hasSize(1);
    assertThat(e.get("attempt_number")).isEqualTo(1L);
    assertThat(e.get("state")).isEqualTo("succeeded");
    assertThat(e.get("recheck_pass")).isEqualTo(1L);
    assertThat(json(e.get("recheck_text"))).hasSize(4);
    UUID operationRun = (UUID) e.get("operation_run_id");
    assertThat(RUNNER.real.operationRun(demo.tenant, operationRun).orElseThrow().requestId())
        .isEqualTo(id(e) + ":execute");
    assertThat(json(e.get("reconciliation_run_ids"))).isEmpty();
    Instant at = T0.plusSeconds(5);
    assertThat(((Timestamp) e.get("started_at")).toInstant()).isEqualTo(at);
    assertThat(((Timestamp) e.get("executing_at")).toInstant()).isEqualTo(at);
    assertThat(((Timestamp) e.get("finished_at")).toInstant()).isEqualTo(at);
  }

  @Test
  @DisplayName("DM-E60-R1 the execution is stored before the first runner request")
  void recordedFirst() {
    Case c = approved(demo);
    Instant before = Instant.now();
    step.start(c.action());
    Map<String, Object> seen = RUNNER.firstSeen;
    assertThat(seen).isNotNull();
    assertThat(seen.get("state")).isEqualTo("rechecking");
    assertThat(seen.get("recheck_pass")).isEqualTo(1L);
    assertThat(seen.get("operation_run_id")).isNull();
    assertThat(((Timestamp) seen.get("started_at")).toInstant()).isEqualTo(T0);
    assertThat((Instant) seen.get("at")).isAfter(before);
    assertThat(RUNNER.requests.getFirst()).endsWith(":precondition:1");
  }

  @Test
  @DisplayName("DM-E14-R1 the request ids of vigil-actions; a repeated id returns the earlier run")
  void requestIds() {
    Case c = approved(demo);
    RUNNER.executeUnknown = true;
    step.start(c.action());
    Map<String, Object> e = execution(c);
    assertThat(RUNNER.requests)
        .containsExactly(id(e) + ":precondition:1", id(e) + ":execute", id(e) + ":reconcile:1");
    UUID operationRun = (UUID) e.get("operation_run_id");
    OperationRunView again =
        RUNNER.real.run(
            demo.tenant,
            Actor.module("vigil-actions"),
            new RunRequest(
                id(e) + ":execute",
                PermissionKind.ACTION,
                "purge_old_archives",
                "lab-db-01",
                "{\"path\":\"/data/txlog_archive\",\"older_than_minutes\":1440,"
                    + "\"max_bytes\":12884901888}"));
    assertThat(again.id()).isEqualTo(operationRun);
    assertThat(operationRuns(demo, id(e))).hasSize(3);
  }

  @Test
  @DisplayName("PM-M14-09 the evidence entries of an approval and an execution")
  void evidence() {
    Case c = approved(demo);
    step.start(c.action());
    EvidenceView approval = demo.evidenceOf(c.incident(), EvidenceKind.APPROVAL).getFirst();
    assertThat(approval.summary()).isEqualTo("Approved by Usman Tariq (DB Ops): " + ON + ".");
    assertThat(approval.sourceSystem()).isEqualTo("Approval 1 of the run");
    assertThat(approval.producerModule()).isEqualTo("vigil-actions");
    assertThat(approval.producerRecordId()).isEqualTo(c.request());
    JsonNode a = json(approval.contentJson());
    assertThat(a.get("approval_request_id").asString()).isEqualTo(c.request().toString());
    assertThat(a.get("state").asString()).isEqualTo("approved");
    assertThat(a.get("sequence_in_run").asLong()).isEqualTo(1);
    assertThat(a.get("policy_outcome").asString()).isEqualTo("approval_required");
    assertThat(a.get("decided_by").asString()).isEqualTo(demo.userId("dbops-engineer").toString());
    assertThat(a.get("decided_at").asString()).isEqualTo(T0.toString());
    assertThat(a.get("action_key").asString()).isEqualTo("purge-archived-txlogs");
    assertThat(a.get("target_key").asString()).isEqualTo("lab-db-01");
    assertThat(a.get("snapshot_hash").asString()).hasSize(64);
    EvidenceView execution =
        demo.evidenceOf(c.incident(), EvidenceKind.ACTION_EXECUTION).getFirst();
    Map<String, Object> e = execution(c);
    assertThat(execution.producerRecordId()).isEqualTo(id(e));
    assertThat(execution.sourceSystem())
        .isEqualTo("lab-db-01 via runner, operation purge_old_archives");
    JsonNode x = json(execution.contentJson());
    assertThat(x.get("action_execution_id").asString()).isEqualTo(id(e).toString());
    assertThat(x.get("state").asString()).isEqualTo("succeeded");
    assertThat(x.get("attempt_number").asLong()).isEqualTo(1);
    assertThat(x.get("action_key").asString()).isEqualTo("purge-archived-txlogs");
    assertThat(x.get("target_key").asString()).isEqualTo("lab-db-01");
    assertThat(x.get("operation_key").asString()).isEqualTo("purge_old_archives");
    assertThat(x.get("operation_run_id").asString())
        .isEqualTo(e.get("operation_run_id").toString());
    assertThat(x.get("output_values").valueStream().map(v -> v.get("name").asString()).toList())
        .containsExactly("files_removed", "bytes_removed", "gib_removed");
    assertThat(x.get("recheck")).hasSize(4);
  }

  private void assertNotExecuted(Case c, String failed, String detail, String name) {
    completed(step.start(c.action()), "failed");
    Map<String, Object> e = execution(c);
    assertThat(e.get("state")).isEqualTo("not_executed");
    assertThat(e.get("finished_at")).isNotNull();
    assertThat(e.get("executing_at")).isNull();
    List<String> checks = checks(e);
    assertThat(checks.getLast()).isEqualTo(failed + " false");
    assertThat(checks.subList(0, checks.size() - 1)).allMatch(check -> check.endsWith(" true"));
    assertThat(detail(e, checks.size() - 1)).isEqualTo(detail);
    assertThat(RUNNER.requests).noneMatch(r -> r.endsWith(":execute"));
    assertThat(c.f().evidenceOf(c.incident(), EvidenceKind.ACTION_EXECUTION))
        .extracting(EvidenceView::summary)
        .containsExactly(ON + ": not executed, recheck '" + name + "' failed.");
    assertThat(c.f().audits(c.incident(), AuditEventType.ACTION_EXECUTED))
        .extracting(AuditEventView::summary)
        .containsExactly(ON + ", attempt 1: Not executed: a recheck failed.");
  }

  @Test
  @DisplayName("DM-R-32 each recheck failing on its own stops the execution")
  void rechecks() {
    Case expired = approved(demo);
    CLOCK.set(T0.plusSeconds(900));
    assertNotExecuted(
        expired,
        "approval_valid",
        "approved by Usman Tariq (DB Ops); valid until 2026-11-12 09:15:00 UTC",
        "Approval still valid");
    assertThat(checks(execution(expired))).hasSize(1);
    CLOCK.set(T0);

    Case action = approved(demo);
    demo.postgres()
        .sql(
            "UPDATE actions.registered_action SET limits = limits || '[\"one more\"]'::jsonb"
                + " WHERE tenant_id = :t")
        .param("t", demo.tenant.value())
        .update();
    try {
      assertNotExecuted(
          action, "unchanged", "changed: action", "Action, parameters and target unchanged");
    } finally {
      demo.postgres()
          .sql("UPDATE actions.registered_action SET limits = limits - 4 WHERE tenant_id = :t")
          .param("t", demo.tenant.value())
          .update();
    }

    Case hypothesis = approved(demo);
    demo.postgres()
        .sql(
            "UPDATE incidents.incident SET hypothesis_component_key = 'db-subscriber'"
                + " WHERE id = :i")
        .param("i", hypothesis.incident())
        .update();
    assertNotExecuted(
        hypothesis,
        "unchanged",
        "changed: hypothesis component",
        "Action, parameters and target unchanged");

    Case resolved = approved(demo);
    demo.notifications.resolve(resolved.incident());
    assertNotExecuted(resolved, "incident_open", "Resolved", "Incident still open");

    Case precondition = approved(demo);
    lab.clear();
    String pct = usedPct(demo);
    assertNotExecuted(
        precondition,
        "precondition",
        "Used percentage of one filesystem (mount=/data): " + pct + " ≥ 90",
        "Precondition");
    assertThat(checks(execution(precondition))).hasSize(4);
  }

  /** The unknown execution whose three reconciliation answers are {@code failed}. */
  private Map<String, Object> unresolved(Case c) {
    RUNNER.executeUnknown = true;
    RUNNER.reconcileFailed = true;
    completed(step.start(c.action()), "failed");
    return execution(c);
  }

  @Test
  @DisplayName(
      "DM-R-33 an unknown result is reconciled: succeeded, or unresolved after three checks")
  void unknownResult() {
    Case c = approved(demo);
    RUNNER.executeUnknown = true;
    completed(step.start(c.action()), "succeeded");
    Map<String, Object> e = execution(c);
    assertThat(e.get("state")).isEqualTo("succeeded");
    assertThat(json(e.get("reconciliation_run_ids"))).hasSize(1);
    assertThat(RUNNER.requests).last().isEqualTo(id(e) + ":reconcile:1");
    assertThat(demo.audits(c.incident(), AuditEventType.ACTION_RECONCILED))
        .extracting(AuditEventView::summary)
        .containsExactly(ON + ", attempt 1: result checked, Succeeded.");
    assertThat(demo.audits(c.incident(), AuditEventType.ACTION_EXECUTED)).isEmpty();

    RUNNER.reset();
    lab.inject();
    Case u = approved(limitTwo);
    Map<String, Object> x = unresolved(u);
    assertThat(x.get("state")).isEqualTo("unresolved");
    assertThat(RUNNER.requests)
        .endsWith(id(x) + ":reconcile:1", id(x) + ":reconcile:2", id(x) + ":reconcile:3");
    List<EvidenceView> unknown = limitTwo.evidenceOf(u.incident(), EvidenceKind.ACTION_EXECUTION);
    assertThat(unknown).hasSize(1);
    assertThat(unknown.getFirst().summary()).startsWith(ON + ": Result unknown");
    assertThat(limitTwo.audits(u.incident(), AuditEventType.ACTION_RECONCILED))
        .extracting(AuditEventView::summary)
        .containsExactly(ON + ", attempt 1: result checked, Result unknown.");
  }

  @Test
  @DisplayName("AR-TM-06 three reconciliation attempts, at least 10 s apart")
  void reconciliationSpacing() {
    Map<String, Object> x = unresolved(approved(limitTwo));
    assertThat(json(x.get("reconciliation_run_ids"))).hasSize(3);
    assertThat(RUNNER.reconcileNanos).hasSize(3);
    for (int i = 1; i < 3; i++) {
      assertThat(RUNNER.reconcileNanos.get(i) - RUNNER.reconcileNanos.get(i - 1))
          .isGreaterThanOrEqualTo(Duration.ofSeconds(10).toNanos());
    }
  }

  @Test
  @DisplayName(
      "PM-M14-08 recorded before the runner call; an unknown result is never executed again")
  void neverAgain() {
    Case c = approved(demo);
    RUNNER.executeUnknown = true;
    step.start(c.action());
    assertThat(RUNNER.firstSeen.get("state")).isEqualTo("rechecking");
    UUID id = id(execution(c));
    step.evaluate(c.action());
    runs.reconcile(c.action());
    assertThat(RUNNER.requests.stream().filter(r -> r.endsWith(":execute"))).hasSize(1);
    assertThat(
            operationRuns(demo, id).stream()
                .filter(r -> r.get("request_id").toString().endsWith(":execute")))
        .hasSize(1);
  }

  @Test
  @DisplayName("DM-E60-R2 the attempt number per incident and action, then the limit")
  void attemptLimit() {
    Case first = approved(limitTwo);
    RUNNER.executeFailed = true;
    completed(step.start(first.action()), "failed");
    assertThat(execution(first).get("state")).isEqualTo("failed");
    assertThat(execution(first).get("attempt_number")).isEqualTo(1L);
    RUNNER.executeFailed = false;
    CLOCK.advance(Duration.ofSeconds(1));
    Case second =
        approve(
            limitTwo,
            first.incident(),
            first.run(),
            List.of(limitTwo.userId("dbops-engineer")),
            "dbops-lead");
    completed(step.start(second.action()), "succeeded");
    Map<String, Object> e = execution(second);
    assertThat(e.get("attempt_number")).isEqualTo(2L);
    assertThat(json(e.get("approval_request_ids")).get(0).asString())
        .isEqualTo(second.request().toString());
    assertThat(json(e.get("approval_request_ids"))).hasSize(1);
    CLOCK.advance(Duration.ofSeconds(1));
    StepCall third =
        limitTwo.approval(
            first.run(),
            first.incident(),
            ActionsFixture.APPROVAL,
            List.of(limitTwo.userId("dbops-engineer"), limitTwo.userId("dbops-lead")));
    completed(step.start(third), "rejected");
    Map<String, Object> refused = limitTwo.rowsOfRun("approval_request", first.run()).getLast();
    assertThat(refused.get("state")).isEqualTo("refused");
    assertThat(refused.get("refusal_reason")).isEqualTo("attempt_limit_reached");
  }

  @Test
  @DisplayName(
      "DM-SM-12 executions left recorded, rechecking, executing and succeeded are continued")
  void restart() {
    for (String state : List.of("recorded", "rechecking", "executing", "succeeded")) {
      RUNNER.reset();
      lab.inject();
      Case c = approved(demo);
      long pass = state.equals("rechecking") ? 1 : 0;
      Instant executing = state.equals("executing") || state.equals("succeeded") ? T0 : null;
      UUID id =
          demo.insertExecution(
              c.run(),
              c.incident(),
              c.action().stepExecutionId(),
              c.proposal(),
              List.of(c.request()),
              state,
              1,
              pass,
              executing);
      Completed answer = runs.reconcile(c.action());
      Map<String, Object> e = execution(c);
      switch (state) {
        case "recorded" -> {
          assertThat(RUNNER.requests).containsExactly(id + ":precondition:1", id + ":execute");
          assertThat(e.get("state")).isEqualTo("succeeded");
          assertThat(answer.namedOutput().value()).isEqualTo("succeeded");
        }
        case "rechecking" -> {
          assertThat(RUNNER.requests).containsExactly(id + ":precondition:2", id + ":execute");
          assertThat(e.get("recheck_pass")).isEqualTo(2L);
          assertThat(e.get("state")).isEqualTo("succeeded");
        }
        case "executing" -> {
          assertThat(RUNNER.requests).containsExactly(id + ":reconcile:1");
          assertThat(e.get("state")).isEqualTo("failed");
          assertThat(demo.audits(c.incident(), AuditEventType.ACTION_RECONCILED)).hasSize(1);
        }
        default -> {
          assertThat(RUNNER.requests).isEmpty();
          assertThat(answer.namedOutput().value()).isEqualTo("succeeded");
          assertThat(e.get("finished_at")).isNull();
        }
      }
      assertThat(answer.resultRef().id()).isEqualTo(id);
    }
  }

  @Test
  @DisplayName("SL-B-11 one request and one execution per step execution; unknown reconciled first")
  void onceEach() {
    Case c = approved(limitTwo);
    Map<String, Object> x = unresolved(c);
    assertThat(x.get("state")).isEqualTo("unresolved");
    step.start(c.action());
    runs.reconcile(c.action());
    assertThat(limitTwo.rowsOfRun("action_execution", c.run())).hasSize(1);
    assertThat(limitTwo.rowsOfRun("approval_request", c.run())).hasSize(1);
    assertThat(RUNNER.requests.stream().filter(r -> r.endsWith(":execute"))).hasSize(1);
    CLOCK.advance(Duration.ofSeconds(1));
    StepCall next =
        limitTwo.approval(
            c.run(),
            c.incident(),
            ActionsFixture.APPROVAL,
            List.of(limitTwo.userId("dbops-engineer")));
    completed(step.start(next), "rejected");
    Map<String, Object> refused = limitTwo.rowsOfRun("approval_request", c.run()).getLast();
    assertThat(refused.get("refusal_reason")).isEqualTo("unresolved_execution");
  }

  @Test
  @DisplayName(
      "DM-E08-R1 the details of action_executed, action_reconciled and policy_check_result")
  void details() {
    Case c = approved(demo);
    step.start(c.action());
    AuditEventView executed = demo.audits(c.incident(), AuditEventType.ACTION_EXECUTED).getFirst();
    assertThat(json(executed.detailsJson()))
        .isEqualTo(json("{\"attempt_number\": 1, \"outcome\": \"succeeded\"}"));
    assertThat(executed.subjectType()).isEqualTo("ActionExecution");
    AuditEventView policy =
        demo.audits(c.incident(), AuditEventType.POLICY_CHECK_RESULT).getFirst();
    assertThat(json(policy.detailsJson())).isEqualTo(json("{\"outcome\": \"approval_required\"}"));

    RUNNER.reset();
    lab.inject();
    Case u = approved(limitTwo);
    RUNNER.executeUnknown = true;
    step.start(u.action());
    AuditEventView reconciled =
        limitTwo.audits(u.incident(), AuditEventType.ACTION_RECONCILED).getFirst();
    assertThat(json(reconciled.detailsJson()))
        .isEqualTo(json("{\"attempt_number\": 1, \"outcome\": \"succeeded\"}"));
  }
}
