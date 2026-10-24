package vigil.engine;

import java.time.Clock;
import org.springframework.context.annotation.Profile;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import vigil.actions.api.ApprovalDecided;
import vigil.shared.Timestamps;
import vigil.tenancy.api.TenantService;

/**
 * The AR-E-09 listener of vigil-engine: wakes the named run, a waiting one only at the event's step
 * execution (DM-R-67). A run is marked once, so a second delivery gives the same result (AR-Q-05).
 */
@Component
@Profile("!migrate")
class EngineApprovalListener {

  private final TenantService tenants;
  private final EngineRecords records;
  private final HandOffs handOffs;
  private final Clock clock;

  EngineApprovalListener(
      TenantService tenants, EngineRecords records, HandOffs handOffs, Clock clock) {
    this.tenants = tenants;
    this.records = records;
    this.handOffs = handOffs;
    this.clock = clock;
  }

  @ApplicationModuleListener
  public void on(ApprovalDecided event) {
    if (tenants.isActive(event.tenantId())) {
      records
          .markApproval(
              event.tenantId(), event.runId(), event.stepExecutionId(), Timestamps.now(clock))
          .forEach(runId -> handOffs.afterCommit(event.tenantId(), runId));
    }
  }
}
