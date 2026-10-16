package vigil.audit.api;

import java.time.Instant;

/** The position after which the next page of an audit query starts (newest first). */
public record AuditPosition(Instant occurredAt, long sequence) {}
