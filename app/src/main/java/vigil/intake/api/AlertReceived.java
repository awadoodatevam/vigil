package vigil.intake.api;

import java.util.UUID;
import vigil.shared.TenantId;

/** AR-E-01: a new alert with its {@code sequence}. */
public record AlertReceived(TenantId tenantId, UUID alertId, long sequence) {}
