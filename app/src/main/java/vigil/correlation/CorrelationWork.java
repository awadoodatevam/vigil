package vigil.correlation;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import vigil.correlation.api.CorrelationScheduling;
import vigil.intake.api.AlertView;
import vigil.shared.TenantId;
import vigil.tenancy.api.TenantService;

/**
 * The correlation workers (AR-R-17, AR-R-18, AR-R-26, DM-R-62, DM-R-70): one virtual thread per
 * active tenant with correlation settings, the only thread that decides, checks stability or
 * reopens for its tenant. Not in the {@code migrate} profile.
 */
@Component
@Profile("!migrate")
class CorrelationWork implements CorrelationScheduling {

  private static final Logger LOG = LoggerFactory.getLogger(CorrelationWork.class);
  private static final Duration ENDING_WAIT = Duration.ofSeconds(4);
  private static final Duration CLOSE_WAIT = Duration.ofSeconds(10);
  private static final Duration FAILURE_WAIT = Duration.ofSeconds(5);
  private static final List<Duration> RETRY_DELAYS =
      List.of(Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(30));
  private static final Duration RETRY_DELAY_LAST = Duration.ofSeconds(60);

  /** A pending retry of a failed decision: the alert and the number of failed attempts. */
  record PendingRetry(TenantId tenantId, UUID alertId, int attempt) {}

  private final TenantService tenants;
  private final CorrelationRecords records;
  private final CorrelationDecisions decisions;
  private final Map<TenantId, Worker> workers = new ConcurrentHashMap<>();
  private final Map<TenantId, Worker> ending = new ConcurrentHashMap<>();
  private final Map<TenantId, PendingRetry> retries = new ConcurrentHashMap<>();
  private boolean closed;

  CorrelationWork(
      TenantService tenants, CorrelationRecords records, CorrelationDecisions decisions) {
    this.tenants = tenants;
    this.records = records;
    this.decisions = decisions;
  }

  @Override
  public synchronized void matchActiveTenants() {
    if (closed) {
      return;
    }
    Set<TenantId> active = tenants.activeTenants();
    for (Worker worker : List.copyOf(workers.values())) {
      if (!active.contains(worker.tenantId)) {
        workers.remove(worker.tenantId);
        ending.put(worker.tenantId, worker);
        worker.stop();
      }
    }
    ending.values().removeIf(worker -> !worker.thread.isAlive());
    long deadline = System.nanoTime() + ENDING_WAIT.toNanos();
    for (TenantId tenantId : active) {
      if (workers.containsKey(tenantId) || records.settings(tenantId).isEmpty()) {
        continue;
      }
      Worker previous = ending.get(tenantId);
      if (previous != null) {
        long remaining = Math.max(deadline - System.nanoTime(), 0);
        if (!join(previous.thread, Duration.ofNanos(remaining))) {
          LOG.warn(
              "correlation worker of tenant {} is still ending; no new worker yet",
              tenantId.value());
          continue;
        }
        ending.remove(tenantId, previous);
      }
      Worker worker = new Worker(tenantId);
      workers.put(tenantId, worker);
      worker.thread.start();
    }
  }

  /** Wakes the tenant's worker; a tenant without a worker is ignored (AR-E-01). */
  void wake(TenantId tenantId) {
    Worker worker = workers.get(tenantId);
    if (worker != null) {
      worker.wake();
    }
  }

  /** The tenants with a running worker (for tests). */
  Set<TenantId> runningTenants() {
    return Set.copyOf(workers.keySet());
  }

  /** The worker thread of the tenant (for tests). */
  Optional<Thread> workerThread(TenantId tenantId) {
    return Optional.ofNullable(workers.get(tenantId)).map(worker -> worker.thread);
  }

  /** The pending retries by tenant id, for the health indicator (AR-H-12). */
  List<PendingRetry> pendingRetries() {
    List<PendingRetry> pending = new ArrayList<>(retries.values());
    pending.sort(Comparator.comparing(retry -> retry.tenantId().value().toString()));
    return List.copyOf(pending);
  }

