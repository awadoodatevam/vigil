package vigil.audit.api;

import java.time.Instant;
import java.util.UUID;
import vigil.shared.Actor;

/** A stored audit event. */
public record AuditEventView(
    UUID id,
    long sequence,
    Instant occurredAt,
    Actor actor,
    AuditEventType eventType,
    String subjectType,
    UUID subjectId,
    UUID incidentId,
    String summary,
    String detailsJson) {}
