package vigil.intake;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vigil.intake.api.IntakeScheduling;
import vigil.intake.api.SourceView;
import vigil.shared.TenantId;
import vigil.shared.Timestamps;
import vigil.tenancy.api.TenantService;

/**
 * The scheduled health work of each active tenant (AR-R-23, DM-R-70): every 1 s the health
 * evaluation of its active sources and the connection test of each source that is due. Not in the
 * {@code migrate} profile, which has no task scheduler.
 */
@Component
@Profile("!migrate")
class SourceHealthWork implements IntakeScheduling {

  private static final Logger LOG = LoggerFactory.getLogger(SourceHealthWork.class);
  static final Duration TICK = Duration.ofSeconds(1);

  private final TaskScheduler taskScheduler;
  private final TenantService tenants;
  private final IntakeRecords records;
  private final SourceHealth health;
  private final SourceTests tests;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final Map<TenantId, Schedule> schedules = new HashMap<>();

  /** One tenant's task, the test start of each source and the sources being tested. */
  private static final class Schedule {

    private ScheduledFuture<?> future;
    private final Map<UUID, Instant> testStarts = new ConcurrentHashMap<>();
    private final Set<UUID> testing = ConcurrentHashMap.newKeySet();
  }

  SourceHealthWork(
      TaskScheduler taskScheduler,
      TenantService tenants,
      IntakeRecords records,
      SourceHealth health,
      SourceTests tests,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.taskScheduler = taskScheduler;
    this.tenants = tenants;
    this.records = records;
    this.health = health;
    this.tests = tests;
    this.tx = new TransactionTemplate(transactionManager);
    this.clock = clock;
  }

  @Override
  public synchronized void matchActiveTenants() {
    Set<TenantId> active = tenants.activeTenants();
    schedules
        .entrySet()
        .removeIf(
            entry -> {
              if (active.contains(entry.getKey())) {
                return false;
              }
              entry.getValue().future.cancel(false);
              return true;
            });
    for (TenantId tenantId : active) {
      if (!schedules.containsKey(tenantId)) {
        Schedule schedule = new Schedule();
        schedule.future =
            taskScheduler.scheduleWithFixedDelay(
                () -> tick(tenantId, schedule), taskScheduler.getClock().instant(), TICK);
        schedules.put(tenantId, schedule);
      }
    }
  }

  /** The tenants whose task is registered (for tests). */
  synchronized Set<TenantId> scheduledTenants() {
    return Set.copyOf(schedules.keySet());
  }

  /** One tick: (1) each active source's evaluation, (2) the tests that are due. */
  private void tick(TenantId tenantId, Schedule schedule) {
    try {
      Instant now = Timestamps.now(clock);
      List<IntakeRecords.Stored> sources = records.sources(tenantId, true);
      for (IntakeRecords.Stored source : sources) {
        tx.executeWithoutResult(status -> health.evaluate(tenantId, source.view().id(), now));
      }
      for (IntakeRecords.Stored source : sources) {
        SourceView view = source.view();
        Instant last = schedule.testStarts.get(view.id());
        boolean due = last == null || !last.plus(view.healthCheckInterval()).isAfter(now);
        if (due && schedule.testing.add(view.id())) {
          schedule.testStarts.put(view.id(), now);
          Thread.ofVirtual()
              .name("intake-test-" + view.sourceKey())
              .start(
                  () -> {
                    try {
                      tests.testLogged(tenantId, view);
                    } finally {
                      schedule.testing.remove(view.id());
                    }
                  });
        }
      }
    } catch (RuntimeException e) {
      LOG.warn(
          "source health work of tenant {} failed ({})", tenantId.value(), e.getClass().getName());
    }
  }
}
