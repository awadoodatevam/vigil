package vigil.incidents.api;

import java.util.UUID;
import vigil.shared.TenantId;

/** AR-E-06: an incident was acknowledged in {@code episode} (DM-E-35). */
public record IncidentAcknowledged(TenantId tenantId, UUID incidentId, long episode) {}
