package vigil.engine;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
import tools.jackson.databind.JsonNode;
import vigil.actions.api.ActionsStepHandler;
import vigil.engine.api.EndReason;
import vigil.engine.api.RunState;
import vigil.engine.api.RunView;
import vigil.intake.IntakeFixture;
import vigil.shared.Ids;
import vigil.shared.StepAnswer;
import vigil.shared.StepCall;
import vigil.shared.StepCall.StepType;
import vigil.shared.StepCancel;
import vigil.testsupport.LogLines;
import vigil.testsupport.MutableClock;
import vigil.testsupport.TestSecrets;
import vigil.testsupport.VigilDatabase;
import vigil.testsupport.VigilTestApp;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@ExtendWith(OutputCaptureExtension.class)
class EngineAbsentHandlersIT {

  private static final MutableClock CLOCK = new MutableClock(Instant.parse("2026-10-24T10:00:00Z"));
  private static final String ALERTNAME = "NodeFilesystemFull";
  private static final List<StepCall> ACTION_CALLS = new CopyOnWriteArrayList<>();

  /** The test clock. */
  static class TestBeans {

    @Bean
    @Primary
    Clock testClock() {
      return CLOCK;
    }
  }

  /** The only handler of the second application: Approval steps approve, Actions succeed. */
  static class ActionsBeans {

    @Bean
    ActionsStepHandler testActionsHandler() {
      return new ActionsStepHandler() {
        public StepAnswer start(StepCall call) {
          if (call.stepType() == StepType.ACTION) {
            ACTION_CALLS.add(call);
            return TestHandlers.completed("succeeded");
          }
          return TestHandlers.completed("approved");
        }

        public StepAnswer evaluate(StepCall call) {
          return start(call);
        }

        public void cancel(StepCancel cancel) {}
      };
    }
  }

  private WireMockServer wiremock;
  private VigilTestApp app;

  @BeforeAll
  void start() {
    wiremock = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort());
    wiremock.start();
    IntakeFixture.stub(wiremock);
  }

  @AfterAll
  void stop() {
    app.close();
    wiremock.stop();
  }

  private EngineFixture startApp(String host, Class<?>... configurations) {
    VigilDatabase database = VigilDatabase.fresh();
    TestSecrets secrets = IntakeFixture.secrets(database, "");
    app = VigilTestApp.start(database, secrets.folder(), List.of(), configurations);
    app.awaitStarted();
    return EngineFixture.create(
        app, secrets, "Tenant A", host, "http://127.0.0.1:" + wiremock.port(), "");
  }

  private static List<JsonNode> errorsOf(String output, UUID run) {
    return LogLines.application(output).stream()
        .filter(line -> line.path("log").path("level").asString("").equals("ERROR"))
        .filter(line -> line.path("message").asString("").contains(run.toString()))
        .toList();
  }

  @Test
  @Order(1)
  @DisplayName("AR-R-21 a step whose handler bean is absent fails; cancel is skipped for it")
  void absentHandler(CapturedOutput output) {
    EngineFixture a = startApp("a.engine-absent.test", TestBeans.class);
    UUID run =
        a.start(
            a.openIncident(CLOCK, "nowhere-1", "i-1", ALERTNAME), TestContents.linear("ticket"));
    for (int n = 1; n <= 3; n++) {
      int attempt = n;
      EngineFixture.await(
          () ->
              a.steps(run).contains("s-1") && a.execution(run, "s-1").failedAttempts() == attempt);
      CLOCK.advance(Duration.ofSeconds(List.of(5, 15, 45).get(n - 1)));
      a.handOff(run);
    }
    RunView failed = a.awaitRunState(run, RunState.FAILED);
    assertThat(failed.endReason()).isEqualTo(EndReason.STEP_FAILED_REPEATEDLY);
    List<JsonNode> errors = errorsOf(output.getAll(), run);
    assertThat(errors).hasSize(4);
    assertThat(errors)
        .allSatisfy(
            line ->
                assertThat(line.path("error").path("message").asString())
                    .isEqualTo("no step handler for ticket"));

    UUID incident = a.openIncident(CLOCK, "nowhere-2", "i-1", ALERTNAME);
    UUID waiting = a.start(incident, TestContents.linear("recovery_check"));
    EngineFixture.await(
        () ->
            a.steps(waiting).contains("s-1") && a.execution(waiting, "s-1").failedAttempts() == 1);
    UUID reopened =
        a.engine
            .startLiveRun(
                a.tenant,
                EngineFixture.WORKFLOWS,
                incident,
                2,
                Ids.newId(),
                TestContents.waitUntilEvent(60),
                true)
            .orElseThrow();
    int errorsBefore = errorsOf(output.getAll(), waiting).size();
    RunView cancelled = a.run(waiting);
    assertThat(cancelled.state()).isEqualTo(RunState.CANCELLED);
    assertThat(cancelled.endReason()).isEqualTo(EndReason.INCIDENT_REOPENED);
    a.awaitRunState(reopened, RunState.WAITING);
    IntakeFixture.sleep(Duration.ofSeconds(6));
    assertThat(errorsOf(output.getAll(), waiting)).hasSize(errorsBefore);
    assertThat(a.run(waiting).state()).isEqualTo(RunState.CANCELLED);
    app.close();
  }

  @Test
  @Order(2)
  @DisplayName("AR-R-21 without ActionRuns a run has no proposal, so its Action step is refused")
  void absentActionRuns() {
    EngineFixture a = startApp("b.engine-absent.test", TestBeans.class, ActionsBeans.class);
    UUID run =
        a.start(
            a.openIncident(CLOCK, "nowhere-3", "i-1", ALERTNAME),
            TestContents.linear("approval", "action", "recovery_check"));
    EngineFixture.await(() -> a.steps(run).contains("s-3"));
    assertThat(a.execution(run, "s-1").namedOutput()).isEqualTo("approved");
    assertThat(a.execution(run, "s-2").namedOutput()).isEqualTo("failed");
    assertThat(a.execution(run, "s-2").refusalReason()).isEqualTo("no action proposal in this run");
    assertThat(ACTION_CALLS).noneMatch(call -> call.runId().equals(run));
  }
}
