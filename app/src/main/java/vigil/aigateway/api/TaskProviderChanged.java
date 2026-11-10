package vigil.aigateway.api;

import vigil.shared.TenantId;

/** The event "task provider changed" (AR-E-11, PM-EV-12); published by P-39. */
public record TaskProviderChanged(TenantId tenantId, AiTask task, String providerKey) {}
