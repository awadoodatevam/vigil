package vigil.api;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import vigil.correlation.api.CorrelationScheduling;
import vigil.engine.api.EngineScheduling;
import vigil.identity.api.SessionExpiry;
import vigil.incidents.api.IncidentScheduling;
import vigil.intake.api.IntakeScheduling;
import vigil.runner.api.RunnerRestart;
import vigil.setup.api.SeedImports;

/**
 * The restart procedure of AR-R-24: steps (1) to (7) in order, after the application context has
 * started. Later packages add their calls to the step methods.
 */
@Component
@Profile("!migrate")
class RestartProcedure implements ApplicationRunner {

  private static final Logger LOG = LoggerFactory.getLogger(RestartProcedure.class);
  static final Duration RESUBMISSION_INTERVAL = Duration.ofSeconds(60);

  private final RunnerRestart runnerRestart;
  private final IncompleteEventPublications incompletePublications;
  private final TaskScheduler taskScheduler;
  private final ReadyGate readyGate;
  private final SessionExpiry sessionExpiry;
  private final SeedImports seedImports;
  private final IntakeScheduling intakeScheduling;
  private final IncidentScheduling incidentScheduling;
  private final CorrelationScheduling correlationScheduling;
  private final EngineScheduling engineScheduling;

  RestartProcedure(
      RunnerRestart runnerRestart,
      IncompleteEventPublications incompletePublications,
      TaskScheduler taskScheduler,
      ReadyGate readyGate,
      SessionExpiry sessionExpiry,
      SeedImports seedImports,
      IntakeScheduling intakeScheduling,
      IncidentScheduling incidentScheduling,
      CorrelationScheduling correlationScheduling,
      EngineScheduling engineScheduling) {
    this.runnerRestart = runnerRestart;
    this.incompletePublications = incompletePublications;
    this.taskScheduler = taskScheduler;
    this.readyGate = readyGate;
    this.sessionExpiry = sessionExpiry;
    this.seedImports = seedImports;
    this.intakeScheduling = intakeScheduling;
    this.incidentScheduling = incidentScheduling;
    this.correlationScheduling = correlationScheduling;
    this.engineScheduling = engineScheduling;
  }

  @Override
  public void run(ApplicationArguments args) {
    step1MarkInterruptedOperationRuns();
    LOG.info("restart step {} done", 1);
    step2FailInterruptedSeedImports();
    LOG.info("restart step {} done", 2);
    step3ResubmitIncompletePublications();
    LOG.info("restart step {} done", 3);
    step4StartRunExecutors();
    LOG.info("restart step {} done", 4);
    step5StartCorrelationWorkers();
    LOG.info("restart step {} done", 5);
    step6StartScheduledWork();
    LOG.info("restart step {} done", 6);
    step7SetReady();
    LOG.info("restart step {} done", 7);
  }

  void step1MarkInterruptedOperationRuns() {
    runnerRestart.markInterruptedRuns();
  }

  void step2FailInterruptedSeedImports() {
    seedImports.failInterrupted();
  }

  void step3ResubmitIncompletePublications() {
    incompletePublications.resubmitIncompletePublications(publication -> true);
  }

  /** Run executors accept hand-offs; every unfinished run of an active tenant is handed off. */
  void step4StartRunExecutors() {
    engineScheduling.resumeRuns();
  }

  /** Starts the correlation workers of the active tenants from their cursors. */
  void step5StartCorrelationWorkers() {
    correlationScheduling.matchActiveTenants();
  }

  /** Starts the per-installation task (AR-R-23); modules add the tasks of the active tenants. */
  void step6StartScheduledWork() {
    taskScheduler.scheduleWithFixedDelay(
        this::resubmitOldPublications,
        taskScheduler.getClock().instant().plus(RESUBMISSION_INTERVAL),
        RESUBMISSION_INTERVAL);
    sessionExpiry.matchActiveTenants();
    intakeScheduling.matchActiveTenants();
    incidentScheduling.matchActiveTenants();
    engineScheduling.matchActiveTenants();
  }

  void step7SetReady() {
    readyGate.setReady();
  }

  /** The per-installation task: resubmits publications older than 60 s (AR-R-14, AR-TM-08). */
  void resubmitOldPublications() {
    incompletePublications.resubmitIncompletePublicationsOlderThan(RESUBMISSION_INTERVAL);
  }
}
