package vigil.incidents.api;

import java.util.UUID;
import vigil.shared.TenantId;

/** AR-E-07: an incident moved from one state to another (DM-SM-05, after its creation). */
public record IncidentStateChanged(
    TenantId tenantId, UUID incidentId, IncidentState from, IncidentState to) {}
