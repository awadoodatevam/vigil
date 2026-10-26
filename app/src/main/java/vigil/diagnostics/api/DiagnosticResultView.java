package vigil.diagnostics.api;

import java.time.Instant;
import java.util.UUID;

/**
 * A diagnostic result (DM-E-54). {@code valuesJson} is the ordered array of {@code {name, value}}
 * objects of a {@code succeeded} result; empty values are {@code null}.
 */
public record DiagnosticResultView(
    UUID id,
    UUID incidentId,
    UUID runId,
    UUID stepExecutionId,
    String diagnosticKey,
    String componentKey,
    String targetKey,
    UUID operationRunId,
    DiagnosticOutcome outcome,
    String valuesJson,
    Instant collectedAt,
    String error) {}
