package vigil.engine;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import vigil.engine.api.EndReason;
import vigil.incidents.api.IncidentAcknowledged;
import vigil.incidents.api.IncidentState;
import vigil.incidents.api.IncidentStateChanged;
import vigil.shared.TenantId;
import vigil.shared.Timestamps;
import vigil.shared.Waiting.WakeEvent;
import vigil.tenancy.api.TenantService;

/**
 * The AR-E-06 and AR-E-07 listener of vigil-engine: wakes the incident's unfinished live runs, or
 * cancels them when the incident becomes {@code merged} or {@code closed} (DM-SM07-T5). A run is
 * marked or cancelled once, so a second delivery gives the same result (AR-Q-05).
 */
@Component
@Profile("!migrate")
class EngineIncidentListener {

  private final TenantService tenants;
  private final EngineRecords records;
  private final Runs runs;
  private final HandOffs handOffs;
  private final Clock clock;

  EngineIncidentListener(
      TenantService tenants, EngineRecords records, Runs runs, HandOffs handOffs, Clock clock) {
    this.tenants = tenants;
    this.records = records;
    this.runs = runs;
    this.handOffs = handOffs;
    this.clock = clock;
  }

  @ApplicationModuleListener
  public void onAcknowledged(IncidentAcknowledged event) {
    if (tenants.isActive(event.tenantId())) {
      wake(event.tenantId(), event.incidentId(), WakeEvent.INCIDENT_ACKNOWLEDGED);
    }
  }

  @ApplicationModuleListener
  public void onStateChanged(IncidentStateChanged event) {
    if (!tenants.isActive(event.tenantId())) {
      return;
    }
    if (event.to() == IncidentState.MERGED || event.to() == IncidentState.CLOSED) {
      runs.cancelIncidentRuns(
          event.tenantId(),
          event.incidentId(),
          event.to() == IncidentState.MERGED
              ? EndReason.INCIDENT_MERGED
              : EndReason.INCIDENT_CLOSED,
          Timestamps.now(clock));
    } else {
      wake(event.tenantId(), event.incidentId(), WakeEvent.INCIDENT_STATE_CHANGED);
    }
  }

  private void wake(TenantId tenantId, UUID incidentId, WakeEvent event) {
    Instant now = Timestamps.now(clock);
    records
        .markIncidentRuns(tenantId, incidentId, event, now)
        .forEach(runId -> handOffs.afterCommit(tenantId, runId));
  }
}
