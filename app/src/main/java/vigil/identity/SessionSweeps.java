package vigil.identity;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vigil.identity.api.SessionExpiry;
import vigil.shared.TenantId;
import vigil.shared.Timestamps;
import vigil.tenancy.api.TenantService;

/**
 * The session expiry sweep of each active tenant, every 60 s (AR-R-23, DM-SM02-T3, PM-R-11). Not in
 * the {@code migrate} profile, which has no task scheduler.
 */
@Component
@Profile("!migrate")
class SessionSweeps implements SessionExpiry {

  private static final Logger LOG = LoggerFactory.getLogger(SessionSweeps.class);
  static final Duration INTERVAL = Duration.ofSeconds(60);

  private final TaskScheduler taskScheduler;
  private final TenantService tenants;
  private final JdbcClient jdbc;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final Map<TenantId, ScheduledFuture<?>> sweeps = new HashMap<>();

  SessionSweeps(
      TaskScheduler taskScheduler,
      TenantService tenants,
      JdbcClient jdbc,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.taskScheduler = taskScheduler;
    this.tenants = tenants;
    this.jdbc = jdbc;
    this.tx = new TransactionTemplate(transactionManager);
    this.clock = clock;
  }

  @Override
  public synchronized void matchActiveTenants() {
    Set<TenantId> active = tenants.activeTenants();
    sweeps
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
      if (!sweeps.containsKey(tenantId)) {
        sweeps.put(
            tenantId,
            taskScheduler.scheduleWithFixedDelay(
                () -> sweepLogged(tenantId), taskScheduler.getClock().instant(), INTERVAL));
      }
    }
  }

  /** The tenants whose sweep is registered (for tests). */
  synchronized Set<TenantId> scheduledTenants() {
    return Set.copyOf(sweeps.keySet());
  }

  /** One sweep of the tenant now, in its own transaction: ends every session past its limits. */
  void sweep(TenantId tenantId) {
    Instant now = Timestamps.now(clock);
    tx.executeWithoutResult(
        status ->
            jdbc.sql(
                    "UPDATE identity.login_session SET state = 'ended', end_reason = 'expired',"
                        + " ended_at = :now, updated_at = :now"
                        + " WHERE tenant_id = :tenantId AND state = 'active'"
                        + " AND (expires_at <= :now OR last_activity_at < :idleBefore)")
                .param("now", Timestamp.from(now))
                .param("idleBefore", Timestamp.from(now.minus(SessionLimits.IDLE)))
                .param("tenantId", tenantId.value())
                .update());
  }

  private void sweepLogged(TenantId tenantId) {
    try {
      sweep(tenantId);
    } catch (RuntimeException e) {
      LOG.warn("session expiry sweep of tenant {} failed", tenantId.value(), e);
    }
  }
}
