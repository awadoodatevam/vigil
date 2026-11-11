package vigil.notifications;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vigil.audit.api.AuditEntry;
import vigil.audit.api.AuditEventType;
import vigil.audit.api.AuditService;
import vigil.connectors.api.ConnectorInstanceView;
import vigil.connectors.api.ConnectorKind;
import vigil.connectors.api.ConnectorService;
import vigil.connectors.api.Delivery;
import vigil.connectors.api.MessageLine;
import vigil.connectors.api.NotificationChannelConnector;
import vigil.identity.api.IdentityService;
import vigil.identity.api.TeamView;
import vigil.identity.api.UserView;
import vigil.incidents.api.IncidentService;
import vigil.incidents.api.IncidentState;
import vigil.incidents.api.IncidentView;
import vigil.incidents.api.NotifiedPurpose;
import vigil.investigation.api.InvestigationService;
import vigil.notifications.api.Channel;
import vigil.notifications.api.DeliveryAddressView;
import vigil.notifications.api.DeliveryStatus;
import vigil.notifications.api.NotificationRecordView;
import vigil.notifications.api.RecipientKind;
import vigil.notifications.api.SendOutcome;
import vigil.shared.Actor;
import vigil.shared.Ids;
import vigil.shared.Key;
import vigil.shared.TenantId;
import vigil.shared.Timestamps;
import vigil.tenancy.api.TenantService;
import vigil.tenancy.api.TenantSettingsView;

/**
 * Delivers one request (P-22 spec Section 6.6; PM-M20-02, PM-M20-04, DM-R-65, DM-E37-R1): for each
 * recipient and each of its active addresses one message, sent with no transaction open and
 * recorded in a transaction opened after the send returned, with its audit event and the notified
 * team. Also prepares what the texts of a request need.
 */
@Component
class Deliveries {

  static final Actor NOTIFICATIONS = Actor.module("vigil-notifications");
  static final String NO_LINK_BASE = "the tenant has no public address for links";
  private static final Logger LOG = LoggerFactory.getLogger(Deliveries.class);

  /** One recipient: a team, or a user with the team through which it was chosen. */
  record Recipient(RecipientKind kind, String key, String viaTeamKey) {

    static Recipient team(String teamKey) {
      return new Recipient(RecipientKind.TEAM, teamKey, null);
    }

    /** The team recorded as notified when a message to this recipient is delivered. */
    String notifiedTeam() {
      return kind == RecipientKind.TEAM ? key : viaTeamKey;
    }
  }

  /** The record a request is for (DM-E-78 {@code origin_ref}). */
  record Origin(String module, String entity, UUID id) {}

  /** A request: its lines and its link, {@code null} when the tenant has no public address. */
  record Request(
      TenantId tenantId,
      UUID incidentId,
      NotifiedPurpose purpose,
      Origin origin,
      List<Recipient> recipients,
      List<MessageLine> lines,
      String link,
      String senderName) {}

  private final NotificationRecords records;
  private final ConnectorService connectorService;
  private final Map<ConnectorKind, NotificationChannelConnector> connectors =
      new EnumMap<>(ConnectorKind.class);
  private final IncidentService incidents;
  private final IdentityService identity;
  private final InvestigationService investigation;
  private final TenantService tenants;
  private final AuditService audit;
  private final Clock clock;
  private final TransactionTemplate tx;

  Deliveries(
      NotificationRecords records,
      ConnectorService connectorService,
      List<NotificationChannelConnector> channelConnectors,
      IncidentService incidents,
      IdentityService identity,
      InvestigationService investigation,
      TenantService tenants,
      AuditService audit,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.records = records;
    this.connectorService = connectorService;
    channelConnectors.forEach(connector -> connectors.put(connector.kind(), connector));
    this.incidents = incidents;
    this.identity = identity;
    this.investigation = investigation;
    this.tenants = tenants;
    this.audit = audit;
    this.clock = clock;
    this.tx = new TransactionTemplate(transactionManager);
  }

  /** The incident; {@code IllegalStateException} when the tenant does not have it. */
  IncidentView incident(TenantId tenantId, UUID incidentId) {
    return incidents
        .incident(tenantId, incidentId)
        .orElseThrow(() -> new IllegalStateException("incident " + incidentId + " not found"));
  }

  /**
   * What every message of the incident shows (Section 6.7): the label, the owner and informed team
   * names and the summary, read in its own transaction before any send (DM-E69-R2).
   */
  MessageTexts.IncidentText incidentText(TenantId tenantId, IncidentView incident) {
    if (incident.number() == null || incident.severity() == null) {
      throw new IllegalStateException("incident " + incident.id() + " has no number or severity");
    }
    String summary =
        tx.execute(status -> investigation.summaryForMessage(tenantId, incident.id())).text();
    return new MessageTexts.IncidentText(
        tenants.settings(tenantId).environmentLabel(),
        incident,
        incident.ownerTeamKey() == null ? null : teamName(tenantId, incident.ownerTeamKey()),
        incident.informedTeamKeys().stream().map(key -> teamName(tenantId, key)).toList(),
        summary);
  }

