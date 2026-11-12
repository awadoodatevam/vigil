package vigil.api;

import java.time.Clock;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import vigil.actions.api.ApprovalDecided;
import vigil.engine.api.RunFinished;
import vigil.engine.api.RunMode;
import vigil.engine.api.RunStarted;
import vigil.engine.api.StepChanged;
import vigil.incidents.api.IncidentChanged;
import vigil.intake.api.AlertReceived;
import vigil.intake.api.AlertStateChanged;
import vigil.intake.api.SourceHealthChanged;
import vigil.shared.TenantId;
import vigil.shared.Timestamps;
import vigil.tenancy.api.TenantService;
import vigil.tenancy.api.TenantSettingsChanged;

/**
 * The live update producers (AR-I-04, AR-R-14, PM-M23-04): each event of a change becomes the
 * notices of its topics, after the change's commit. Nothing is done for a tenant that is not active
 * (AR-R-19); nothing is read or written, so a second delivery sends the same notices again
 * (AR-Q-05).
 */
@Component
@Profile("!migrate")
class LiveProducers {

  private final TenantService tenants;
  private final LiveHub hub;
  private final Clock clock;

  LiveProducers(TenantService tenants, LiveHub hub, Clock clock) {
    this.tenants = tenants;
    this.hub = hub;
    this.clock = clock;
  }

  @ApplicationModuleListener
  public void on(AlertReceived event) {
    publish(event.tenantId(), event.alertId(), topic(LiveTopic.Kind.ALERTS, null));
  }

  @ApplicationModuleListener
  public void on(AlertStateChanged event) {
    publish(event.tenantId(), event.alertId(), topic(LiveTopic.Kind.ALERTS, null));
  }

  @ApplicationModuleListener
  public void on(SourceHealthChanged event) {
    publish(event.tenantId(), event.sourceId(), topic(LiveTopic.Kind.SOURCES, null));
  }

  @ApplicationModuleListener
  public void on(IncidentChanged event) {
    publish(
        event.tenantId(),
        event.incidentId(),
        topic(LiveTopic.Kind.INCIDENTS, null),
        topic(LiveTopic.Kind.INCIDENT, event.incidentId()));
  }

  @ApplicationModuleListener
  public void on(RunStarted event) {
    run(event.tenantId(), event.mode(), event.runId(), event.incidentId());
  }

  @ApplicationModuleListener
  public void on(StepChanged event) {
    run(event.tenantId(), event.mode(), event.runId(), event.incidentId());
  }

  @ApplicationModuleListener
  public void on(RunFinished event) {
    run(event.tenantId(), event.mode(), event.runId(), event.incidentId());
  }

  @ApplicationModuleListener
  public void on(ApprovalDecided event) {
    publish(
        event.tenantId(),
        event.approvalRequestId(),
        topic(LiveTopic.Kind.APPROVAL, event.approvalRequestId()));
  }

  @ApplicationModuleListener
  public void on(TenantSettingsChanged event) {
    publish(event.tenantId(), event.tenantId().value(), topic(LiveTopic.Kind.TENANT, null));
  }

  /** AR-E-10: live runs only; test-mode runs have no topic in this package (P-36). */
  private void run(TenantId tenantId, RunMode mode, UUID runId, UUID incidentId) {
    if (mode != RunMode.LIVE) {
      return;
    }
    publish(
        tenantId,
        runId,
        topic(LiveTopic.Kind.RUN, runId),
        topic(LiveTopic.Kind.INCIDENT, incidentId),
        topic(LiveTopic.Kind.INCIDENTS, null));
  }

  private void publish(TenantId tenantId, UUID id, LiveTopic... topics) {
    if (!tenants.isActive(tenantId)) {
      return;
    }
    for (LiveTopic topic : topics) {
      hub.publish(tenantId, topic, id, Timestamps.now(clock));
    }
  }

  private static LiveTopic topic(LiveTopic.Kind kind, UUID id) {
    return new LiveTopic(kind, id);
  }
}
