package vigil.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
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
import vigil.api.ApiFixture;
import vigil.engine.api.EngineService;
import vigil.engine.api.Stage;
import vigil.engine.api.StepExecutionView;
import vigil.engine.api.TimerView;
import vigil.shared.Ids;
import vigil.shared.StepCall.StepType;
import vigil.shared.TenantId;
import vigil.shared.Waiting;
import vigil.testsupport.TestTenants;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EngineReadsIT {

  private ApiFixture fixture;
  private EngineService engine;
  private TenantId tenant;
  private Instant base;
  private final Map<String, UUID> incidents = new LinkedHashMap<>();
  private UUID liveRun;
  private UUID cancelledStep;
  private UUID earlierTimer;

  @BeforeAll
  void start() {
    fixture = new ApiFixture();
    engine = fixture.app.context().getBean(EngineService.class);
    tenant = fixture.tenant;
    base = fixture.clock.instant().minusSeconds(3600);
  }

  @AfterAll
  void stop() {
    fixture.close();
  }

  private UUID run(UUID incident, String mode, Long episode, String state, int startedS) {
    UUID id = Ids.newId();
    Timestamp at = Timestamp.from(base.plusSeconds(startedS));
    boolean ended = Set.of("finished", "cancelled", "failed").contains(state);
    fixture
        .postgres
        .sql(
            "INSERT INTO engine.workflow_run (id, tenant_id, created_at, updated_at, incident_id,"
                + " mode, episode, content, test_replay_id, state, wait_reason, wake_pending,"
                + " started_at, finished_at, end_reason) VALUES (:id, :t, :at, :at, :incident,"
                + " :mode, :episode, CAST('{}' AS jsonb), :replay, :state, :wait, false, :at,"
                + " :finished, :end)")
        .param("id", id)
        .param("t", tenant.value())
        .param("at", at)
        .param("incident", incident)
        .param("mode", mode)
        .param("episode", episode)
        .param("replay", mode.equals("test") ? Ids.newId() : null)
        .param("state", state)
        .param("wait", state.equals("waiting") ? "step_waiting" : null)
        .param("finished", ended ? at : null)
        .param("end", ended ? "path_ended" : null)
        .update();
    return id;
  }

  private UUID step(UUID run, String type, String state, int startedS) {
    UUID id = Ids.newId();
    Timestamp at = Timestamp.from(base.plusSeconds(startedS));
    fixture
        .postgres
        .sql(
            "INSERT INTO engine.step_execution (id, tenant_id, created_at, updated_at, run_id,"
                + " step_id, step_type, visit, state, started_at, named_output,"
                + " evaluation_count, failed_attempts) VALUES (:id, :t, :at, :at, :run, :stepId,"
                + " :type, 1, :state, :at, :output, 1, 0)")
        .param("id", id)
        .param("t", tenant.value())
        .param("at", at)
        .param("run", run)
        .param("stepId", "s-" + type.replace('_', '-'))
        .param("type", type)
        .param("state", state)
        .param("output", state.equals("completed") ? "done" : null)
        .update();
    return id;
  }

  /** An incident whose one live run waits at a step of the type, after a completed Trigger. */
  private UUID waitingAt(String type, String state) {
    UUID incident = UUID.randomUUID();
    UUID run = run(incident, "live", 1L, "waiting", 0);
    step(run, "trigger", "completed", 0);
    step(run, type, state, 1);
    return incident;
  }

  private void timer(UUID run, UUID stepExecution, Instant due, String state) {
    fixture
        .postgres
        .sql(
            "INSERT INTO engine.durable_timer (id, tenant_id, created_at, updated_at, run_id,"
                + " step_execution_id, due_at, state, purpose) VALUES (:id, :t, now(), now(),"
                + " :run, :step, :due, :state, 'step')")
        .param("id", state.equals("pending") ? earlierTimer : Ids.newId())
        .param("t", tenant.value())
        .param("run", run)
        .param("step", stepExecution)
        .param("due", Timestamp.from(due))
        .param("state", state)
        .update();
  }

  @Test
  @Order(1)
  @DisplayName("DC-R-08 stages from the latest live run's latest running or waiting step; timers")
  void reads() {
    incidents.put("diagnostic", waitingAt("diagnostic", "waiting"));
    incidents.put("ai_task", waitingAt("ai_task", "running"));
    incidents.put("approval", waitingAt("approval", "waiting"));
    incidents.put("action", waitingAt("action", "running"));
    incidents.put("recovery_check", waitingAt("recovery_check", "waiting"));
    incidents.put("notification", waitingAt("notification", "waiting"));
    UUID latestStep = UUID.randomUUID();
    UUID twoSteps = run(latestStep, "live", 1L, "running", 0);
    step(twoSteps, "diagnostic", "waiting", 1);
    step(twoSteps, "recovery_check", "running", 2);
    incidents.put("latest step", latestStep);
    UUID finished = UUID.randomUUID();
    UUID done = run(finished, "live", 1L, "finished", 0);
    step(done, "diagnostic", "completed", 1);
    incidents.put("finished", finished);
    UUID twoRuns = UUID.randomUUID();
    UUID older = run(twoRuns, "live", 1L, "cancelled", 0);
    step(older, "diagnostic", "waiting", 1);
    UUID newer = run(twoRuns, "live", 2L, "waiting", 10);
    step(newer, "approval", "waiting", 11);
    incidents.put("two runs", twoRuns);
    UUID withTest = UUID.randomUUID();
    UUID live = run(withTest, "live", 1L, "waiting", 0);
    step(live, "action", "waiting", 1);
    UUID test = run(withTest, "test", null, "running", 20);
    step(test, "diagnostic", "running", 21);
    incidents.put("test run", withTest);
    UUID onlyTest = UUID.randomUUID();
    step(run(onlyTest, "test", null, "running", 0), "recovery_check", "running", 1);
    incidents.put("only test", onlyTest);
    incidents.put("no run", UUID.randomUUID());

    Map<UUID, Stage> stages = engine.stages(tenant, incidents.values());
    Map<String, Stage> expected = new LinkedHashMap<>();
    expected.put("diagnostic", Stage.INVESTIGATION);
    expected.put("ai_task", Stage.INVESTIGATION);
    expected.put("approval", Stage.RESPONSE);
    expected.put("action", Stage.RESPONSE);
    expected.put("recovery_check", Stage.RECOVERY);
    expected.put("latest step", Stage.RECOVERY);
    expected.put("two runs", Stage.RESPONSE);
    expected.put("test run", Stage.RESPONSE);
    Map<UUID, Stage> byId = new LinkedHashMap<>();
    expected.forEach((name, stage) -> byId.put(incidents.get(name), stage));
    assertThat(stages).isEqualTo(byId);
    assertThat(engine.stages(tenant, List.of())).isEmpty();
    assertThat(engine.stages(tenant, List.of(incidents.get("no run")))).isEmpty();

    UUID incident = fixture.openIncident();
    fixture.handlers.answer(
        StepType.DIAGNOSTIC,
        c -> new Waiting(Set.of(), fixture.clock.instant().plusSeconds(600), "{}"));
    liveRun = fixture.liveRun(incident, TestContents.linear("diagnostic"));
    EngineFixture.await(() -> engine.pendingTimers(tenant, liveRun).size() == 1);
    TimerView real = engine.pendingTimers(tenant, liveRun).getFirst();
    StepExecutionView waiting =
        engine.stepExecutions(tenant, liveRun).stream()
            .filter(e -> e.stepType() == StepType.DIAGNOSTIC)
            .findFirst()
            .orElseThrow();
    assertThat(real.stepExecutionId()).isEqualTo(waiting.id());
    assertThat(real.dueAt()).isEqualTo(fixture.clock.instant().plusSeconds(600));
    assertThat(engine.stages(tenant, List.of(incident)))
        .containsEntry(incident, Stage.INVESTIGATION);

    cancelledStep = step(liveRun, "wait", "cancelled", 0);
    earlierTimer = Ids.newId();
    timer(liveRun, cancelledStep, fixture.clock.instant().plusSeconds(300), "pending");
    timer(liveRun, cancelledStep, fixture.clock.instant().plusSeconds(100), "fired");
    List<TimerView> timers = engine.pendingTimers(tenant, liveRun);
    assertThat(timers)
        .containsExactly(
            new TimerView(earlierTimer, cancelledStep, fixture.clock.instant().plusSeconds(300)),
            real);
  }

  @Test
  @Order(2)
  @DisplayName("PM-R-10 the stage and timer reads of vigil-engine answer only the tenant's runs")
  void tenantOnly() {
    TenantId other = new TestTenants(fixture.app).tenant("Other").create().id();
    assertThat(engine.stages(other, incidents.values())).isEmpty();
    assertThat(engine.pendingTimers(other, liveRun)).isEmpty();
    Map<UUID, Stage> stages = engine.stages(tenant, incidents.values());
    assertThat(stages).hasSize(8);
    assertThatThrownBy(() -> stages.clear()).isInstanceOf(UnsupportedOperationException.class);
    List<TimerView> timers = engine.pendingTimers(tenant, liveRun);
    assertThatThrownBy(() -> timers.clear()).isInstanceOf(UnsupportedOperationException.class);
    assertThat(Duration.between(fixture.clock.instant(), timers.getFirst().dueAt()))
        .isEqualTo(Duration.ofSeconds(300));
  }
}
