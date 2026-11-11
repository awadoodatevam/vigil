package vigil.notifications;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import vigil.connectors.api.MessageLine;
import vigil.incidents.api.EscalationCause;
import vigil.incidents.api.IncidentService;
import vigil.incidents.api.IncidentView;
import vigil.incidents.api.NotifiedPurpose;
import vigil.notifications.Deliveries.Origin;
import vigil.notifications.Deliveries.Recipient;
import vigil.notifications.Deliveries.Request;
import vigil.notifications.api.ApprovalRecipient;
import vigil.notifications.api.DeliveryAddressView;
import vigil.notifications.api.NotificationRecordView;
import vigil.notifications.api.NotificationsService;
import vigil.notifications.api.RecipientKind;
import vigil.notifications.api.SendOutcome;
import vigil.shared.Key;
import vigil.shared.TenantId;
import vigil.shared.TransactionGuard;

/**
 * The requests of other modules to vigil-notifications (PM-M20; P-22 spec Section 6.9): the reads,
 * the escalation message of vigil-routing and the approval request of vigil-actions.
 */
@Service
class Notifications implements NotificationsService {

  private final NotificationRecords records;
  private final Deliveries deliveries;
  private final IncidentService incidents;

  Notifications(NotificationRecords records, Deliveries deliveries, IncidentService incidents) {
    this.records = records;
    this.deliveries = deliveries;
    this.incidents = incidents;
  }

  @Override
  public List<DeliveryAddressView> deliveryAddresses(TenantId tenantId) {
    required(tenantId);
    return List.copyOf(records.addresses(tenantId));
  }

  @Override
  public List<NotificationRecordView> records(TenantId tenantId, UUID incidentId) {
    required(tenantId, incidentId);
    return List.copyOf(records.records(tenantId, incidentId));
  }

  /**
   * {@inheritDoc}
   *
   * <p>external call: not inside a transaction
   */
  @Override
  public SendOutcome notifyEscalation(
      TenantId tenantId,
      UUID incidentId,
      UUID escalationId,
      long level,
      List<Key> teamKeys,
      EscalationCause cause,
      Duration timer) {
    TransactionGuard.assertNoTransaction();
    required(tenantId, incidentId, escalationId, teamKeys, cause);
    if (teamKeys.stream().anyMatch(key -> key == null)) {
      throw new IllegalArgumentException("a required argument is missing");
    }
    if ((cause == EscalationCause.ACKNOWLEDGEMENT_TIMEOUT) != (timer != null)) {
      throw new IllegalArgumentException("timer: required for acknowledgement_timeout only");
    }
    IncidentView incident = deliveries.incident(tenantId, incidentId);
    MessageTexts.IncidentText text = deliveries.incidentText(tenantId, incident);
    String link = deliveries.link(tenantId, "/m/acknowledge/" + incidentId);
    List<String> names =
        teamKeys.stream().map(key -> deliveries.teamName(tenantId, key.value())).toList();
    List<MessageLine> lines =
        MessageTexts.escalation(
            text,
            level,
            names,
            cause,
            timer,
            incidents.evidenceSummary(tenantId, incidentId),
            link);
    return deliveries.deliver(
        new Request(
            tenantId,
            incidentId,
            NotifiedPurpose.ESCALATION,
            new Origin("vigil-incidents", "IncidentEscalation", escalationId),
            teamKeys.stream().map(key -> Recipient.team(key.value())).distinct().toList(),
            lines,
            link,
            deliveries.senderName(tenantId)));
  }

  /**
   * {@inheritDoc}
   *
   * <p>external call: not inside a transaction
   */
  @Override
  public SendOutcome notifyApprovalRequest(
      TenantId tenantId,
      UUID incidentId,
      UUID approvalRequestId,
      List<ApprovalRecipient> recipients,
      String actionName,
      String targetName,
      Instant expiresAt) {
    TransactionGuard.assertNoTransaction();
    required(tenantId, incidentId, approvalRequestId, recipients, actionName, targetName);
    required(expiresAt);
    if (recipients.stream()
        .anyMatch(r -> r == null || r.username() == null || r.viaTeamKey() == null)) {
      throw new IllegalArgumentException("a required argument is missing");
    }
    if (recipients.isEmpty()) {
      throw new IllegalArgumentException("recipients: at least one");
    }
    IncidentView incident = deliveries.incident(tenantId, incidentId);
    MessageTexts.IncidentText text = deliveries.incidentText(tenantId, incident);
    String link = deliveries.link(tenantId, "/m/approve/" + approvalRequestId);
    List<MessageLine> lines =
        MessageTexts.approvalRequest(text, actionName, targetName, expiresAt, link);
    return deliveries.deliver(
        new Request(
            tenantId,
            incidentId,
            NotifiedPurpose.APPROVAL_REQUEST,
            new Origin("vigil-actions", "ApprovalRequest", approvalRequestId),
            recipients.stream()
                .map(
                    r ->
                        new Recipient(
                            RecipientKind.USER, r.username().value(), r.viaTeamKey().value()))
                .toList(),
            lines,
            link,
            deliveries.senderName(tenantId)));
  }

  private static void required(Object... arguments) {
    for (Object argument : arguments) {
      if (argument == null) {
        throw new IllegalArgumentException("a required argument is missing");
      }
    }
  }
}
