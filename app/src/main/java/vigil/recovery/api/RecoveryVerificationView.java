package vigil.recovery.api;

import java.time.Instant;
import java.util.UUID;

/**
 * A recovery verification (DM-E-62); {@code windowStartedAt} and {@code decidedAt} are {@code null}
 * when empty.
 */
public record RecoveryVerificationView(
    UUID id,
    UUID incidentId,
    UUID runId,
    UUID stepExecutionId,
    String recoveryCheckKey,
    String definitionSnapshotJson,
    VerificationState state,
    Instant startedAt,
    Instant maxWaitUntil,
    Instant windowStartedAt,
    String lastEvaluationJson,
    Instant decidedAt) {}