  /** {@code {base}{path}} with the tenant's public base address; {@code null} without one. */
  String link(TenantId tenantId, String path) {
    return tenants.publicBaseAddress(tenantId).map(base -> base + path).orElse(null);
  }

  /** The product display name, the email sender's name. */
  String senderName(TenantId tenantId) {
    TenantSettingsView settings = tenants.settings(tenantId);
    return settings.productDisplayName();
  }

  /** The team's name, or its key when the team is not found. */
  String teamName(TenantId tenantId, String teamKey) {
    return identity.team(tenantId, new Key(teamKey)).map(TeamView::name).orElse(teamKey);
  }

  /** Delivers the request; {@code SENT} when at least one message is delivered, now or earlier. */
  SendOutcome deliver(Request request) {
    TenantId tenantId = request.tenantId();
    Map<Channel, Optional<ConnectorInstanceView>> instances = new EnumMap<>(Channel.class);
    boolean delivered = false;
    for (Recipient recipient : request.recipients()) {
      for (DeliveryAddressView address :
          records.activeAddresses(tenantId, recipient.kind(), recipient.key())) {
        Channel channel = address.channel();
        if (records.sent(
            tenantId, request.origin().id(), recipient.kind(), recipient.key(), channel)) {
          delivered = true;
          continue;
        }
        Delivery delivery;
        if (request.link() == null) {
          delivery = new Delivery.Failed(NO_LINK_BASE);
        } else {
          Optional<ConnectorInstanceView> instance =
              instances.computeIfAbsent(
                  channel,
                  c -> connectorService.activeInstances(tenantId, kind(c)).stream().findFirst());
          delivery =
              instance.isEmpty()
                  ? new Delivery.Failed(
                      "no active " + MessageTexts.channelName(channel) + " connector instance")
                  : connectors
                      .get(kind(channel))
                      .send(
                          tenantId,
                          instance.get(),
                          address.address(),
                          address.credentialRefId(),
                          request.senderName(),
                          request.lines());
        }
        delivered |= record(request, recipient, address, delivery);
      }
    }
    return delivered ? SendOutcome.SENT : SendOutcome.FAILED;
  }

  /**
   * One transaction: the record, its audit event and, when sent to an {@code open} or {@code
   * resolved} incident, the notified team. Returns whether it was sent.
   */
  private boolean record(
      Request request, Recipient recipient, DeliveryAddressView address, Delivery delivery) {
    TenantId tenantId = request.tenantId();
    boolean sent = delivery instanceof Delivery.Sent;
    DeliveryStatus status = sent ? DeliveryStatus.SENT : DeliveryStatus.FAILED;
    String error = delivery instanceof Delivery.Failed failed ? failed.reason() : null;
    Instant now = Timestamps.now(clock);
    NotificationRecordView record =
        new NotificationRecordView(
            Ids.newId(),
            request.incidentId(),
            request.purpose(),
            request.origin().module(),
            request.origin().entity(),
            request.origin().id(),
            recipient.kind(),
            recipient.key(),
            recipient.viaTeamKey(),
            address.channel(),
            address.address(),
            MessageTexts.content(request.lines()),
            request.link() == null ? "" : request.link(),
            status,
            error,
            now);
    String recipientName = recipientName(tenantId, recipient);
    tx.executeWithoutResult(
        transaction -> {
          records.insertRecord(tenantId, record);
          audit.append(
              new AuditEntry(
                  tenantId,
                  now,
                  NOTIFICATIONS,
                  AuditEventType.NOTIFICATION_SENT,
                  "NotificationRecord",
                  record.id(),
                  record.incidentId(),
                  MessageTexts.auditSummary(
                      record.purpose(), recipientName, record.channel(), status),
                  MessageTexts.auditDetails(record.purpose(), record.channel(), status)));
          if (sent) {
            IncidentState state =
                incidents
                    .incident(tenantId, record.incidentId())
                    .map(IncidentView::state)
                    .orElse(null);
            if (state == IncidentState.OPEN || state == IncidentState.RESOLVED) {
              incidents.recordNotifiedTeam(
                  tenantId,
                  NOTIFICATIONS,
                  record.incidentId(),
                  new Key(recipient.notifiedTeam()),
                  record.purpose());
            }
          }
        });
    if (!sent) {
      LOG.warn(
          "{} message to {} {} by {} failed for incident {} of tenant {}: {}",
          record.purpose().key(),
          recipient.kind().key(),
          recipient.key(),
          address.channel().key(),
          record.incidentId(),
          tenantId.value(),
          error);
    }
    return sent;
  }

  private String recipientName(TenantId tenantId, Recipient recipient) {
    if (recipient.kind() == RecipientKind.TEAM) {
      return teamName(tenantId, recipient.key());
    }
    return identity
        .userByUsername(tenantId, new Key(recipient.key()))
        .map(UserView::displayName)
        .orElse(recipient.key());
  }

  private static ConnectorKind kind(Channel channel) {
    return channel == Channel.TEAMS ? ConnectorKind.MS_TEAMS : ConnectorKind.EMAIL;
  }
}
