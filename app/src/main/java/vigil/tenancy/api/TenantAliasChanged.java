package vigil.tenancy.api;

import vigil.shared.TenantId;

/**
 * AR-E-14: a host name now points to {@code tenantId}. Installation-level, so it carries no
 * separate tenant identifier; {@code previousTenantId} is {@code null} for a host's first revision.
 */
public record TenantAliasChanged(String hostName, TenantId tenantId, TenantId previousTenantId) {}
