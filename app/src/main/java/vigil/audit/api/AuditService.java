package vigil.audit.api;

import vigil.shared.TenantId;

/**
 * The audit log (PM-M03). It offers no operation that changes or deletes an event (PM-M03-02,
 * DM-E08-R2).
 */
public interface AuditService {

  /**
   * Appends an event in the caller's transaction (AR-R-11): it commits or rolls back with the
   * change it reports.
   */
  AuditRecorded append(AuditEntry entry);

  /** The tenant's events that match every given filter, newest first (PM-M03-03, DC-S11-03). */
  AuditPage query(TenantId tenantId, AuditQuery query);
}
