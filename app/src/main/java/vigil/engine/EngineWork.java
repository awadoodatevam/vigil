package vigil.engine;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vigil.engine.EngineRecords.FiredTimer;
import vigil.engine.api.EngineScheduling;
import vigil.shared.TenantId;
import vigil.shared.Timestamps;
import vigil.tenancy.api.TenantService;

/**
 * The engine's scheduled work and restart (AR-R-23, AR-R-24 step (4), AR-R-19): the timer poll of
 * each active tenant every 1 s, the engine's set of active tenants, and the hand-off of every
 * unfinished run at restart and when a tenant becomes active again. Not in the {@code migrate}
 * profile.
 */
@Component
@Profile("!migrate")
class EngineWork implements EngineScheduling {

  private static final Logger LOG = LoggerFactory.getLogger(EngineWork.class);
  private static final Duration POLL_INTERVAL = Duration.ofSeconds(1);

  private final TaskScheduler taskScheduler;
  private final TenantService tenants;
  private final EngineRecords records;
  private final RunExecutors executors;
  private final HandOffs handOffs;
  private final ActiveTenants activeTenants;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final Map<TenantId, ScheduledFuture<?>> polls = new HashMap<>();
  private boolean resumed;

  EngineWork(
      TaskScheduler taskScheduler,
      TenantService tenants,
      EngineRecords records,
      RunExecutors executors,
      HandOffs handOffs,
      ActiveTenants activeTenants,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.taskScheduler = taskScheduler;
    this.tenants = tenants;
    this.records = records;
    this.executors = executors;
    this.handOffs = handOffs;
    this.activeTenants = activeTenants;
    this.tx = new TransactionTemplate(transactionManager);
    this.clock = clock;
  }

  @Override
  public synchronized void resumeRuns() {
    if (resumed) {
      return;
    }
    resumed = true;
    executors.startAccepting();
    Set<TenantId> active = tenants.activeTenants();
    activeTenants.set(active);
    active.forEach(this::resume);
  }

  @Override
  public synchronized void matchActiveTenants() {
    Set<TenantId> active = tenants.activeTenants();
    polls
        .entrySet()
        .removeIf(
            entry -> {
              if (active.contains(entry.getKey())) {
                return false;
              }
              entry.getValue().cancel(false);
              return true;
            });
    for (TenantId tenantId : active) {
      polls.computeIfAbsent(
          tenantId,
          t ->
              taskScheduler.scheduleWithFixedDelay(
                  () -> poll(t), taskScheduler.getClock().instant(), POLL_INTERVAL));
    }
    Set<TenantId> again = new HashSet<>(active);
    again.removeAll(activeTenants.all());
    activeTenants.set(active);
    if (resumed) {
      again.forEach(this::resume);
    }
  }

  /** The tenants that have a timer poll (for tests). */
  synchronized Set<TenantId> scheduledTenants() {
    return Set.copyOf(polls.keySet());
  }

  /**
   * Restart step (4) for one tenant: the unfinished runs locked in id order; overdue timers fire
   * (DM-E52-R1), waiting runs get a wake (DM-R-66), retries count from zero; then every unfinished
   * run is handed off.
   */
  private void resume(TenantId tenantId) {
    tx.executeWithoutResult(
        status -> {
          Instant now = Timestamps.now(clock);
          List<UUID> unfinished = records.lockUnfinishedRuns(tenantId);
          records.fireDueTimers(tenantId, now);
          records.wakeWaitingRuns(tenantId, now);
          records.resetRetries(tenantId, now);
          unfinished.forEach(id -> handOffs.afterCommit(tenantId, id));
        });
  }

  /**
   * The timer poll (AR-R-23): the runs of the due timers locked in id order; then each timer, in
   * due order, fires when it is still pending and wakes its run.
   */
  private void poll(TenantId tenantId) {
    try {
      tx.executeWithoutResult(
          status -> {
            Instant now = Timestamps.now(clock);
            List<FiredTimer> due = records.dueTimers(tenantId, now);
            records.lockRuns(tenantId, due.stream().map(FiredTimer::runId).distinct().toList());
            for (FiredTimer timer : due) {
              if (records.fireTimer(tenantId, timer.id(), now)) {
                for (UUID runId : records.markTimer(tenantId, timer, now)) {
                  handOffs.afterCommit(tenantId, runId);
                }
              }
            }
          });
    } catch (RuntimeException e) {
      LOG.warn("engine timer poll for tenant {} failed", tenantId.value(), e);
    }
  }
}
