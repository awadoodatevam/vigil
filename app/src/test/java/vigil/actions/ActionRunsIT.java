package vigil.actions;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import vigil.actions.api.ActionRuns;
import vigil.actions.api.ActionsService;
import vigil.actions.api.ActionsStepHandler;
import vigil.actions.api.Decision;
import vigil.notifications.NotificationsApp;
import vigil.shared.Ids;
import vigil.shared.Key;
import vigil.shared.StepCall;
import vigil.testsupport.MutableClock;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ActionRunsIT {

  static final Instant T0 = Instant.parse("2026-11-12T09:00:00Z");
  static final MutableClock CLOCK = new MutableClock(T0);

  /** The test clock. */
  static class TestBeans {
    @Bean
    @Primary
    Clock testClock() {
      return CLOCK;
    }
  }

  private NotificationsApp servers;
  private ActionsStepHandler step;
  private ActionRuns runs;
  private ActionsService actions;
  private ActionsFixture demo;
  private ActionsFixture preauthorized;

  @BeforeAll
  void start() {
    servers = NotificationsApp.start(TestBeans.class);
    step = servers.app.context().getBean(ActionsStepHandler.class);
    runs = servers.app.context().getBean(ActionRuns.class);
    actions = servers.app.context().getBean(ActionsService.class);
    demo = ActionsFixture.create(servers, "Runs");
    preauthorized =
        ActionsFixture.create(
            servers,
            "Runs preauthorized",
            ActionsFixture.part(1, ActionsFixture.policy(null, "preauthorized")));
  }

  @AfterAll
  void stop() {
    servers.close();
  }

  @BeforeEach
  void reset() {
    servers.reset();
    CLOCK.set(T0);
  }

  private Map<String, Object> request(ActionsFixture f, StepCall call) {
    step.start(call);
    return f.rowsOfRun("approval_request", call.runId()).stream()
        .filter(r -> r.get("step_execution_id").equals(call.stepExecutionId()))
        .findFirst()
        .orElseThrow();
  }

  private void approve(ActionsFixture f, Map<String, Object> request, String user) {
    actions.decide(
        f.tenant,
        (UUID) request.get("id"),
        f.userId(user),
        Decision.APPROVE,
        (String) request.get("snapshot_hash"));
  }

  @Test
  @DisplayName("DM-R-31 the reads of the engine's guard with real records")
  void guardReads() {
    UUID incident = demo.openIncident();
    demo.match(incident);
    UUID run = Ids.newId();
    assertThat(runs.proposal(demo.tenant, run)).isEmpty();
    assertThat(runs.proposalOfRequest(demo.tenant, Ids.newId())).isEmpty();
    assertThat(runs.lastExecutedStepExecution(demo.tenant, run)).isEmpty();
    assertThat(runs.earlierApprovers(demo.tenant, run)).isEmpty();

    Map<String, Object> first =
        request(demo, demo.approval(run, incident, ActionsFixture.APPROVAL, List.of()));
    Map<String, Object> second =
        request(demo, demo.approval(run, incident, ActionsFixture.APPROVAL, List.of()));
    UUID proposal = (UUID) first.get("proposal_id");
    assertThat(runs.proposal(demo.tenant, run)).contains(proposal);
    assertThat(runs.proposalOfRequest(demo.tenant, (UUID) second.get("id"))).contains(proposal);
    assertThat(runs.earlierApprovers(demo.tenant, run)).isEmpty();
    CLOCK.set(T0.plusSeconds(5));
    approve(demo, second, "dbops-engineer");
    CLOCK.set(T0.plusSeconds(10));
    approve(demo, first, "dbops-lead");
    assertThat(runs.earlierApprovers(demo.tenant, run))
        .containsExactly(demo.userId("dbops-engineer"), demo.userId("dbops-lead"));

    UUID earlier = Ids.newId();
    UUID later = Ids.newId();
    demo.insertExecution(
        run, incident, later, proposal, List.of(), "succeeded", 1, 1, T0.plusSeconds(30));
    demo.insertExecution(
        run, incident, earlier, proposal, List.of(), "failed", 1, 1, T0.plusSeconds(20));
    demo.insertExecution(
        run, incident, Ids.newId(), proposal, List.of(), "not_executed", 1, 1, null);
    assertThat(runs.lastExecutedStepExecution(demo.tenant, run)).contains(later);

    UUID p = preauthorized.openIncident();
    preauthorized.match(p);
    UUID preRun = Ids.newId();
    request(preauthorized, preauthorized.approval(preRun, p, ActionsFixture.APPROVAL, List.of()));
    assertThat(preauthorized.rowsOfRun("approval_request", preRun).getFirst().get("state"))
        .isEqualTo("preauthorized");
    assertThat(runs.earlierApprovers(preauthorized.tenant, preRun)).isEmpty();
  }

  @Test
  @DisplayName("DM-R-34 the recovery check key of the run's latest executed action")
  void recoveryCheckKey() {
    UUID incident = demo.openIncident();
    demo.match(incident);
    UUID run = Ids.newId();
    assertThat(runs.latestRecoveryCheckKey(demo.tenant, run)).isEmpty();
    Map<String, Object> request =
        request(demo, demo.approval(run, incident, ActionsFixture.APPROVAL, List.of()));
    UUID proposal = (UUID) request.get("proposal_id");
    demo.insertExecution(
        run, incident, Ids.newId(), proposal, List.of(), "not_executed", 1, 1, null);
    assertThat(runs.latestRecoveryCheckKey(demo.tenant, run)).isEmpty();
    demo.insertExecution(run, incident, Ids.newId(), proposal, List.of(), "succeeded", 1, 1, T0);
    assertThat(runs.latestRecoveryCheckKey(demo.tenant, run)).contains(new Key("rc-subscriber-db"));
  }
}
