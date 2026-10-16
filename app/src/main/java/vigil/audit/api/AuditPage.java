package vigil.audit.api;

import java.util.List;

/**
 * One page of an audit query: the rows, the number of all matching rows, and the position of the
 * next page ({@code null} when no further row exists).
 */
public record AuditPage(List<AuditEventView> items, long total, AuditPosition next) {}
