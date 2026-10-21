package vigil.intake.api;

import java.util.UUID;
import vigil.shared.TenantId;

/** AR-E-03: a monitoring source's health changed. */
public record SourceHealthChanged(TenantId tenantId, UUID sourceId, Health health) {}
