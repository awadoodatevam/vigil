package vigil.engine.api;

import java.util.UUID;
import vigil.shared.TenantId;

/**
 * AR-E-10: a step execution started, waits, finished or was cancelled; {@code testReplayId} is
 * {@code null} for live runs.
 */
public record StepChanged(
    TenantId tenantId,
    UUID runId,
    UUID incidentId,
    RunMode mode,
    UUID testReplayId,
    UUID stepExecutionId,
    StepExecutionState state) {}
