package vigil.actions.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An approval request (DM-E-58); {@code snapshotJson} is the approval snapshot (DM-V-12), the lists
 * are sorted, and the empty values are {@code null}.
 */
public record ApprovalRequestView(
    UUID id,
    UUID incidentId,
    UUID runId,
    UUID stepExecutionId,
    UUID proposalId,
    long sequenceInRun,
    PolicyOutcome policyOutcome,
    List<String> eligibleTeamKeys,
    List<UUID> excludedUserIds,
    String snapshotJson,
    String snapshotHash,
    Instant expiresAt,
    RequestState state,
    RefusalReason refusalReason,
    Instant decidedAt,
    UUID decidedBy,
    Instant createdAt) {

  public ApprovalRequestView {
    eligibleTeamKeys = List.copyOf(eligibleTeamKeys);
    excludedUserIds = List.copyOf(excludedUserIds);
  }
}
