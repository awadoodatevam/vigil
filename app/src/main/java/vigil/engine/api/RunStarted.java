package vigil.engine.api;

import java.util.UUID;
import vigil.shared.TenantId;

/** AR-E-10: a workflow run started; {@code testReplayId} is {@code null} for live runs. */
public record RunStarted(
    TenantId tenantId, UUID runId, UUID incidentId, RunMode mode, UUID testReplayId) {}