  /**
   * Stops every worker when the application context closes and waits for each to end; no worker
   * starts afterwards.
   */
  @PreDestroy
  synchronized void stopAll() {
    closed = true;
    List<Worker> all = new ArrayList<>(workers.values());
    all.addAll(ending.values());
    workers.clear();
    all.forEach(Worker::stop);
    for (Worker worker : all) {
      join(worker.thread, CLOSE_WAIT);
    }
    ending.clear();
  }

  private static boolean join(Thread thread, Duration timeout) {
    try {
      return thread.join(timeout);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return !thread.isAlive();
    }
  }

  /** The delay after failed attempt {@code n}: 1 s, 5 s, 30 s, then 60 s. */
  static Duration retryDelay(int attempt) {
    return attempt <= RETRY_DELAYS.size() ? RETRY_DELAYS.get(attempt - 1) : RETRY_DELAY_LAST;
  }

  /** One tenant's worker: its loop, its stop flag and its wake signal. */
  private final class Worker {

    final TenantId tenantId;
    final Thread thread;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition signal = lock.newCondition();
    private volatile boolean stopped;
    private boolean woken;

    Worker(TenantId tenantId) {
      this.tenantId = tenantId;
      this.thread = Thread.ofVirtual().name("correlation-" + tenantId.value()).unstarted(this::run);
    }

    void stop() {
      stopped = true;
      lock.lock();
      try {
        signal.signalAll();
      } finally {
        lock.unlock();
      }
    }

    void wake() {
      lock.lock();
      try {
        woken = true;
        signal.signalAll();
      } finally {
        lock.unlock();
      }
    }

    private void run() {
      LOG.info("correlation worker of tenant {} started", tenantId.value());
      try {
        while (!stopped) {
          try {
            if (!decideNewAlerts() || stopped) {
              break;
            }
            decisions.checkStability(tenantId, () -> stopped);
            awaitWake(decisions.settings(tenantId).stabilityCheckInterval());
          } catch (Throwable e) {
            LOG.error("correlation worker of tenant {} failed", tenantId.value(), e);
            pause(FAILURE_WAIT);
          }
        }
      } finally {
        retries.remove(tenantId);
        LOG.info("correlation worker of tenant {} stopped", tenantId.value());
      }
    }

    /** Decides the alerts after the cursor in batches; false when stopped. */
    private boolean decideNewAlerts() {
      while (!stopped) {
        List<AlertView> batch = decisions.nextAlerts(tenantId);
        for (AlertView alert : batch) {
          if (!decideWithRetries(alert)) {
            return false;
          }
        }
        if (batch.size() < CorrelationDecisions.BATCH) {
          return true;
        }
      }
      return false;
    }

    /** Decides the alert, retrying it until it succeeds; never skips it. False when stopped. */
    private boolean decideWithRetries(AlertView alert) {
      int attempt = 0;
      while (!stopped) {
        attempt++;
        try {
          decisions.decide(tenantId, alert);
          retries.remove(tenantId);
          return true;
        } catch (Throwable e) {
          retries.put(tenantId, new PendingRetry(tenantId, alert.id(), attempt));
          LOG.error(
              "correlation decision of alert {} for tenant {} failed (attempt {})",
              alert.id(),
              tenantId.value(),
              attempt,
              e);
          if (!pause(retryDelay(attempt))) {
            break;
          }
        }
      }
      retries.remove(tenantId);
      return false;
    }

    /** Waits until woken, stopped or the timeout has passed; a wake while busy counts. */
    private void awaitWake(Duration timeout) {
      lock.lock();
      try {
        long remaining = timeout.toNanos();
        while (!woken && !stopped && remaining > 0) {
          remaining = signal.awaitNanos(remaining);
        }
        woken = false;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        stopped = true;
      } finally {
        lock.unlock();
      }
    }

    /** Waits the whole duration (wakes do not shorten it); false when stopped. */
    private boolean pause(Duration duration) {
      lock.lock();
      try {
        long remaining = duration.toNanos();
        while (!stopped && remaining > 0) {
          remaining = signal.awaitNanos(remaining);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        stopped = true;
      } finally {
        lock.unlock();
      }
      return !stopped;
    }
  }
}
