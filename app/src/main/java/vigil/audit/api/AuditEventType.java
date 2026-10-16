package vigil.audit.api;

import java.util.Locale;

/** The audit event types of DM-E08-R1 (complete list, in its order). */
public enum AuditEventType {
  INCIDENT_CREATED,
  ALERT_GROUPED,
  HYPOTHESIS_CHANGED,
  ASSIGNMENT_CHANGED,
  INCIDENT_ACKNOWLEDGED,
  INCIDENT_ESCALATED,
  AI_RESULT_ACCEPTED,
  AI_RESULT_REJECTED,
  POLICY_CHECK_RESULT,
  APPROVAL_REQUESTED,
  APPROVAL_DECIDED,
  ACTION_EXECUTED,
  ACTION_RECONCILED,
  RECOVERY_VERIFIED,
  INCIDENT_STATE_CHANGED,
  NOTIFICATION_SENT,
  TICKET_CHANGED,
  CONFIGURATION_CHANGED;

  /** The stored key (Example: {@code incident_created}). */
  public String key() {
    return name().toLowerCase(Locale.ROOT);
  }
}
