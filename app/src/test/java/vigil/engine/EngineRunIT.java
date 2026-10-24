package vigil.engine;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import vigil.engine.api.EndReason;
import vigil.engine.api.RunFinished;
import vigil.engine.api.RunMode;
import vigil.engine.api.RunStarted;
import vigil.engine.api.RunState;
import vigil.engine.api.RunView;
import vigil.engine.api.StepChanged;
import vigil.engine.api.StepExecutionState;
import vigil.engine.api.StepExecutionView;
import vigil.engine.api.WaitReason;
import vigil.incidents.api.IncidentAcknowledged;
import vigil.incidents.api.IncidentService;
import vigil.incidents.api.IncidentState;
import vigil.intake.IntakeFixture;
import vigil.intake.api.AlertStateChanged;
import vigil.intake.api.AlertStatus;
import vigil.intake.api.AlertView;
import vigil.shared.Actor;
import vigil.shared.Ids;
import vigil.shared.Key;
import vigil.shared.StepCall;
import vigil.shared.StepCall.StepType;
import vigil.shared.Waiting;
import vigil.shared.Waiting.WakeEvent;
import vigil.testsupport.LogLines;
import vigil.testsupport.MutableClock;
import vigil.testsupport.TestSecrets;
import vigil.testsupport.VigilDatabase;
import vigil.testsupport.VigilTestApp;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@ExtendWith(OutputCaptureExtension.class)
class EngineRunIT {

  private static final MutableClock CLOCK = new MutableClock(Instant.parse("2026-10-24T10:00:00Z"));
  private static final String HOST = "a.engine-run.test";
  private static final String ALERTNAME = "NodeFilesystemFull";

  /** The test clock and the recorder of the engine's events. */
  static class TestBeans {

    @Bean
    @Primary
    Clock testClock() {
      return CLOCK;
    }

    @Bean
    EventRecorder eventRecorder() {
      return new EventRecorder();
    }
  }

  /** Records the events of AR-E-10 when they are published. */
  static class EventRecorder {

    final List<Object> events = new CopyOnWriteArrayList<>();

    @EventListener
    void on(RunStarted event) {
      events.add(event);
    }

    @EventListener
    void on(StepChanged event) {
      events.add(event);
    }

    @EventListener
    void on(RunFinished event) {
      events.add(event);
    }
  }

  private WireMockServer wiremock;
  private VigilDatabase database;
  private JdbcClient postgres;
  private VigilTestApp app;
  private EngineFixture a;
  private TestHandlers handlers;
  private EventRecorder recorder;
  private TransactionTemplate tx;
  private int component;

