package vigil.audit.api;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * An audit query (PM-M03-03). Every filter may be {@code null} or empty (no filter); {@code
 * subjectType} and {@code subjectId} are both set or both {@code null}; {@code limit} is 1 to 50.
 */
public record AuditQuery(
    Instant from,
    Instant to,
    AuditActorFilter actor,
    Set<AuditEventType> eventTypes,
    Set<UUID> incidentIds,
    String subjectType,
    UUID subjectId,
    int limit,
    AuditPosition after) {}
