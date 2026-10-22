package vigil.incidents.api;

import java.time.Instant;
import java.util.UUID;

/**
 * An incident alert membership (DM-E-34); {@code reasonJson} and {@code finalReasonJson} are the
 * stored JSON as text ({@code finalReasonJson} {@code null} when not set).
 */
public record MembershipView(
    UUID id,
    UUID alertId,
    AlertCopy alert,
    String reasonJson,
    String finalReasonJson,
    UUID groupingDecisionId,
    Instant joinedAt,
    Instant endedAt) {}
