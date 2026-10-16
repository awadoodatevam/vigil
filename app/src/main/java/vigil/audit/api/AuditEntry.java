package vigil.audit.api;

import java.time.Instant;
import java.util.UUID;
import vigil.shared.Actor;
import vigil.shared.TenantId;

/**
 * An audit event to append (DM-E-08). {@code incidentId} and {@code detailsJson} may be {@code
 * null}; for {@link AuditEventType#CONFIGURATION_CHANGED} the details are {@code {area, before,
 * after}} (DM-C-07).
 */
public record AuditEntry(
    TenantId tenantId,
    Instant occurredAt,
    Actor actor,
    AuditEventType eventType,
    String subjectType,
    UUID subjectId,
    UUID incidentId,
    String summary,
    String detailsJson) {}
