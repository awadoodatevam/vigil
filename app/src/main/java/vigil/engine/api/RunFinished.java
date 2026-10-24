package vigil.engine.api;

import java.util.UUID;
import vigil.shared.TenantId;

/**
 * AR-E-10: a workflow run ended ({@code finished}, {@code cancelled} or {@code failed}); {@code
 * testReplayId} is {@code null} for live runs.
 */
public record RunFinished(
    TenantId tenantId, UUID runId, UUID incidentId, RunMode mode, UUID testReplayId) {}
