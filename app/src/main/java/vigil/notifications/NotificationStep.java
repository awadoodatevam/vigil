package vigil.notifications;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import vigil.incidents.api.IncidentView;
import vigil.incidents.api.NotifiedPurpose;
import vigil.notifications.Deliveries.Origin;
import vigil.notifications.Deliveries.Recipient;
import vigil.notifications.Deliveries.Request;
import vigil.notifications.api.DeliveryStatus;
import vigil.notifications.api.NotificationRecordView;
import vigil.notifications.api.NotificationsStepHandler;
import vigil.shared.Completed;
import vigil.shared.Key;
import vigil.shared.StepAnswer;
import vigil.shared.StepCall;
import vigil.shared.StepCancel;
import vigil.shared.TenantId;
import vigil.shared.TransactionGuard;

/**
 * The Notification step (DM-S-09, PM-S-09; P-22 spec Section 6.8): {@code start} and {@code
 * evaluate} do the same and always complete with {@code sent} or {@code failed}; {@code cancel}
 * does nothing.
 */
@Component
class NotificationStep implements NotificationsStepHandler {

  static final String BAD_PARAMETERS = "notification step parameters: recipients";
  private static final List<String> RECIPIENTS =
      List.of("owner_team", "informed_parties", "named_teams");
  private static final Key SENT = new Key("sent");
  private static final Key FAILED = new Key("failed");

  private final Deliveries deliveries;
  private final NotificationRecords records;

  NotificationStep(Deliveries deliveries, NotificationRecords records) {
    this.deliveries = deliveries;
    this.records = records;
  }

  @Override
  public StepAnswer start(StepCall call) {
    return step(call);
  }

  @Override
  public StepAnswer evaluate(StepCall call) {
    return step(call);
  }

  /** The step leaves nothing open (AR-R-21). */
  @Override
  public void cancel(StepCancel cancel) {
    // Nothing to cancel.
  }

  private StepAnswer step(StepCall call) {
    TransactionGuard.assertNoTransaction();
    Parameters parameters = parameters(call);
    TenantId tenantId = call.tenantId();
    IncidentView incident = deliveries.incident(tenantId, call.incidentId());
    Set<String> teams = new LinkedHashSet<>();
    if (parameters.recipients().contains("owner_team") && incident.ownerTeamKey() != null) {
      teams.add(incident.ownerTeamKey());
    }
    if (parameters.recipients().contains("informed_parties")) {
      incident.informedTeamKeys().stream().sorted().forEach(teams::add);
    }
    if (parameters.recipients().contains("named_teams")) {
      teams.addAll(parameters.namedTeams());
    }
    MessageTexts.IncidentText text = deliveries.incidentText(tenantId, incident);
    String link = deliveries.link(tenantId, "/m/acknowledge/" + incident.id());
    deliveries.deliver(
        new Request(
            tenantId,
            incident.id(),
            NotifiedPurpose.INCIDENT_NOTIFICATION,
            new Origin("vigil-engine", "StepExecution", call.stepExecutionId()),
            teams.stream().map(Recipient::team).toList(),
            MessageTexts.incidentNotification(text, link),
            link,
            deliveries.senderName(tenantId)));
    List<NotificationRecordView> own = records.recordsOfOrigin(tenantId, call.stepExecutionId());
    boolean sent = own.stream().anyMatch(r -> r.status() == DeliveryStatus.SENT);
    return new Completed(
        sent ? SENT : FAILED,
        own.isEmpty()
            ? null
            : new Completed.ResultRef(
                "vigil-notifications", "NotificationRecord", own.getFirst().id()));
  }

  /** The step's recipients, and its named team keys in the order written. */
  private record Parameters(Set<String> recipients, List<String> namedTeams) {}

  private static Parameters parameters(StepCall call) {
    if (call.stepType() != StepCall.StepType.NOTIFICATION) {
      throw new IllegalArgumentException(BAD_PARAMETERS);
    }
    JsonNode tree;
    try {
      tree = MessageTexts.JSON.readTree(call.parameters());
    } catch (JacksonException e) {
      throw new IllegalArgumentException(BAD_PARAMETERS, e);
    }
    JsonNode recipients = tree == null || !tree.isObject() ? null : tree.get("recipients");
    if (recipients == null || !recipients.isArray() || recipients.isEmpty()) {
      throw new IllegalArgumentException(BAD_PARAMETERS);
    }
    Set<String> chosen = new LinkedHashSet<>();
    for (JsonNode recipient : recipients) {
      if (!recipient.isString() || !RECIPIENTS.contains(recipient.stringValue())) {
        throw new IllegalArgumentException(BAD_PARAMETERS);
      }
      chosen.add(recipient.stringValue());
    }
    List<String> named = new ArrayList<>();
    if (chosen.contains("named_teams")) {
      JsonNode keys = tree.get("named_teams");
      if (keys == null || !keys.isArray()) {
        throw new IllegalArgumentException(BAD_PARAMETERS);
      }
      for (JsonNode key : keys) {
        if (!key.isString() || !key.stringValue().matches("^[A-Za-z0-9._-]{1,64}$")) {
          throw new IllegalArgumentException(BAD_PARAMETERS);
        }
        named.add(key.stringValue());
      }
    }
    return new Parameters(chosen, List.copyOf(named));
  }
}
