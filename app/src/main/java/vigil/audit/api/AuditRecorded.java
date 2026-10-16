package vigil.audit.api;

import java.util.UUID;

/** The identifier and the per-tenant sequence of an appended audit event. */
public record AuditRecorded(UUID id, long sequence) {}
