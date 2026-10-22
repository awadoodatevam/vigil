package vigil.incidents.api;

import java.util.UUID;
import vigil.shared.TenantId;

/** AR-E-08: an incident changed (DM-E32-R2). */
public record IncidentChanged(TenantId tenantId, UUID incidentId) {}
