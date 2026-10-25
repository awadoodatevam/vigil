package vigil.engine.api;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import vigil.shared.Completed;
import vigil.shared.StepCall;
import vigil.shared.Waiting;

/**
 * A step execution (DM-E-51). {@code stepState} is JSON text; {@code wakeEvents} is {@code null}
 * until the step stored a {@code Waiting} answer; other empty values are {@code null}.
 */
public record StepExecutionView(
    UUID id,
    UUID runId,
    String stepId,
    StepCall.StepType stepType,
    long visit,
    StepExecutionState state,
    Instant startedAt,
    Instant finishedAt,
    String namedOutput,
    Completed.ResultRef resultRef,
    Set<Waiting.WakeEvent> wakeEvents,
    UUID timerId,
    String stepState,
    long evaluationCount,
    String refusalReason,
    String testNote,
    long failedAttempts,
    Instant retryDueAt) {}
