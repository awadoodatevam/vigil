package vigil.engine.api;

import java.time.Instant;
import java.util.UUID;

/**
 * A workflow run (DM-E-50). {@code contentJson} is the run's own copy of its content; empty values
 * are {@code null}.
 */
public record RunView(
    UUID id,
    UUID incidentId,
    RunMode mode,
    Long episode,
    UUID workflowVersionId,
    UUID draftId,
    Long draftRevision,
    String contentJson,
    UUID testReplayId,
    RunState state,
    WaitReason waitReason,
    boolean wakePending,
    String wakeReason,
    UUID currentStepExecutionId,
    String nextStepId,
    Instant simulatedNow,
    Instant startedAt,
    Instant finishedAt,
    EndReason endReason,
    String endDetail) {}
