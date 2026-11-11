package vigil.notifications;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import vigil.connectors.api.MessageLine;
import vigil.incidents.api.AffectedServiceCopy;
import vigil.incidents.api.EscalationCause;
import vigil.incidents.api.IncidentView;
import vigil.incidents.api.NotifiedPurpose;
import vigil.notifications.api.Channel;
import vigil.notifications.api.DeliveryStatus;

/**
 * The outbound texts of vigil-notifications (DC-OUT-00, DC-OUT-05 to DC-OUT-07, DM-E02-R2,
 * DC-TX-00, DC-TX-01), the record content and the audit texts (P-22 spec Sections 6.7 and 6.13).
 */
final class MessageTexts {

  static final JsonMapper JSON = JsonMapper.builder().build();
  static final Comparator<String> CODE_POINTS =
      Comparator.comparing(text -> text.codePoints().toArray(), Arrays::compare);
  static final int CONTENT_MAX = 4000;
  private static final DateTimeFormatter OUTBOUND =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);
  private static final long SECONDS_RULE = 120;

  private MessageTexts() {}

  /** What every message of an incident shows: the names are already resolved. */
  record IncidentText(
      String environmentLabel,
      IncidentView incident,
      String ownerTeamName,
      List<String> informedTeamNames,
      String summary) {}

  /** DC-OUT-05, the incident notification; {@code link} {@code null} when there is none. */
  static List<MessageLine> incidentNotification(IncidentText text, String link) {
    IncidentView incident = text.incident();
    List<MessageLine> lines = new ArrayList<>();
    lines.add(
        MessageLine.of(
            prefix(text.environmentLabel())
                + "["
                + severity(incident)
                + "] "
                + number(incident)
                + " "
                + incident.title()));
    lines.add(MessageLine.of(parties(text)));
    List<String> services =
        incident.affectedServices().stream().map(AffectedServiceCopy::name).toList();
    lines.add(MessageLine.of("Customer impact: " + list(services, "none identified") + "."));
    lines.add(MessageLine.of("Summary: " + text.summary()));
    lines.add(linked("Acknowledge: ", link));
    return List.copyOf(lines);
  }

  /** DC-OUT-06, the escalation message; {@code timer} {@code null} for {@code escalate_now}. */
  static List<MessageLine> escalation(
      IncidentText text,
      long level,
      List<String> teamNames,
      EscalationCause cause,
      Duration timer,
      List<String> evidence,
      String link) {
    IncidentView incident = text.incident();
    List<MessageLine> lines = new ArrayList<>();
    lines.add(
        MessageLine.of(
            prefix(text.environmentLabel())
                + "Escalation level "
                + level
                + ": ["
                + severity(incident)
                + "] "
                + number(incident)
                + " "
                + incident.title()));
    lines.add(
        MessageLine.of(
            "Escalated to " + list(teamNames, "none") + " (" + cause(cause, timer) + ")."));
    lines.add(MessageLine.of(parties(text)));
    lines.add(MessageLine.of("Summary: " + text.summary()));
    lines.add(MessageLine.of("Evidence:"));
    evidence.forEach(entry -> lines.add(MessageLine.of("- " + entry)));
    lines.add(linked("Acknowledge: ", link));
    return List.copyOf(lines);
  }

  /** DC-OUT-07, the approval request message. */
  static List<MessageLine> approvalRequest(
      IncidentText text, String actionName, String targetName, Instant expiresAt, String link) {
    IncidentView incident = text.incident();
    List<MessageLine> lines = new ArrayList<>();
    lines.add(
        MessageLine.of(
            prefix(text.environmentLabel())
                + "Approval needed: "
                + actionName
                + " on "
                + targetName
                + " for "
                + number(incident)));
    lines.add(
        MessageLine.of(
            "[" + severity(incident) + "] " + number(incident) + " " + incident.title()));
    lines.add(MessageLine.of("Expires: " + time(expiresAt)));
    lines.add(MessageLine.of("Summary: " + text.summary()));
    lines.add(linked("Approve or reject: ", link));
    return List.copyOf(lines);
  }

  /** The prefix of DM-E02-R2: {@code {label} - }, or empty without a label. */
  static String prefix(String environmentLabel) {
    return environmentLabel == null || environmentLabel.isEmpty() ? "" : environmentLabel + " - ";
  }

  /** {@code INC-} and the number padded with zeros to 4 digits (DC-TX-01). */
  static String number(long number) {
    return "INC-" + String.format("%04d", number);
  }

  /** The escalation cause of DC-TX-01. */
  static String cause(EscalationCause cause, Duration timer) {
    return cause == EscalationCause.ACKNOWLEDGEMENT_TIMEOUT
        ? "not acknowledged within " + duration(timer)
        : "workflow escalation step";
  }

  /** A duration by DC-U-07 rule (2). */
  static String duration(Duration duration) {
    long seconds = duration.toSeconds();
    if (seconds < SECONDS_RULE) {
      return seconds + " s";
    }
    long minutes = seconds / 60;
    long rest = seconds % 60;
    return rest == 0 ? minutes + " min" : minutes + " min " + rest + " s";
  }

  /** An instant in the outbound time format of DC-TX-01, truncated to the second. */
  static String time(Instant instant) {
    return OUTBOUND.format(instant.truncatedTo(ChronoUnit.SECONDS));
  }

  /** A record's {@code content}: the lines' texts joined by a line break, cut to 4,000. */
  static String content(List<MessageLine> lines) {
    return cut(
        lines.stream().map(MessageLine::text).collect(Collectors.joining("\n")), CONTENT_MAX);
  }

  /** The DC-TX-01 name of a purpose. */
  static String purposeName(NotifiedPurpose purpose) {
    return switch (purpose) {
      case INCIDENT_NOTIFICATION -> "incident notification";
      case ESCALATION -> "escalation";
      case APPROVAL_REQUEST -> "approval request";
    };
  }

  /** The channel's name in texts: {@code Teams} or {@code email}. */
  static String channelName(Channel channel) {
    return channel == Channel.TEAMS ? "Teams" : "email";
  }

  /** The audit summary of DC-TX-10 {@code notification_sent}. */
  static String auditSummary(
      NotifiedPurpose purpose, String recipientName, Channel channel, DeliveryStatus status) {
    return purposeName(purpose)
        + " to "
        + recipientName
        + " by "
        + channelName(channel)
        + ": "
        + status.key()
        + ".";
  }

  /** The audit details {@code {"purpose", "channel", "status"}}. */
  static String auditDetails(NotifiedPurpose purpose, Channel channel, DeliveryStatus status) {
    ObjectNode details = JSON.createObjectNode();
    details.put("purpose", purpose.key());
    details.put("channel", channel.key());
    details.put("status", status.key());
    return JSON.writeValueAsString(details);
  }

  /** The first {@code max} code points of {@code text}. */
  static String cut(String text, int max) {
    return text.codePointCount(0, text.length()) <= max
        ? text
        : text.substring(0, text.offsetByCodePoints(0, max));
  }

  /** The names in code point order, joined with {@code ", "}; {@code empty} when there is none. */
  static String list(List<String> names, String empty) {
    return names.isEmpty() ? empty : String.join(", ", names.stream().sorted(CODE_POINTS).toList());
  }

  private static String parties(IncidentText text) {
    return "Owner: "
        + (text.ownerTeamName() == null ? "none" : text.ownerTeamName())
        + ". Informed: "
        + list(text.informedTeamNames(), "none")
        + ".";
  }

  private static MessageLine linked(String text, String link) {
    return link == null ? MessageLine.of(text) : new MessageLine(text + link, link);
  }

  private static String severity(IncidentView incident) {
    return incident.severity().key();
  }

  private static String number(IncidentView incident) {
    return number(incident.number());
  }
}
