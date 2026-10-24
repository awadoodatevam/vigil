package vigil.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import vigil.correlation.CorrelationFixture;
import vigil.engine.api.EngineService;
import vigil.engine.api.RunState;
import vigil.engine.api.RunView;
import vigil.engine.api.StepExecutionView;
import vigil.incidents.api.IncidentService;
import vigil.incidents.api.IncidentState;
import vigil.intake.IntakeFixture;
import vigil.intake.api.AlertView;
import vigil.shared.Actor;
import vigil.shared.Ids;
import vigil.shared.TenantId;
import vigil.testsupport.MutableClock;
import vigil.testsupport.TestSecrets;
import vigil.testsupport.VigilTestApp;

/**
 * The engine fixture of P-17 spec Section 7: a correlation fixture whose tenant has its timer poll,
 * and helpers that make incidents, start live runs and wait for them.
 */
public final class EngineFixture {

  public static final Actor WORKFLOWS = Actor.module("vigil-workflows");
  public static final Duration WAIT = Duration.ofSeconds(10);

  public final CorrelationFixture correlation;
  public final TenantId tenant;
  public final EngineService engine;
  private final VigilTestApp app;
  private final IncidentService incidents;

  private EngineFixture(VigilTestApp app, CorrelationFixture correlation) {
    this.app = app;
    this.correlation = correlation;
    this.tenant = correlation.tenant;
    this.engine = app.context().getBean(EngineService.class);
    this.incidents = app.context().getBean(IncidentService.class);
  }

  public static EngineFixture create(
      VigilTestApp app,
      TestSecrets secrets,
      String name,
      String host,
      String address,
      String suffix) {
    CorrelationFixture correlation =
        CorrelationFixture.create(app, secrets, name, host, address, suffix);
    await(() -> isScheduled(app, correlation.tenant));
    return new EngineFixture(app, correlation);
  }

  /** The fixture of a tenant created earlier, for an application started again. */
  public static EngineFixture of(
      VigilTestApp app, TestSecrets secrets, TenantId tenant, String suffix) {
    return new EngineFixture(app, CorrelationFixture.of(app, secrets, tenant, suffix));
  }

  /** Whether the tenant has a timer poll. */
  public static boolean isScheduled(VigilTestApp app, TenantId tenant) {
    return app.context().getBean(EngineWork.class).scheduledTenants().contains(tenant);
  }

  /** Starts a live run of episode 1 on a new version id; returns its id. */
  public UUID start(UUID incident, String contentJson) {
    return engine
        .startLiveRun(tenant, WORKFLOWS, incident, 1, Ids.newId(), contentJson, false)
        .orElseThrow();
  }

  /** Posts an Alertmanager alert, waits until it is decided and returns its incident. */
  public UUID formingIncident(String component, String instance, String alertname) {
    AlertView alert = correlation.postAlertmanager(component, instance, alertname);
    correlation.awaitDecided();
    UUID incident = incidents.incidentOfAlert(tenant, alert.id()).orElseThrow();
    awaitPublications();
    return incident;
  }

  /** {@link #formingIncident}, then 41 s later on the clock, until the incident is open. */
  public UUID openIncident(
      MutableClock clock, String component, String instance, String alertname) {
    UUID incident = formingIncident(component, instance, alertname);
    clock.advance(Duration.ofSeconds(41));
    correlation.awaitState(incident, IncidentState.OPEN);
    awaitPublications();
    return incident;
  }

  /** Waits until every event publication is complete. */
  public void awaitPublications() {
    JdbcClient jdbc = app.context().getBean(JdbcClient.class);
    await(
        () ->
            jdbc.sql(
                        "SELECT count(*) FROM platform.event_publication"
                            + " WHERE completion_date IS NULL")
                    .query(Long.class)
                    .single()
                == 0);
  }

  public RunView run(UUID run) {
    return engine.run(tenant, run).orElseThrow();
  }

  public List<StepExecutionView> executions(UUID run) {
    return engine.stepExecutions(tenant, run);
  }

  /** The step execution of the run's step with this step id (its last visit). */
  public StepExecutionView execution(UUID run, String stepId) {
    return executions(run).stream()
        .filter(execution -> execution.stepId().equals(stepId))
        .reduce((first, second) -> second)
        .orElseThrow();
  }

  public RunView awaitRunState(UUID run, RunState state) {
    await(() -> run(run).state() == state);
    return run(run);
  }

  /** The step ids of the run's step executions, in order. */
  public List<String> steps(UUID run) {
    return executions(run).stream().map(StepExecutionView::stepId).toList();
  }

  /** The named outputs of the run's step executions, in order. */
  public List<String> outputs(UUID run) {
    return executions(run).stream().map(StepExecutionView::namedOutput).toList();
  }

  /** Hands the run to its executor. */
  public void handOff(UUID run) {
    app.context().getBean(RunExecutors.class).handOff(tenant, run);
  }

  public static void await(BooleanSupplier condition) {
    long deadline = System.nanoTime() + WAIT.toNanos();
    while (!condition.getAsBoolean()) {
      assertThat(System.nanoTime()).as("condition met within %s", WAIT).isLessThan(deadline);
      IntakeFixture.sleep(Duration.ofMillis(50));
    }
  }
}
