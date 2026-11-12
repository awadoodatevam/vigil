package vigil.actions.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An action execution (DM-E-60); {@code recheckJson} is {@code null} before the first recheck, the
 * other empty values are {@code null}.
 */
public record ActionExecutionView(
    UUID id,
    UUID incidentId,
    UUID runId,
    UUID stepExecutionId,
    UUID proposalId,
    String actionKey,
    List<UUID> approvalRequestIds,
    long attemptNumber,
    ExecutionState state,
    String recheckJson,
    UUID operationRunId,
    Instant startedAt,
    Instant finishedAt) {

  public ActionExecutionView {
    approvalRequestIds = List.copyOf(approvalRequestIds);
  }
}
