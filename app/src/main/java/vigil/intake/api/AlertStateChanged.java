package vigil.intake.api;

import java.util.UUID;
import vigil.shared.TenantId;

/** AR-E-02: an alert's status changed. */
public record AlertStateChanged(TenantId tenantId, UUID alertId, AlertStatus status) {}