  @BeforeAll
  void start() {
    wiremock = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort());
    wiremock.start();
    IntakeFixture.stub(wiremock);
    database = VigilDatabase.fresh();
    postgres = database.postgres();
    TestSecrets secrets = IntakeFixture.secrets(database, "");
    app =
        VigilTestApp.start(
            database, secrets.folder(), List.of(), TestBeans.class, TestHandlers.Beans.class);
    app.awaitStarted();
    a =
        EngineFixture.create(
            app, secrets, "Tenant A", HOST, "http://127.0.0.1:" + wiremock.port(), "");
    handlers = app.context().getBean(TestHandlers.class);
    recorder = app.context().getBean(EventRecorder.class);
    tx = new TransactionTemplate(app.context().getBean(PlatformTransactionManager.class));
  }

  @AfterAll
  void stop() {
    app.close();
    wiremock.stop();
  }

  private <T> List<T> events(Class<T> type, UUID run) {
    return recorder.events.stream()
        .filter(type::isInstance)
        .map(type::cast)
        .filter(
            event ->
                switch (event) {
                  case RunStarted e -> e.runId().equals(run);
                  case StepChanged e -> e.runId().equals(run);
                  case RunFinished e -> e.runId().equals(run);
                  default -> false;
                })
        .toList();
  }

  private UUID incident(AlertView alert) {
    return app.context()
        .getBean(IncidentService.class)
        .incidentOfAlert(a.tenant, alert.id())
        .orElseThrow();
  }

  /** A new open incident on a component used once in this class. */
  private UUID openIncident() {
    component++;
    return a.openIncident(CLOCK, "nowhere-" + component, "i-1", ALERTNAME);
  }

  private static JsonNode tree(String json) {
    return TestContents.JSON.readTree(json);
  }

  private static JsonNode parameters(String content, String stepId) {
    for (JsonNode step : tree(content).get("steps")) {
      if (step.get("step_id").stringValue().equals(stepId)) {
        return step.get("parameters");
      }
    }
    throw new IllegalArgumentException(stepId);
  }

  private List<Map<String, Object>> timers(UUID run) {
    return postgres
        .sql(
            "SELECT id, state, due_at, step_execution_id FROM engine.durable_timer"
                + " WHERE tenant_id = :t AND run_id = :r ORDER BY created_at, id")
        .param("t", a.tenant.value())
        .param("r", run)
        .query()
        .listOfRows();
  }

  private void deliver(Runnable listenerCall) {
    tx.executeWithoutResult(status -> listenerCall.run());
  }

  private EngineIntakeListener intakeListener() {
    return AopTestUtils.getUltimateTargetObject(app.context().getBean(EngineIntakeListener.class));
  }

  private EngineIncidentListener incidentListener() {
    return AopTestUtils.getUltimateTargetObject(
        app.context().getBean(EngineIncidentListener.class));
  }

  // PM-M12-01 and the checks on its runs.

  private String v1;
  private UUID i1;
  private UUID i2;
  private UUID r1;
  private UUID r2;
  private RunView r1Forming;
  private List<StepExecutionView> r1FormingSteps;
  private RunView r2Forming;
  private List<StepExecutionView> r2FormingSteps;

  @Test
  @Order(1)
  @DisplayName("PM-M12-01 a run completes on its own copy of the content it started with")
  void ownCopy() {
    handlers.reset();
    v1 = TestContents.version1();
    AlertView s =
        a.correlation.postZabbix("sms-queue", "lab-vas-01", "SMS queue growing on route r1");
    a.correlation.awaitDecided();
    i1 = incident(s);
    a.awaitPublications();
    r1 = a.start(i1, v1);
    r1Forming = a.awaitRunState(r1, RunState.WAITING);
    r1FormingSteps = a.executions(r1);
    CLOCK.advance(Duration.ofSeconds(10));
    AlertView bundle =
        a.correlation.postAlertmanager("api-bundle", "api-bundle-1", "BundleApiErrors");
    a.correlation.awaitDecided();
    i2 = incident(bundle);
    a.awaitPublications();
    r2 = a.start(i2, v1);
    r2Forming = a.awaitRunState(r2, RunState.WAITING);
    r2FormingSteps = a.executions(r2);
    CLOCK.advance(Duration.ofSeconds(5));
    a.correlation.postZabbix("db-subscriber", "lab-db-01", "Database write errors on schema s1");
    a.correlation.awaitDecided();
    a.correlation.awaitState(i2, IncidentState.MERGED);
    CLOCK.advance(Duration.ofSeconds(40));
    a.correlation.awaitState(i1, IncidentState.OPEN);
    RunView finished = a.awaitRunState(r1, RunState.FINISHED);
    assertThat(finished.endReason()).isEqualTo(EndReason.PATH_ENDED);
    assertThat(a.steps(r1))
        .containsExactly(
            "s-trigger",
            "s-severity",
            "s-diagnose",
            "s-investigate",
            "s-summarise",
            "s-ticket",
            "s-notify",
            "s-wait-ack",
            "s-similar",
            "s-approve",
            "s-action",
            "s-recover",
            "s-resolve");
    assertThat(a.outputs(r1))
        .containsExactly(
            "next",
            "p1",
            "done",
            "done",
            "done",
            "done",
            "sent",
            "next",
            "done",
            "approved",
            "succeeded",
            "recovered",
            "done");
    assertThat(tree(finished.contentJson())).isEqualTo(tree(v1));
  }

  @Test
  @Order(2)
  @DisplayName("SL-B-03 a run on the otherwise path completes on the content it started with")
  void otherwisePath() {
    handlers.reset();
    UUID i3 = a.formingIncident("nowhere-x", "i-1", ALERTNAME);
    UUID run = a.start(i3, v1);
    CLOCK.advance(Duration.ofSeconds(41));
    RunView finished = a.awaitRunState(run, RunState.FINISHED);
    assertThat(
            app.context()
                .getBean(IncidentService.class)
                .incident(a.tenant, i3)
                .orElseThrow()
                .severity()
                .key())
        .isEqualTo("P4");
    assertThat(a.steps(run))
        .containsExactly(
            "s-trigger",
            "s-severity",
            "s-summarise-2",
            "s-ticket-2",
            "s-notify-2",
            "s-recover-2",
            "s-resolve-2");
    assertThat(a.execution(run, "s-severity").namedOutput()).isEqualTo("otherwise");
    assertThat(tree(finished.contentJson())).isEqualTo(tree(v1));
  }

  @Test
  @Order(3)
  @DisplayName("DM-S-01 the Trigger step completes with next at the run's start")
  void triggerStep() {
    StepExecutionView trigger = a.executions(r1).getFirst();
    assertThat(trigger.stepId()).isEqualTo("s-trigger");
    assertThat(trigger.stepType()).isEqualTo(StepType.TRIGGER);
    assertThat(trigger.state()).isEqualTo(StepExecutionState.COMPLETED);
    assertThat(trigger.namedOutput()).isEqualTo("next");
    assertThat(handlers.calls(r1))
        .noneMatch(call -> call.call.stepType() == StepType.TRIGGER)
        .noneMatch(call -> call.call.stepId().value().equals("s-trigger"));
  }

  @Test
  @Order(4)
  @DisplayName("PM-S-01 Trigger: the output next, without a handler call")
  void triggerOutput() {
    assertThat(a.execution(r1, "s-trigger").namedOutput()).isEqualTo("next");
    assertThat(handlers.calls(r1).stream().map(call -> call.call.stepId().value()))
        .doesNotContain("s-trigger");
  }

  @Test
  @Order(5)
  @DisplayName(
      "PM-M12-02 Trigger and Condition in the engine; every other step through its handler")
  void handlerCalls() {
    List<TestHandlers.Call> calls = handlers.calls(r1);
    assertThat(calls.stream().map(call -> call.call.stepId().value()))
        .containsExactly(
            "s-diagnose",
            "s-investigate",
            "s-summarise",
            "s-ticket",
            "s-notify",
            "s-wait-ack",
            "s-similar",
            "s-approve",
            "s-action",
            "s-recover",
            "s-resolve");
    for (TestHandlers.Call call : calls) {
      assertThat(call.operation).isEqualTo("start");
      StepExecutionView execution = a.execution(r1, call.call.stepId().value());
      assertThat(call.call.stepType()).isEqualTo(execution.stepType());
      assertThat(tree(call.call.parameters()))
          .isEqualTo(parameters(v1, call.call.stepId().value()));
    }
  }

  @Test
  @Order(6)
  @DisplayName("AR-E-10 RunStarted, StepChanged and RunFinished of a run")
  void events() {
    assertThat(events(RunStarted.class, r1))
        .containsExactly(new RunStarted(a.tenant, r1, i1, RunMode.LIVE, null));
    List<StepChanged> changes = events(StepChanged.class, r1);
    assertThat(changes.stream().filter(e -> e.state() == StepExecutionState.COMPLETED)).hasSize(13);
    assertThat(changes.stream().filter(e -> e.state() == StepExecutionState.RUNNING)).hasSize(12);
    assertThat(changes).hasSize(25);
    assertThat(events(RunFinished.class, r1))
        .containsExactly(new RunFinished(a.tenant, r1, i1, RunMode.LIVE, null));
  }

  @Test
  @Order(7)
  @DisplayName("PM-M12-08 step changed is published when a step starts and when it finishes")
  void stepChanged() {
    List<StepChanged> changes = events(StepChanged.class, r1);
    for (StepExecutionView execution : a.executions(r1)) {
      List<StepExecutionState> states =
          changes.stream()
              .filter(e -> e.stepExecutionId().equals(execution.id()))
              .map(StepChanged::state)
              .toList();
      if (execution.stepType() == StepType.TRIGGER) {
        assertThat(states).containsExactly(StepExecutionState.COMPLETED);
      } else {
        assertThat(states)
            .containsExactly(StepExecutionState.RUNNING, StepExecutionState.COMPLETED);
      }
    }
  }

  private UUID r3;

  @Test
  @Order(8)
  @DisplayName("DM-R-16 runs wait for a stable group; the state is read before each step")
  void formingAndMerged() {
    assertThat(r1Forming.waitReason()).isEqualTo(WaitReason.INCIDENT_FORMING);
    assertThat(r1FormingSteps).extracting(StepExecutionView::stepId).containsExactly("s-trigger");
    assertThat(r2Forming.state()).isEqualTo(RunState.WAITING);
    assertThat(r2Forming.waitReason()).isEqualTo(WaitReason.INCIDENT_FORMING);
    assertThat(r2FormingSteps).extracting(StepExecutionView::stepId).containsExactly("s-trigger");
    RunView merged = a.awaitRunState(r2, RunState.CANCELLED);
    assertThat(merged.endReason()).isEqualTo(EndReason.INCIDENT_MERGED);
    assertThat(events(RunFinished.class, r2)).hasSize(1);
    r3 =
        a.engine
            .startLiveRun(a.tenant, EngineFixture.WORKFLOWS, i2, 2, Ids.newId(), v1, false)
            .orElseThrow();
    RunView cancelled = a.awaitRunState(r3, RunState.CANCELLED);
    assertThat(cancelled.endReason()).isEqualTo(EndReason.INCIDENT_MERGED);
    assertThat(a.steps(r3)).containsExactly("s-trigger");
    assertThat(events(RunFinished.class, r3)).hasSize(1);
  }

  @Test
  @Order(9)
  @DisplayName(
      "PM-M12-11 no step after the Trigger while forming; runs of merged incidents cancelled")
  void formingRecorded() {
    assertThat(r1Forming.state()).isEqualTo(RunState.WAITING);
    assertThat(a.execution(r1, "s-severity").startedAt())
        .isAfterOrEqualTo(
            app.context()
                .getBean(IncidentService.class)
                .incident(a.tenant, i1)
                .orElseThrow()
                .stableAt());
    assertThat(a.run(r2).state()).isEqualTo(RunState.CANCELLED);
    assertThat(a.steps(r2)).containsExactly("s-trigger");
    assertThat(a.run(r3).endReason()).isEqualTo(EndReason.INCIDENT_MERGED);
    assertThat(handlers.calls(r2)).isEmpty();
    assertThat(handlers.calls(r3)).isEmpty();
  }

  private UUID i4;
  private UUID r4;
  private UUID r5;

  @Test
  @Order(10)
  @DisplayName("DM-R-22 a reopen start cancels the unfinished live run in the same transaction")
  void reopenStart() {
    handlers.reset();
    handlers.answer(StepType.RECOVERY_CHECK, call -> waiting(WakeEvent.SOURCE_HEALTH_CHANGED));
    i4 = a.openIncident(CLOCK, "nowhere-r", "i-1", ALERTNAME);
    String content = TestContents.linear("recovery_check");
    r4 = a.start(i4, content);
    a.awaitRunState(r4, RunState.WAITING);
    r5 =
        a.engine
            .startLiveRun(a.tenant, EngineFixture.WORKFLOWS, i4, 2, Ids.newId(), content, true)
            .orElseThrow();
    RunView cancelled = a.run(r4);
    assertThat(cancelled.state()).isEqualTo(RunState.CANCELLED);
    assertThat(cancelled.endReason()).isEqualTo(EndReason.INCIDENT_REOPENED);
    StepExecutionView step = a.execution(r4, "s-1");
    assertThat(step.state()).isEqualTo(StepExecutionState.CANCELLED);
    assertThat(handlers.cancels().stream().filter(c -> c.runId().equals(r4)).toList())
        .singleElement()
        .satisfies(
            c -> {
              assertThat(c.stepExecutionId()).isEqualTo(step.id());
              assertThat(c.stepType()).isEqualTo(StepType.RECOVERY_CHECK);
            });
    RunView reopened = a.awaitRunState(r5, RunState.WAITING);
    assertThat(reopened.episode()).isEqualTo(2);
    assertThat(reopened.waitReason()).isEqualTo(WaitReason.STEP_WAITING);
    assertThat(a.execution(r5, "s-1").state()).isEqualTo(StepExecutionState.WAITING);
  }

  private static Waiting waiting(WakeEvent... events) {
    return new Waiting(Set.of(events), null, "{}");
  }

  @Test
  @Order(11)
  @DisplayName(
      "DM-E50-R1 at most one live run per incident episode and one unfinished per incident")
  void oneRunPerEpisode() {
    String content = TestContents.linear("recovery_check");
    int before = a.engine.runs(a.tenant, i4).size();
    assertThat(
            a.engine.startLiveRun(
                a.tenant, EngineFixture.WORKFLOWS, i4, 2, Ids.newId(), content, false))
        .isEmpty();
    assertThat(
            a.engine.startLiveRun(
                a.tenant, EngineFixture.WORKFLOWS, i4, 2, Ids.newId(), content, true))
        .isEmpty();
    assertThat(a.run(r5).state()).isEqualTo(RunState.WAITING);
    assertThatThrownBy(
            () ->
                a.engine.startLiveRun(
                    a.tenant, EngineFixture.WORKFLOWS, i4, 3, Ids.newId(), content, false))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("the incident has an unfinished live run");
    assertThatThrownBy(
            () ->
                a.engine.startLiveRun(
                    a.tenant, Actor.module("vigil-correlation"), i4, 3, Ids.newId(), content, true))
        .isInstanceOf(IllegalArgumentException.class);
    String twoTriggers =
        TestContents.with(
            content,
            root -> {
              ((tools.jackson.databind.node.ArrayNode) root.get("steps"))
                  .addObject()
                  .put("step_id", "s-trigger-2")
                  .put("type", "trigger")
                  .put("label", "Trigger 2");
              return root;
            });
    assertThatThrownBy(
            () ->
                a.engine.startLiveRun(
                    a.tenant, EngineFixture.WORKFLOWS, i4, 3, Ids.newId(), twoTriggers, true))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(a.engine.runs(a.tenant, i4)).hasSize(before);
    assertThat(a.engine.hasLiveRun(a.tenant, i4, 2)).isTrue();
    assertThat(a.engine.hasLiveRun(a.tenant, i4, 3)).isFalse();
  }

  // One run of linear("ticket") for DM-E-50, DM-E-51, DM-SM-07, DM-SM-08, AR-R-21.

  private UUID ticketIncident;
  private UUID ticketRun;
  private UUID ticketVersion;
  private String ticketContent;

  @Test
  @Order(12)
  @DisplayName("DM-E-50 a live run with its fields; the test-mode fields stay empty")
  void workflowRun() {
    handlers.reset();
    ticketIncident = openIncident();
    ticketContent = TestContents.linear("ticket");
    ticketVersion = Ids.newId();
    Instant startedAt = CLOCK.instant();
    ticketRun =
        a.engine
            .startLiveRun(
                a.tenant,
                EngineFixture.WORKFLOWS,
                ticketIncident,
                1,
                ticketVersion,
                ticketContent,
                false)
            .orElseThrow();
    RunView run = a.awaitRunState(ticketRun, RunState.FINISHED);
    assertThat(run.incidentId()).isEqualTo(ticketIncident);
    assertThat(run.mode()).isEqualTo(RunMode.LIVE);
    assertThat(run.episode()).isEqualTo(1);
    assertThat(run.workflowVersionId()).isEqualTo(ticketVersion);
    assertThat(tree(run.contentJson())).isEqualTo(tree(ticketContent));
    assertThat(run.endReason()).isEqualTo(EndReason.PATH_ENDED);
    assertThat(run.endDetail()).isNull();
    assertThat(run.waitReason()).isNull();
    assertThat(run.wakePending()).isFalse();
    assertThat(run.startedAt()).isEqualTo(startedAt);
    assertThat(run.finishedAt()).isEqualTo(startedAt);
    assertThat(run.currentStepExecutionId()).isEqualTo(a.execution(ticketRun, "s-1").id());
    assertThat(run.nextStepId()).isNull();
    assertThat(run.draftId()).isNull();
    assertThat(run.draftRevision()).isNull();
    assertThat(run.testReplayId()).isNull();
    assertThat(run.simulatedNow()).isNull();
  }

  @Test
  @Order(13)
  @DisplayName("DM-E-51 the step executions of a run")
  void stepExecutions() {
    List<StepExecutionView> executions = a.executions(ticketRun);
    assertThat(executions).hasSize(2);
    StepExecutionView trigger = executions.get(0);
    assertThat(trigger.stepId()).isEqualTo("s-trigger");
    assertThat(trigger.state()).isEqualTo(StepExecutionState.COMPLETED);
    assertThat(trigger.namedOutput()).isEqualTo("next");
    StepExecutionView ticket = executions.get(1);
    assertThat(ticket.runId()).isEqualTo(ticketRun);
    assertThat(ticket.stepId()).isEqualTo("s-1");
    assertThat(ticket.stepType()).isEqualTo(StepType.TICKET);
    assertThat(ticket.state()).isEqualTo(StepExecutionState.COMPLETED);
    assertThat(ticket.namedOutput()).isEqualTo("done");
    assertThat(ticket.visit()).isEqualTo(1);
    assertThat(ticket.evaluationCount()).isEqualTo(1);
    assertThat(ticket.failedAttempts()).isZero();
    assertThat(ticket.resultRef()).isNull();
    assertThat(ticket.retryDueAt()).isNull();
    assertThat(ticket.wakeEvents()).isNull();
    assertThat(ticket.timerId()).isNull();
    assertThat(ticket.finishedAt()).isNotNull();
  }

  @Test
  @Order(14)
  @DisplayName("DM-SM-07 workflow run transitions T1 (start) and T4 (path ended)")
  void runTransitions() {
    assertThat(events(RunStarted.class, ticketRun)).hasSize(1);
    assertThat(a.run(ticketRun).state()).isEqualTo(RunState.FINISHED);
    assertThat(a.run(ticketRun).endReason()).isEqualTo(EndReason.PATH_ENDED);
    assertThat(events(RunFinished.class, ticketRun)).hasSize(1);
  }

  @Test
  @Order(15)
  @DisplayName("DM-SM-08 step execution transitions T1 (start) and T4 (completed)")
  void stepTransitions() {
    UUID ticket = a.execution(ticketRun, "s-1").id();
    assertThat(
            events(StepChanged.class, ticketRun).stream()
                .filter(e -> e.stepExecutionId().equals(ticket))
                .map(StepChanged::state))
        .containsExactly(StepExecutionState.RUNNING, StepExecutionState.COMPLETED);
  }

  @Test
  @Order(16)
  @DisplayName("AR-R-21 the StepCall of a handler call")
  void stepCall() {
    StepCall call = handlers.calls(ticketRun).getFirst().call;
    assertThat(handlers.calls(ticketRun)).hasSize(1);
    StepExecutionView ticket = a.execution(ticketRun, "s-1");
    assertThat(call.tenantId()).isEqualTo(a.tenant);
    assertThat(call.runId()).isEqualTo(ticketRun);
    assertThat(call.incidentId()).isEqualTo(ticketIncident);
    assertThat(call.stepExecutionId()).isEqualTo(ticket.id());
    assertThat(call.stepId()).isEqualTo(new Key("s-1"));
    assertThat(call.stepType()).isEqualTo(StepType.TICKET);
    assertThat(tree(call.parameters())).isEqualTo(tree("{\"operation\":\"create\"}"));
    assertThat(call.evaluationCount()).isEqualTo(1);
    assertThat(call.stepState()).isNull();
    assertThat(call.wakeReason().value()).isEqualTo("start");
    assertThat(call.recoveryCheckKey()).isNull();
    assertThat(call.earlierApprovers()).isEmpty();
  }

  private void assertThreads() {
    assertThat(handlers.calls()).isNotEmpty();
    for (TestHandlers.Call call : handlers.calls()) {
      assertThat(call.virtualThread).isTrue();
      assertThat(call.thread).isEqualTo("run-" + call.call.runId());
      assertThat(call.transactionActive).isFalse();
    }
  }

  @Test
  @Order(17)
  @DisplayName("AR-R-27 each run's calls on its own virtual thread, with no transaction open")
  void virtualThreads() {
    TestHandlers.Call call = handlers.calls(ticketRun).getFirst();
    assertThat(call.thread).isEqualTo("run-" + ticketRun);
    assertThreads();
  }

  // Waiting steps.

  private UUID waitingRun;
  private Instant waitingDue;
  private RunView waitingView;
  private StepExecutionView waitingStep;

  @Test
  @Order(18)
  @DisplayName("PM-S-12 a handler answers a named output or waiting with its wake conditions")
  void waitingAnswer() {
    handlers.reset();
    waitingDue = null;
    handlers.answer(
        StepType.RECOVERY_CHECK,
        call -> {
          if (call.wakeReason().value().equals("start")) {
            waitingDue = CLOCK.instant().plusSeconds(300);
            return new Waiting(Set.of(WakeEvent.ALERT_STATE_CHANGED), waitingDue, "{\"n\":1}");
          }
          return TestHandlers.completed("recovered");
        });
    waitingRun = a.start(openIncident(), TestContents.linear("recovery_check"));
    waitingView = a.awaitRunState(waitingRun, RunState.WAITING);
    waitingStep = a.execution(waitingRun, "s-1");
    assertThat(waitingView.waitReason()).isEqualTo(WaitReason.STEP_WAITING);
    assertThat(waitingStep.state()).isEqualTo(StepExecutionState.WAITING);
    assertThat(waitingStep.wakeEvents()).containsExactly(WakeEvent.ALERT_STATE_CHANGED);
    assertThat(waitingStep.namedOutput()).isNull();
    assertThat(timers(waitingRun))
        .singleElement()
        .satisfies(
            timer -> {
              assertThat(timer.get("id")).isEqualTo(waitingStep.timerId());
              assertThat(timer.get("state")).isEqualTo("pending");
              assertThat(((java.sql.Timestamp) timer.get("due_at")).toInstant())
                  .isEqualTo(waitingDue);
            });
    deliver(
        () ->
            intakeListener()
                .onAlertStateChanged(
                    new AlertStateChanged(a.tenant, Ids.newId(), AlertStatus.RESOLVED)));
    a.awaitRunState(waitingRun, RunState.FINISHED);
    assertThat(a.execution(waitingRun, "s-1").namedOutput()).isEqualTo("recovered");
    assertThat(timers(waitingRun))
        .singleElement()
        .satisfies(timer -> assertThat(timer.get("state")).isEqualTo("cancelled"));
  }

  @Test
  @Order(19)
  @DisplayName("PM-S-13 the module is asked again when a listed event arrives")
  void askedAgain() {
    List<TestHandlers.Call> calls = handlers.calls(waitingRun);
    assertThat(calls).extracting(call -> call.operation).containsExactly("start", "evaluate");
    assertThat(calls.get(1).call.wakeReason().value()).isEqualTo("event:alert_state_changed");
  }

  @Test
  @Order(20)
  @DisplayName("PM-S-14 the step state kept between evaluations")
  void stepState() {
    assertThat(waitingStep.stepState()).isNotNull();
    assertThat(tree(waitingStep.stepState())).isEqualTo(tree("{\"n\":1}"));
    StepCall evaluate = handlers.calls(waitingRun).get(1).call;
    assertThat(tree(evaluate.stepState())).isEqualTo(tree("{\"n\":1}"));
  }

  @Test
  @Order(21)
  @DisplayName("DM-S-12 waiting is a state of the step execution, not a named output")
  void waitingIsState() {
    assertThat(waitingStep.state()).isEqualTo(StepExecutionState.WAITING);
    assertThat(waitingStep.namedOutput()).isNull();
    assertThat(
            events(StepChanged.class, waitingRun).stream()
                .filter(e -> e.stepExecutionId().equals(waitingStep.id()))
                .map(StepChanged::state))
        .containsExactly(
            StepExecutionState.RUNNING,
            StepExecutionState.WAITING,
            StepExecutionState.RUNNING,
            StepExecutionState.COMPLETED);
  }

  private UUID timerRun;
  private UUID rewaitRun;

  @Test
  @Order(22)
  @DisplayName("DM-E-52 durable timers: one pending timer per step execution, fired when due")
  void durableTimer() {
    handlers.reset();
    handlers.answer(
        StepType.RECOVERY_CHECK,
        call ->
            call.wakeReason().value().equals("start")
                ? new Waiting(
                    Set.of(WakeEvent.ALERT_STATE_CHANGED), CLOCK.instant().plusSeconds(300), "{}")
                : TestHandlers.completed("not_recovered"));
    timerRun = a.start(openIncident(), TestContents.linear("recovery_check"));
    a.awaitRunState(timerRun, RunState.WAITING);
    CLOCK.advance(Duration.ofSeconds(301));
    a.awaitRunState(timerRun, RunState.FINISHED);
    assertThat(timers(timerRun))
        .singleElement()
        .satisfies(timer -> assertThat(timer.get("state")).isEqualTo("fired"));
    List<TestHandlers.Call> calls = handlers.calls(timerRun);
    assertThat(calls).extracting(call -> call.operation).containsExactly("start", "evaluate");
    assertThat(calls.get(1).call.wakeReason().value()).isEqualTo("timer");

    handlers.answer(
        StepType.RECOVERY_CHECK,
        call ->
            new Waiting(
                Set.of(WakeEvent.ALERT_STATE_CHANGED),
                CLOCK.instant().plusSeconds(call.evaluationCount() * 300),
                "{}"));
    rewaitRun = a.start(openIncident(), TestContents.linear("recovery_check"));
    a.awaitRunState(rewaitRun, RunState.WAITING);
    UUID first = a.execution(rewaitRun, "s-1").timerId();
    deliver(
        () ->
            intakeListener()
                .onAlertStateChanged(
                    new AlertStateChanged(a.tenant, Ids.newId(), AlertStatus.FIRING)));
    EngineFixture.await(() -> a.execution(rewaitRun, "s-1").evaluationCount() == 2);
    a.awaitRunState(rewaitRun, RunState.WAITING);
    List<Map<String, Object>> timers = timers(rewaitRun);
    assertThat(timers).hasSize(2);
    assertThat(timers.get(0).get("id")).isEqualTo(first);
    assertThat(timers.get(0).get("state")).isEqualTo("cancelled");
    assertThat(timers.get(1).get("state")).isEqualTo("pending");
    assertThat(timers.get(1).get("id")).isEqualTo(a.execution(rewaitRun, "s-1").timerId());
  }

  @Test
  @Order(23)
  @DisplayName("DM-SM-09 durable timer transitions: pending, fired, cancelled")
  void timerTransitions() {
    assertThat(timers(timerRun)).extracting(t -> t.get("state")).containsExactly("fired");
    assertThat(timers(waitingRun)).extracting(t -> t.get("state")).containsExactly("cancelled");
    assertThat(timers(rewaitRun))
        .extracting(t -> t.get("state"))
        .containsExactly("cancelled", "pending");
  }

  @Test
  @Order(24)
  @DisplayName("PM-M12-05 long-running steps with durable timers held by the engine")
  void longRunning() {
    assertThat(a.execution(timerRun, "s-1").namedOutput()).isEqualTo("not_recovered");
    assertThat(handlers.calls(timerRun).get(1).call.wakeReason().value()).isEqualTo("timer");
  }

  private UUID durationRun;
  private UUID eventRun;

  @Test
  @Order(25)
  @DisplayName("DM-S-11 the Wait/timer step in the engine, by duration and until an event")
  void waitStep() {
    handlers.reset();
    durationRun = a.start(openIncident(), TestContents.linear("wait"));
    a.awaitRunState(durationRun, RunState.WAITING);
    StepExecutionView waiting = a.execution(durationRun, "s-1");
    assertThat(timers(durationRun))
        .singleElement()
        .satisfies(
            timer ->
                assertThat(((java.sql.Timestamp) timer.get("due_at")).toInstant())
                    .isEqualTo(waiting.startedAt().plusSeconds(60)));
    CLOCK.advance(Duration.ofSeconds(61));
    a.awaitRunState(durationRun, RunState.FINISHED);
    assertThat(a.execution(durationRun, "s-1").namedOutput()).isEqualTo("timed_out");
    assertThat(a.execution(durationRun, "s-1").evaluationCount()).isEqualTo(2);

    UUID incident = openIncident();
    eventRun = a.start(incident, TestContents.waitUntilEvent(60));
    a.awaitRunState(eventRun, RunState.WAITING);
    assertThat(a.execution(eventRun, "s-1").wakeEvents())
        .containsExactly(WakeEvent.INCIDENT_ACKNOWLEDGED);
    deliver(
        () -> incidentListener().onAcknowledged(new IncidentAcknowledged(a.tenant, incident, 1)));
    EngineFixture.await(() -> a.execution(eventRun, "s-1").evaluationCount() == 2);
    a.awaitRunState(eventRun, RunState.WAITING);
    CLOCK.advance(Duration.ofSeconds(61));
    a.awaitRunState(eventRun, RunState.FINISHED);
    assertThat(a.execution(eventRun, "s-1").namedOutput()).isEqualTo("timed_out");
  }

  @Test
  @Order(26)
  @DisplayName("PM-S-11 Wait/timer: the outputs event_received and timed_out")
  void waitOutputs() {
    assertThat(a.outputs(durationRun)).containsExactly("next", "timed_out");
    assertThat(a.outputs(eventRun)).containsExactly("next", "timed_out");
    assertThat(handlers.calls(durationRun)).isEmpty();
    assertThat(handlers.calls(eventRun)).isEmpty();
  }

  @Test
  @Order(27)
  @DisplayName("DM-R-64 a step evaluated again keeps its step execution")
  void sameExecution() {
    for (UUID run : List.of(waitingRun, timerRun, rewaitRun)) {
      StepExecutionView step = a.execution(run, "s-1");
      List<TestHandlers.Call> calls = handlers.calls(run);
      assertThat(calls).allSatisfy(c -> assertThat(c.call.stepExecutionId()).isEqualTo(step.id()));
      for (int n = 0; n < calls.size(); n++) {
        assertThat(calls.get(n).call.evaluationCount()).isEqualTo(n + 1);
      }
      assertThat((long) calls.size()).isBetween(2L, step.evaluationCount());
      assertThat(a.executions(run)).hasSize(2);
    }
    assertThat(handlers.calls(waitingRun)).hasSize(2);
    assertThat(a.execution(waitingRun, "s-1").evaluationCount()).isEqualTo(2);
    assertThat(a.execution(eventRun, "s-1").evaluationCount()).isEqualTo(3);
    assertThat(a.executions(eventRun)).hasSize(2);
  }

  private UUID failingRun;

  private static List<JsonNode> lines(CapturedOutput output, String text) {
    return LogLines.application(output.getAll()).stream()
        .filter(line -> line.path("message").asString("").equals(text))
        .toList();
  }

  @Test
  @Order(28)
  @DisplayName("AR-R-22 a failed call is retried as a new call; the run fails after 4 attempts")
  void retries(CapturedOutput output) {
    handlers.reset();
    handlers.failAlways(StepType.TICKET);
    failingRun = a.start(openIncident(), TestContents.linear("ticket"));
    EngineFixture.await(
        () ->
            a.steps(failingRun).contains("s-1")
                && a.execution(failingRun, "s-1").failedAttempts() == 1);
    StepExecutionView first = a.execution(failingRun, "s-1");
    assertThat(first.retryDueAt()).isEqualTo(CLOCK.instant().plusSeconds(5));
    CLOCK.advance(Duration.ofSeconds(5));
    a.handOff(failingRun);
    EngineFixture.await(() -> a.execution(failingRun, "s-1").failedAttempts() == 2);
    assertThat(a.execution(failingRun, "s-1").retryDueAt())
        .isEqualTo(CLOCK.instant().plusSeconds(15));
    CLOCK.advance(Duration.ofSeconds(15));
    a.handOff(failingRun);
    EngineFixture.await(() -> a.execution(failingRun, "s-1").failedAttempts() == 3);
    assertThat(a.execution(failingRun, "s-1").retryDueAt())
        .isEqualTo(CLOCK.instant().plusSeconds(45));
    CLOCK.advance(Duration.ofSeconds(45));
    a.handOff(failingRun);
    RunView failed = a.awaitRunState(failingRun, RunState.FAILED);
    assertThat(failed.endReason()).isEqualTo(EndReason.STEP_FAILED_REPEATEDLY);
    assertThat(failed.endDetail()).isEqualTo("step 'Step 1' did not answer after 4 attempts");
    assertThat(a.execution(failingRun, "s-1").state()).isEqualTo(StepExecutionState.CANCELLED);
    List<TestHandlers.Call> calls = handlers.calls(failingRun);
    assertThat(calls).hasSize(4);
    assertThat(calls.stream().map(c -> c.call.wakeReason().value()))
        .containsExactly("start", "retry", "retry", "retry");
    assertThat(calls.stream().map(c -> c.call.evaluationCount())).containsExactly(1L, 2L, 3L, 4L);
    for (int n = 1; n <= 4; n++) {
      String text =
          "step s-1 of run "
              + failingRun
              + " for tenant "
              + a.tenant.value()
              + " failed (attempt "
              + n
              + ")";
      assertThat(lines(output, text))
          .singleElement()
          .satisfies(
              line -> assertThat(line.path("log").path("level").asString()).isEqualTo("ERROR"));
    }
    assertThat(handlers.cancels()).noneMatch(c -> c.runId().equals(failingRun));
  }

  @Test
  @Order(29)
  @DisplayName("AR-TM-05 retries after 5 s, 15 s and 45 s; then the run fails")
  void retryWaits() {
    List<StepChanged> changes = events(StepChanged.class, failingRun);
    assertThat(changes.getLast().state()).isEqualTo(StepExecutionState.CANCELLED);
    assertThat(events(RunFinished.class, failingRun)).hasSize(1);
    assertThat(a.execution(failingRun, "s-1").evaluationCount()).isEqualTo(4);
    assertThat(a.run(failingRun).state()).isEqualTo(RunState.FAILED);
  }

  private UUID refusedRun;
  private UUID guardedRun;
  private UUID noRecoveryRun;

  @Test
  @Order(30)
  @DisplayName("PM-M12-06 the Action step guard refuses without asking vigil-actions")
  void guard() {
    handlers.reset();
    handlers.proposal(run -> Optional.empty());
    refusedRun =
        a.start(openIncident(), TestContents.linear("approval", "action", "recovery_check"));
    a.awaitRunState(refusedRun, RunState.FINISHED);
    StepExecutionView refused = a.execution(refusedRun, "s-2");
    assertThat(refused.state()).isEqualTo(StepExecutionState.COMPLETED);
    assertThat(refused.namedOutput()).isEqualTo("failed");
    assertThat(refused.refusalReason()).isEqualTo("no action proposal in this run");
    assertThat(handlers.calls(refusedRun)).noneMatch(c -> c.call.stepType() == StepType.ACTION);
    assertThat(a.steps(refusedRun)).containsExactly("s-trigger", "s-1", "s-2", "s-3");

    handlers.reset();
    guardedRun =
        a.start(openIncident(), TestContents.linear("approval", "action", "recovery_check"));
    a.awaitRunState(guardedRun, RunState.FINISHED);
    assertThat(a.execution(guardedRun, "s-2").namedOutput()).isEqualTo("succeeded");
    assertThat(a.execution(guardedRun, "s-2").refusalReason()).isNull();
    assertThat(
            handlers.calls(guardedRun).stream().filter(c -> c.call.stepType() == StepType.ACTION))
        .hasSize(1);

    noRecoveryRun = a.start(openIncident(), TestContents.linear("approval", "action"));
    a.awaitRunState(noRecoveryRun, RunState.FINISHED);
    assertThat(a.execution(noRecoveryRun, "s-2").namedOutput()).isEqualTo("failed");
    assertThat(a.execution(noRecoveryRun, "s-2").refusalReason())
        .isEqualTo("an output of this step does not reach a Recovery check step");
    assertThat(handlers.calls(noRecoveryRun)).noneMatch(c -> c.call.stepType() == StepType.ACTION);
  }

  @Test
  @Order(31)
  @DisplayName("SL-B-04 no Action step without the approval path and a Recovery check after it")
  void engineGuard() {
    assertThat(a.execution(refusedRun, "s-2").refusalReason()).isNotNull();
    assertThat(a.execution(noRecoveryRun, "s-2").refusalReason()).isNotNull();
    assertThat(a.execution(guardedRun, "s-3").namedOutput()).isEqualTo("recovered");
  }

  @Test
  @Order(32)
  @DisplayName("PM-M12-07 the earlier approvers of the run passed to the Approval step")
  void earlierApprovers() {
    handlers.reset();
    UUID u1 = Ids.newId();
    UUID u2 = Ids.newId();
    handlers.earlierApprovers(run -> List.of(u1, u2));
    UUID run = a.start(openIncident(), TestContents.linear("approval", "action", "recovery_check"));
    a.awaitRunState(run, RunState.FINISHED);
    StepCall approval = callOf(run, StepType.APPROVAL);
    assertThat(approval.earlierApprovers()).containsExactly(u1, u2);
    assertThat(callOf(run, StepType.RECOVERY_CHECK).recoveryCheckKey()).isNull();

    handlers.reset();
    handlers.latestRecoveryCheckKey(r -> Optional.of(new Key("rc-x")));
    UUID keyed =
        a.start(openIncident(), TestContents.linear("approval", "action", "recovery_check"));
    a.awaitRunState(keyed, RunState.FINISHED);
    assertThat(callOf(keyed, StepType.RECOVERY_CHECK).recoveryCheckKey())
        .isEqualTo(new Key("rc-x"));
    assertThat(callOf(keyed, StepType.APPROVAL).earlierApprovers()).isEmpty();
  }

  private StepCall callOf(UUID run, StepType type) {
    return handlers.calls(run).stream()
        .filter(c -> c.call.stepType() == type)
        .findFirst()
        .orElseThrow()
        .call;
  }

  @Test
  @Order(33)
  @DisplayName("AR-R-23 the engine timer poll of the active tenant")
  void timerPoll() {
    assertThat(EngineFixture.isScheduled(app, a.tenant)).isTrue();
    assertThreads();
  }
}
