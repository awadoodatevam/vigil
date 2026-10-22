package vigil.incidents;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vigil.incidents.api.IncidentScheduling;
import vigil.incidents.api.IncidentSettingsView;
import vigil.incidents.api.IncidentState;
import vigil.shared.TenantId;
import vigil.shared.Timestamps;
import vigil.tenancy.api.TenantService;

/**
 * The closure check of each active tenant with incident settings (DM-R-23, AR-R-23, DM-R-70): every
 * {@code closure_check_interval}, each {@code resolved} incident whose reopen window has passed is
 * closed. Not in the {@code migrate} profile, which has no task scheduler.
 */
@Component
@Profile("!migrate")
class ClosureWork implements IncidentScheduling {

  private static final Logger LOG = LoggerFactory.getLogger(ClosureWork.class);

  private final TaskScheduler taskScheduler;
  private final TenantService tenants;
  private final IncidentRecords records;
  private final Incidents incidents;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final Map<TenantId, ScheduledFuture<?>> schedules = new HashMap<>();

  ClosureWork(
      TaskScheduler taskScheduler,
      TenantService tenants,
      IncidentRecords records,
      Incidents incidents,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.taskScheduler = taskScheduler;
    this.tenants = tenants;
    this.records = records;
    this.incidents = incidents;
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
              entry.getValue().cancel(false);
              return true;
            });
    for (TenantId tenantId : active) {
      if (schedules.containsKey(tenantId)) {
        continue;
      }
      Optional<IncidentSettingsView> settings = records.settings(tenantId);
      if (settings.isPresent()) {
        schedules.put(
            tenantId,
            taskScheduler.scheduleWithFixedDelay(
                () -> check(tenantId),
                taskScheduler.getClock().instant(),
                settings.get().closureCheckInterval()));
      }
    }
  }

  /** The tenants whose task is registered (for tests). */
  synchronized Set<TenantId> scheduledTenants() {
    return Set.copyOf(schedules.keySet());
  }

  /** One check: each resolved incident whose window has passed, in its own transaction. */
  private void check(TenantId tenantId) {
    try {
      Instant now = Timestamps.now(clock);
      IncidentSettingsView settings = records.settings(tenantId).orElseThrow();
      for (IncidentRow row : records.incidents(tenantId, Set.of(IncidentState.RESOLVED))) {
        if (row.resolvedAt.plus(settings.reopenWindow()).isBefore(now)) {
          tx.executeWithoutResult(
              status -> incidents.close(tenantId, row.id, settings.reopenWindow(), now));
        }
      }
    } catch (RuntimeException e) {
      LOG.warn("closure check of tenant {} failed ({})", tenantId.value(), e.getClass().getName());
    }
  }
}
