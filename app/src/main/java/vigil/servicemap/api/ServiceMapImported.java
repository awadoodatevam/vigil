package vigil.servicemap.api;

import vigil.shared.TenantId;

/** AR-E-04: a file import created a new service map generation. */
public record ServiceMapImported(TenantId tenantId, long generation) {}
