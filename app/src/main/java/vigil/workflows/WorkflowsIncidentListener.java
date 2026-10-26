package vigil.workflows;

import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import vigil.engine.api.EngineService;
import vigil.incidents.api.IncidentCreated;
import vigil.incidents.api.IncidentService;
import vigil.incidents.api.IncidentState;
import vigil.incidents.api.IncidentStateChanged;
import vigil.incidents.api.IncidentView;
import vigil.routing.api.RequestedBy;
import vigil.routing.api.RoutingService;
import vigil.shared.Actor;
import vigil.shared.TenantId;
import vigil.tenancy.api.TenantService;
import vigil.workflows.api.WorkflowVersionView;

/**
 * The AR-E-05 and AR-E-07 listener of vigil-workflows (PM-M11-07; P-41 spec Section 6.6): starts a
 * live run of the active version when an incident is created, after the routing evaluation, and a
 * new run when it is reopened (DM-R-22). A run exists once per episode, so a second delivery gives
 * the same result (AR-Q-05).
 */
@Component
@Profile("!migrate")
class WorkflowsIncidentListener {

  private static final Actor WORKFLOWS = Actor.module("vigil-workflows");

  private final TenantService tenants;
  private final IncidentService incidents;
  private final RoutingService routing;
  private final EngineService engine;
  private final WorkflowRecords records;

  WorkflowsIncidentListener(
      TenantService tenants,
      IncidentService incidents,
      RoutingService routing,
      EngineService engine,
      WorkflowRecords records) {
    this.tenants = tenants;
    this.incidents = incidents;
    this.routing = routing;
    this.engine = engine;
    this.records = records;
  }

  @ApplicationModuleListener
  public void onCreated(IncidentCreated event) {
    TenantId tenantId = event.tenantId();
    UUID incidentId = event.incidentId();
    Optional<WorkflowVersionView> version = activeVersion(tenantId);
    Optional<IncidentView> incident = version.flatMap(v -> startable(tenantId, incidentId));
    if (incident.isEmpty()
        || incident.get().reopenCount() > 0
        || engine.hasLiveRun(tenantId, incidentId, 1)) {
      return;
    }
    routing.evaluate(tenantId, WORKFLOWS, incidentId, RequestedBy.WORKFLOWS);
    engine.startLiveRun(
        tenantId, WORKFLOWS, incidentId, 1, version.get().id(), version.get().contentJson(), false);
  }

  @ApplicationModuleListener
  public void onStateChanged(IncidentStateChanged event) {
    if (event.from() != IncidentState.RESOLVED || event.to() != IncidentState.OPEN) {
      return;
    }
    TenantId tenantId = event.tenantId();
    UUID incidentId = event.incidentId();
    Optional<WorkflowVersionView> version = activeVersion(tenantId);
    Optional<IncidentView> incident = version.flatMap(v -> startable(tenantId, incidentId));
    if (incident.isEmpty()) {
      return;
    }
    long episode = incident.get().reopenCount() + 1;
    if (engine.hasLiveRun(tenantId, incidentId, episode)) {
      return;
    }
    engine.startLiveRun(
        tenantId,
        WORKFLOWS,
        incidentId,
        episode,
        version.get().id(),
        version.get().contentJson(),
        true);
  }

  /** The active version of an active tenant's workflow (DM-R-70). */
  private Optional<WorkflowVersionView> activeVersion(TenantId tenantId) {
    return tenants.isActive(tenantId) ? records.activeVersion(tenantId) : Optional.empty();
  }

  /** The incident when it is {@code forming} or {@code open} (DM-R-16). */
  private Optional<IncidentView> startable(TenantId tenantId, UUID incidentId) {
    return incidents
        .incident(tenantId, incidentId)
        .filter(
            incident ->
                incident.state() == IncidentState.FORMING
                    || incident.state() == IncidentState.OPEN);
  }
}
