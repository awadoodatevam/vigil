package vigil.engine;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import vigil.shared.TenantId;

/**
 * The run executors (AR-R-20, AR-R-27): per run a queue of hand-offs, processed one at a time by
 * one virtual thread named {@code run-{run id}}, which ends when the queue is empty. Hand-offs are
 * accepted from restart step (4) on and dropped once the application context closes. Not in the
 * {@code migrate} profile.
 */
@Component
@Profile("!migrate")
class RunExecutors {

  private static final Logger LOG = LoggerFactory.getLogger(RunExecutors.class);
  private static final Duration FAILURE_WAIT = Duration.ofSeconds(5);

  private final RunSteps steps;
  private final TaskScheduler taskScheduler;
  private final Map<UUID, Executor> executors = new HashMap<>();
  private boolean accepting;
  private volatile boolean closed;

  RunExecutors(RunSteps steps, TaskScheduler taskScheduler) {
    this.steps = steps;
    this.taskScheduler = taskScheduler;
  }

  /** Restart step (4): hand-offs are accepted from now on. */
  synchronized void startAccepting() {
    accepting = true;
  }

  /** Puts a signal into the run's queue, starting its thread when it has none. */
  void handOff(TenantId tenantId, UUID runId) {
    synchronized (this) {
      if (!accepting || closed) {
        return;
      }
      Executor executor = executors.get(runId);
      if (executor != null) {
        executor.signals++;
        return;
      }
      executor = new Executor(tenantId, runId);
      executors.put(runId, executor);
      Thread.ofVirtual().name("run-" + runId).start(executor::run);
    }
  }

  /** A hand-off of the run after {@code delay} of real time. */
  void scheduleHandOff(TenantId tenantId, UUID runId, Duration delay) {
    synchronized (this) {
      if (closed) {
        return;
      }
    }
    taskScheduler.schedule(
        () -> handOff(tenantId, runId), taskScheduler.getClock().instant().plus(delay));
  }

  /** The application context closes: no hand-off, no scheduled hand-off, no new processing. */
  @PreDestroy
  synchronized void close() {
    closed = true;
  }

  /** One run's queue: the signals not yet processed. */
  private final class Executor {

    private final TenantId tenantId;
    private final UUID runId;
    private int signals = 1;

    Executor(TenantId tenantId, UUID runId) {
      this.tenantId = tenantId;
      this.runId = runId;
    }

    private void run() {
      while (true) {
        synchronized (RunExecutors.this) {
          if (closed || signals == 0) {
            executors.remove(runId);
            return;
          }
          signals--;
        }
        try {
          steps.process(tenantId, runId, () -> closed);
        } catch (RuntimeException | Error e) {
          LOG.error("run executor of run {} for tenant {} failed", runId, tenantId.value(), e);
          scheduleHandOff(tenantId, runId, FAILURE_WAIT);
        }
      }
    }
  }
}
