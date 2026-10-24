package vigil.engine;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
import vigil.engine.api.RunState;
import vigil.engine.api.RunView;
import vigil.engine.api.StepExecutionView;
import vigil.engine.api.WaitReason;
import vigil.incidents.api.IncidentService;
import vigil.intake.IntakeFixture;
import vigil.shared.StepCall.StepType;
import vigil.shared.TenantId;
import vigil.shared.Waiting;
import vigil.shared.Waiting.WakeEvent;
import vigil.testsupport.MutableClock;
import vigil.testsupport.TestSecrets;
import vigil.testsupport.VigilDatabase;
import vigil.testsupport.VigilTestApp;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EngineRestartIT {

  private static final MutableClock CLOCK = new MutableClock(Instant.parse("2026-10-24T10:00:00Z"));
  private static final String HOST = "a.engine-restart.test";
  private static final String ALERTNAME = "NodeFilesystemFull";

  /** The test clock, the same for both applications. */
  static class TestBeans {

    @Bean
    @Primary
    Clock testClock() {
      return CLOCK;
    }
  }

  private WireMockServer wiremock;
  private VigilDatabase database;
  private JdbcClient postgres;
  private TestSecrets secrets;
  private VigilTestApp app;
  private EngineFixture a;
  private TestHandlers handlers;
  private TenantId tenant;
  private UUID r1;
  private UUID r2;
  private UUID r3;
  private UUID r4;
  private UUID r5;
  private UUID r6;
  private UUID i5;
  private List<String> stepsBefore;

  @BeforeAll
  void start() {
    wiremock = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort());
    wiremock.start();
    IntakeFixture.stub(wiremock);
    database = VigilDatabase.fresh();
    postgres = database.postgres();
    secrets = IntakeFixture.secrets(database, "");
    startApp();
  }

  private void startApp() {
    app =
        VigilTestApp.start(
            database, secrets.folder(), List.of(), TestBeans.class, TestHandlers.Beans.class);
    app.awaitStarted();
    handlers = app.context().getBean(TestHandlers.class);
  }

  @AfterAll
  void stop() {
    app.close();
    wiremock.stop();
  }

  private UUID open(int n) {
    return a.openIncident(CLOCK, "nowhere-r" + n, "i-1", ALERTNAME);
  }

  private List<String> pendingTimers(UUID run) {
    return postgres
        .sql(
            "SELECT state FROM engine.durable_timer WHERE tenant_id = :t AND run_id = :r"
                + " AND state = 'pending'")
        .param("t", tenant.value())
        .param("r", run)
        .query(String.class)
        .list();
  }

  @Test
  @Order(1)
  @DisplayName("AR-R-24 restart step (4): every run continues as its state says (AR-Q-11 (c))")
  void restart() {
    a =
        EngineFixture.create(
            app, secrets, "Tenant A", HOST, "http://127.0.0.1:" + wiremock.port(), "");
    tenant = a.tenant;
    handlers.reset();
    UUID i1 = open(1);
    UUID i2 = open(2);
    UUID i3 = open(3);
    UUID i4 = open(4);
    UUID i6 = open(6);
    handlers.block(StepType.DIAGNOSTIC);
    r1 = a.start(i1, TestContents.linear("diagnostic"));
    handlers.awaitBlocked(StepType.DIAGNOSTIC, r1);
    handlers.block(StepType.ACTION);
    r2 = a.start(i2, TestContents.linear("approval", "action", "recovery_check"));
    handlers.awaitBlocked(StepType.ACTION, r2);
    handlers.failAlways(StepType.TICKET);
    r3 = a.start(i3, TestContents.linear("ticket"));
    EngineFixture.await(
        () -> a.steps(r3).contains("s-1") && a.execution(r3, "s-1").failedAttempts() == 1);
    handlers.answer(
        StepType.RECOVERY_CHECK,
        call -> new Waiting(Set.of(WakeEvent.SOURCE_HEALTH_CHANGED), null, "{}"));
    r4 = a.start(i4, TestContents.linear("recovery_check"));
    a.awaitRunState(r4, RunState.WAITING);
    r6 = a.start(i6, TestContents.linear("wait"));
    a.awaitRunState(r6, RunState.WAITING);
    i5 = a.formingIncident("nowhere-r5", "i-1", ALERTNAME);
    r5 = a.start(i5, TestContents.linear("ticket"));
    RunView forming = a.awaitRunState(r5, RunState.WAITING);
    assertThat(forming.waitReason()).isEqualTo(WaitReason.INCIDENT_FORMING);
    stepsBefore =
        List.of(r1, r2, r3, r4, r5, r6).stream()
            .map(run -> String.join(",", a.steps(run)))
            .toList();

    TestHandlers first = handlers;
    app.close();
    first.release(StepType.DIAGNOSTIC);
    first.release(StepType.ACTION);
    CLOCK.advance(Duration.ofSeconds(61));
    startApp();
    a = EngineFixture.of(app, secrets, tenant, "");

    a.awaitRunState(r1, RunState.FINISHED);
    assertThat(handlers.calls(r1))
        .singleElement()
        .satisfies(
            call -> {
              assertThat(call.operation).isEqualTo("start");
              assertThat(call.call.wakeReason().value()).isEqualTo("resume");
              assertThat(call.call.evaluationCount()).isEqualTo(2);
            });
    assertThat(a.execution(r1, "s-1").evaluationCount()).isEqualTo(2);

    a.awaitRunState(r3, RunState.FINISHED);
    assertThat(handlers.calls(r3))
        .singleElement()
        .satisfies(call -> assertThat(call.call.wakeReason().value()).isEqualTo("resume"));

    a.awaitRunState(r4, RunState.FINISHED);
    assertThat(handlers.calls(r4))
        .singleElement()
        .satisfies(
            call -> {
              assertThat(call.operation).isEqualTo("evaluate");
              assertThat(call.call.wakeReason().value()).isEqualTo("restart");
            });

    RunView waited = a.awaitRunState(r6, RunState.FINISHED);
    assertThat(waited.wakeReason()).isEqualTo("restart");
    assertThat(a.execution(r6, "s-1").namedOutput()).isEqualTo("timed_out");

    a.awaitRunState(r5, RunState.FINISHED);
    Instant stableAt =
        app.context().getBean(IncidentService.class).incident(tenant, i5).orElseThrow().stableAt();
    assertThat(stableAt).isNotNull();
    assertThat(a.execution(r5, "s-1").startedAt()).isAfterOrEqualTo(stableAt);
    a.awaitRunState(r2, RunState.FINISHED);
  }

  @Test
  @Order(2)
  @DisplayName("PM-M12-04 after a restart each run resumes from its checkpoint")
  void checkpoints() {
    List<String> before = stepsBefore;
    assertThat(before)
        .containsExactly(
            "s-trigger,s-1",
            "s-trigger,s-1,s-2",
            "s-trigger,s-1",
            "s-trigger,s-1",
            "s-trigger",
            "s-trigger,s-1");
    assertThat(a.steps(r1)).containsExactly("s-trigger", "s-1");
    assertThat(a.steps(r2)).containsExactly("s-trigger", "s-1", "s-2", "s-3");
    assertThat(a.steps(r3)).containsExactly("s-trigger", "s-1");
    assertThat(a.steps(r4)).containsExactly("s-trigger", "s-1");
    assertThat(a.steps(r5)).containsExactly("s-trigger", "s-1");
    assertThat(a.steps(r6)).containsExactly("s-trigger", "s-1");
    assertThat(handlers.calls(r2)).noneMatch(call -> call.call.stepId().value().equals("s-1"));
  }

  @Test
  @Order(3)
  @DisplayName(
      "PM-M12-10 an Action step without a named output is reconciled before the run goes on")
  void reconciled() {
    List<TestHandlers.Call> actions =
        handlers.calls(r2).stream()
            .filter(call -> call.call.stepType() == StepType.ACTION)
            .toList();
    assertThat(actions)
        .singleElement()
        .satisfies(
            call -> {
              assertThat(call.operation).isEqualTo("reconcile");
              assertThat(call.call.wakeReason().value()).isEqualTo("resume");
              assertThat(call.call.stepId().value()).isEqualTo("s-2");
            });
    StepExecutionView action = a.execution(r2, "s-2");
    assertThat(action.namedOutput()).isEqualTo("succeeded");
    assertThat(action.refusalReason()).isNull();
    assertThat(a.execution(r2, "s-3").startedAt()).isAfterOrEqualTo(action.finishedAt());
  }

  @Test
  @Order(4)
  @DisplayName("SL-B-11 durable runs resume after a restart; reconciliation before any retry")
  void durableRuns() {
    for (UUID run : List.of(r1, r2, r3, r4, r5, r6)) {
      assertThat(a.run(run).state()).isEqualTo(RunState.FINISHED);
    }
    List<TestHandlers.Call> calls = handlers.calls(r2);
    assertThat(calls.getFirst().operation).isEqualTo("reconcile");
    assertThat(calls).extracting(call -> call.operation).doesNotContain("evaluate");
  }

  @Test
  @Order(5)
  @DisplayName("DM-R-66 every waiting step is evaluated once after a restart")
  void waitingEvaluated() {
    assertThat(handlers.calls(r4)).hasSize(1);
    assertThat(a.execution(r4, "s-1").namedOutput()).isEqualTo("recovered");
    assertThat(a.run(r4).wakeReason()).isEqualTo("restart");
  }

  @Test
  @Order(6)
  @DisplayName("DM-E52-R1 a timer overdue at restart fires")
  void overdueTimer() {
    assertThat(a.run(r6).wakeReason()).isEqualTo("restart");
    assertThat(pendingTimers(r6)).isEmpty();
    List<Map<String, Object>> timers =
        postgres
            .sql("SELECT state FROM engine.durable_timer WHERE tenant_id = :t AND run_id = :r")
            .param("t", tenant.value())
            .param("r", r6)
            .query()
            .listOfRows();
    assertThat(timers)
        .singleElement()
        .satisfies(t -> assertThat(t.get("state")).isEqualTo("fired"));
  }
}
