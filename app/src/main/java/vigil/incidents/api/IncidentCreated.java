package vigil.incidents.api;

import java.util.UUID;
import vigil.shared.TenantId;

/** AR-E-05: an incident was created (DM-SM05-T1). */
public record IncidentCreated(TenantId tenantId, UUID incidentId) {}
