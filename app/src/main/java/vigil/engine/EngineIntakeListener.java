package vigil.engine;

import java.time.Clock;
import org.springframework.context.annotation.Profile;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import vigil.intake.api.AlertStateChanged;
import vigil.intake.api.SourceHealthChanged;
import vigil.shared.TenantId;
import vigil.shared.Timestamps;
import vigil.shared.Waiting.WakeEvent;
import vigil.tenancy.api.TenantService;

/**
 * The AR-E-02 and AR-E-03 listener of vigil-engine: wakes the tenant's unfinished live runs at a
 * Recovery check step (AR-R-20, DM-A-22). A run is marked once, so a second delivery gives the same
 * result (AR-Q-05).
 */
@Component
@Profile("!migrate")
class EngineIntakeListener {

  private final TenantService tenants;
  private final EngineRecords records;
  private final HandOffs handOffs;
  private final Clock clock;

  EngineIntakeListener(
      TenantService tenants, EngineRecords records, HandOffs handOffs, Clock clock) {
    this.tenants = tenants;
    this.records = records;
    this.handOffs = handOffs;
    this.clock = clock;
  }

  @ApplicationModuleListener
  public void onAlertStateChanged(AlertStateChanged event) {
    wake(event.tenantId(), WakeEvent.ALERT_STATE_CHANGED);
  }

  @ApplicationModuleListener
  public void onSourceHealthChanged(SourceHealthChanged event) {
    wake(event.tenantId(), WakeEvent.SOURCE_HEALTH_CHANGED);
  }

  private void wake(TenantId tenantId, WakeEvent event) {
    if (tenants.isActive(tenantId)) {
      records
          .markRecoveryChecks(tenantId, event, Timestamps.now(clock))
          .forEach(runId -> handOffs.afterCommit(tenantId, runId));
    }
  }
}
