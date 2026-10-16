package vigil.tenancy.api;

import vigil.shared.TenantId;

/** AR-E-13: a tenant's settings changed. */
public record TenantSettingsChanged(TenantId tenantId) {}
