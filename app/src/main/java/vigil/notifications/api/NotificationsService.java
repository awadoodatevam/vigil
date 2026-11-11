package vigil.notifications.api;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import vigil.incidents.api.EscalationCause;
import vigil.shared.Key;
import vigil.shared.TenantId;

/** Delivery addresses, notification records and the send requests of other modules (PM-M20). */
public interface NotificationsService {

  /** By subject kind, subject key, channel, then id. */
  List<DeliveryAddressView> deliveryAddresses(TenantId tenantId);

  /** The incident's notification records, by {@code at}, then id. */
  List<NotificationRecordView> records(TenantId tenantId, UUID incidentId);

  /**
   * PM-M20-01, DC-OUT-06, from vigil-routing: the escalation message to the level's teams, in the
   * order given; {@code timer} is required for {@code acknowledgement_timeout} and {@code null} for
   * {@code escalate_now}. Sends only what is not yet sent for the escalation (DM-R-65).
   *
   * <p>external call: not inside a transaction
   */
  SendOutcome notifyEscalation(
      TenantId tenantId,
      UUID incidentId,
      UUID escalationId,
      long level,
      List<Key> teamKeys,
      EscalationCause cause,
      Duration timer);

  /**
   * PM-M20-01, DC-OUT-07, from vigil-actions: the approval request message to the users, in the
   * order given, at least one. Sends only what is not yet sent for the request (DM-R-65).
   *
   * <p>external call: not inside a transaction
   */
  SendOutcome notifyApprovalRequest(
      TenantId tenantId,
      UUID incidentId,
      UUID approvalRequestId,
      List<ApprovalRecipient> recipients,
      String actionName,
      String targetName,
      Instant expiresAt);
}
