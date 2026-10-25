package vigil.actions.api;

import java.util.UUID;
import vigil.shared.TenantId;

/** AR-E-09: an approval request of a run's step execution was decided. */
public record ApprovalDecided(
    TenantId tenantId, UUID approvalRequestId, UUID runId, UUID stepExecutionId) {}
