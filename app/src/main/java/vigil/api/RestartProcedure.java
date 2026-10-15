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

/**
 * The restart procedure of AR-R-24: steps (1) to (7) in order, after the application context has
 * started. Later packages add their calls to the step methods.
 */
@Component
@Profile("!migrate")
class RestartProcedure implements ApplicationRunner {

  private static final Logger LOG = LoggerFactory.getLogger(RestartProcedure.class);
  static final Duration RESUBMISSION_INTERVAL = Duration.ofSeconds(60);

  private final IncompleteEventPublications incompletePublications;
  private final TaskScheduler taskScheduler;
  private final ReadyGate readyGate;

  RestartProcedure(
      IncompleteEventPublications incompletePublications,
      TaskScheduler taskScheduler,
      ReadyGate readyGate) {
    this.incompletePublications = incompletePublications;
    this.taskScheduler = taskScheduler;
    this.readyGate = readyGate;
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
    // Filled by P-18 (vigil-runner).
  }

  void step2FailInterruptedSeedImports() {
    // Filled by P-11 (vigil-setup).
  }

  void step3ResubmitIncompletePublications() {
    incompletePublications.resubmitIncompletePublications(publication -> true);
  }

  void step4StartRunExecutors() {
    // Filled by P-17 (vigil-engine).
  }

  void step5StartCorrelationWorkers() {
    // Filled by P-16 (vigil-correlation).
  }

  /** Starts the per-installation task (AR-R-23); modules add the tasks of the active tenants. */
  void step6StartScheduledWork() {
    taskScheduler.scheduleWithFixedDelay(
        this::resubmitOldPublications,
        taskScheduler.getClock().instant().plus(RESUBMISSION_INTERVAL),
        RESUBMISSION_INTERVAL);
  }

  void step7SetReady() {
    readyGate.setReady();
  }

  /** The per-installation task: resubmits publications older than 60 s (AR-R-14, AR-TM-08). */
  void resubmitOldPublications() {
    incompletePublications.resubmitIncompletePublicationsOlderThan(RESUBMISSION_INTERVAL);
  }
}
