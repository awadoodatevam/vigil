package vigil.intake;

import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import vigil.intake.api.Health;
import vigil.intake.api.SourceHealthChanged;
import vigil.shared.TenantId;

/**
 * The health evaluation of DM-R-40: run in a transaction that holds the source row's lock; a change
 * is recorded (DM-E-23), published (AR-E-03) and logged.
 */
@Component
class SourceHealth {

  private static final Logger LOG = LoggerFactory.getLogger(SourceHealth.class);

  private final IntakeRecords records;
  private final ApplicationEventPublisher events;

  SourceHealth(IntakeRecords records, ApplicationEventPublisher events) {
    this.records = records;
    this.events = events;
  }

  /** Locks the source row and evaluates its health at {@code now}; returns the health after it. */
  Health evaluate(TenantId tenantId, UUID sourceId, Instant now) {
    IntakeRecords.Stored source = records.lockSource(tenantId, sourceId);
    HealthRule.Result result = HealthRule.evaluate(source.inputs(), now);
    if (result.cause() == null) {
      return result.health();
    }
    Health from = source.view().health();
    records.updateHealth(tenantId, sourceId, result.health(), now);
    records.insertHealthChange(tenantId, sourceId, from, result.health(), now, result.cause());
    events.publishEvent(new SourceHealthChanged(tenantId, sourceId, result.health()));
    LOG.info(
        "source health changed: tenant {} source {} from {} to {} ({})",
        tenantId.value(),
        source.view().sourceKey(),
        from.key(),
        result.health().key(),
        result.cause().key());
    return result.health();
  }
}
