package vigil.ticketing.api;

import java.time.Instant;
import java.util.UUID;

/**
 * A ticket sync record (DM-E-76); {@code contentSentJson} is the stored JSON object as text.
 * Nullable fields are {@code null} when empty.
 */
public record SyncRecordView(
    UUID id,
    UUID incidentId,
    UUID ticketLinkId,
    SyncOperation operation,
    SyncRequester requestedBy,
    UUID stepExecutionId,
    UUID escalationId,
    String contentSentJson,
    SyncOutcome outcome,
    String error,
    Instant at,
    Instant finishedAt) {}
