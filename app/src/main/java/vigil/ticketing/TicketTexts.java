package vigil.ticketing;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import vigil.connectors.api.TicketLine;
import vigil.incidents.api.AffectedServiceCopy;
import vigil.incidents.api.EscalationCause;
import vigil.incidents.api.EvidenceView;
import vigil.incidents.api.IncidentView;
import vigil.incidents.api.MembershipView;
import vigil.investigation.api.IncidentSummaryView;
import vigil.investigation.api.SummarySource;
import vigil.ticketing.api.SyncOperation;
import vigil.ticketing.api.SyncOutcome;

/**
 * The outbound texts of vigil-ticketing (DC-OUT-00 to DC-OUT-04, DM-E02-R2, DC-TX-00, DC-TX-01),
 * the content sent and the audit summary (P-21 spec Section 6.6).
 */
final class TicketTexts {

  static final JsonMapper JSON = JsonMapper.builder().build();
  static final Comparator<String> CODE_POINTS =
      Comparator.comparing(text -> text.codePoints().toArray(), Arrays::compare);
  static final int TITLE_MAX = 255;
  static final int ERROR_MAX = 500;
  private static final DateTimeFormatter OUTBOUND =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);
  private static final long SECONDS_RULE = 120;
  private static final Map<String, String> SOURCE_KINDS =
      Map.of("zabbix", "Zabbix", "alertmanager", "Alertmanager");

  private TicketTexts() {}

  /** {@code INC-} and the number padded with zeros to 4 digits (DC-TX-01). */
  static String number(long number) {
    return "INC-" + String.format("%04d", number);
  }

  /** The Jira title (DM-E02-R2): {@code {label} - {title}}, cut to 255. */
  static String title(String environmentLabel, String title) {
    String text =
        environmentLabel == null || environmentLabel.isEmpty()
            ? title
            : environmentLabel + " - " + title;
    return cut(text, TITLE_MAX);
  }

  /** The seven description parts of DC-OUT-01. */
  static List<TicketLine> createLines(
      IncidentView incident,
      List<MembershipView> members,
      IncidentSummaryView summary,
      List<String> evidence,
      String product,
      String link) {
    List<TicketLine> lines = new ArrayList<>();
    lines.add(TicketLine.of("Incident: " + incident.title()));
    Map<String, String> names = new LinkedHashMap<>();
    for (MembershipView member : members) {
      names.putIfAbsent(member.alert().alertType(), member.alert().alertTypeName());
    }
    List<String> types =
        incident.alertTypes().stream().map(type -> names.getOrDefault(type, type)).toList();
    long count = incident.alertCount();
    lines.add(
        TicketLine.of(
            "Alerts: "
                + count
                + (count == 1 ? " alert" : " alerts")
                + ". Types: "
                + list(types, "none")
                + "."));
    List<String> services =
        incident.affectedServices().stream().map(AffectedServiceCopy::name).toList();
    lines.add(TicketLine.of("Customer impact: " + list(services, "none identified") + "."));
    lines.add(
        TicketLine.of(
            incident.hypothesis() == null
                ? "Root-cause hypothesis: none."
                : "Root-cause hypothesis: "
                    + incident.hypothesis().name()
                    + " ("
                    + incident.hypothesis().componentKey()
                    + "). A hypothesis from the service map, not a confirmed cause."));
    lines.add(
        TicketLine.of(
            (summary.source() == SummarySource.AI ? "AI summary: " : "Summary (template): ")
                + summary.text()));
    lines.add(TicketLine.of("Evidence:"));
    if (evidence.isEmpty()) {
      lines.add(TicketLine.of("- none yet"));
    }
    evidence.forEach(entry -> lines.add(TicketLine.of("- " + entry)));
    lines.add(
        new TicketLine("Open " + number(incident.number()) + " in " + product + ": " + link, link));
    return List.copyOf(lines);
  }

  /** The reopen comment of DC-OUT-04. */
  static TicketLine reopenLine(String alertTitle, String sourceKind, Instant firstReceivedAt) {
    return TicketLine.of(
        "Reopened by alert '"
            + alertTitle
            + "' ("
            + SOURCE_KINDS.getOrDefault(sourceKind, sourceKind)
            + ") received at "
            + time(firstReceivedAt)
            + ".");
  }

  /**
   * The resolve comment of DC-OUT-03 from the recovery verification's evidence entry; empty when
   * its content does not hold the keys of Section 6.6.3 in their types.
   */
  static Optional<List<TicketLine>> resolveLines(EvidenceView entry) {
    JsonNode content;
    try {
      content = JSON.readTree(entry.contentJson());
    } catch (JacksonException e) {
      return Optional.empty();
    }
    if (content == null || !content.isObject()) {
      return Optional.empty();
    }
    JsonNode snapshot = content.get("definition_snapshot");
    JsonNode window = snapshot == null ? null : snapshot.get("observation_window_seconds");
    if (window == null
        || !window.isIntegralNumber()
        || !window.canConvertToLong()
        || window.longValue() < 1) {
      return Optional.empty();
    }
    Instant started = instant(content.get("window_started_at"));
    Instant decided = instant(content.get("decided_at"));
    JsonNode evaluation = content.get("last_evaluation");
    JsonNode conditions = evaluation == null ? null : evaluation.get("conditions");
    if (started == null || decided == null || conditions == null || !conditions.isArray()) {
      return Optional.empty();
    }
    List<TicketLine> lines = new ArrayList<>();
    lines.add(TicketLine.of("Resolved: recovery verified."));
    lines.add(TicketLine.of(entry.summary()));
    lines.add(TicketLine.of("Conditions:"));
    for (JsonNode condition : conditions) {
      JsonNode detail = condition.isObject() ? condition.get("detail") : null;
      if (detail == null || !detail.isString()) {
        return Optional.empty();
      }
      lines.add(TicketLine.of("- " + detail.stringValue() + ": held"));
    }
    lines.add(TicketLine.of("- Every monitoring source is healthy: held"));
    lines.add(
        TicketLine.of(
            "Observation window: "
                + duration(Duration.ofSeconds(window.longValue()))
                + ", from "
                + time(started)
                + " to "
                + time(decided)
                + "."));
    lines.add(TicketLine.of("Source: " + entry.sourceSystem()));
    return Optional.of(List.copyOf(lines));
  }

  /** The escalation comment of DC-OUT-02. */
  static List<TicketLine> escalationLines(
      long level,
      List<String> teamNames,
      EscalationCause cause,
      Duration timer,
      List<String> evidence) {
    String causeText =
        cause == EscalationCause.ACKNOWLEDGEMENT_TIMEOUT
            ? "not acknowledged within " + duration(timer)
            : "workflow escalation step";
    List<TicketLine> lines = new ArrayList<>();
    lines.add(
        TicketLine.of(
            "Escalated to level "
                + level
                + ": "
                + list(teamNames, "none")
                + " ("
                + causeText
                + ")."));
    lines.add(TicketLine.of("Evidence:"));
    evidence.forEach(entry -> lines.add(TicketLine.of("- " + entry)));
    return List.copyOf(lines);
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

  /** {@code content_sent} of a create (CTX-D-069 (8)). */
  static String createContent(String title, List<TicketLine> description, String label) {
    ObjectNode content = JSON.createObjectNode();
    content.put("title", title);
    texts(content.putArray("description"), description);
    content.putArray("labels").add(label);
    return JSON.writeValueAsString(content);
  }

  /** {@code content_sent} of a reopen or resolve. */
  static String commentContent(List<TicketLine> comment, String transitionTo) {
    ObjectNode content = JSON.createObjectNode();
    texts(content.putArray("comment"), comment);
    content.put("transition_to", transitionTo);
    return JSON.writeValueAsString(content);
  }

  /** {@code content_sent} of an escalation comment. */
  static String escalationContent(List<TicketLine> comment) {
    ObjectNode content = JSON.createObjectNode();
    texts(content.putArray("comment"), comment);
    return JSON.writeValueAsString(content);
  }

  /** The audit summary of DC-TX-10 {@code ticket_changed}. */
  static String auditSummary(String key, SyncOperation operation, SyncOutcome outcome) {
    String name =
        operation == SyncOperation.RECORD_ESCALATION ? "record escalation" : operation.key();
    return "Jira " + (key == null ? "no ticket" : key) + ": " + name + " " + outcome.key() + ".";
  }

  /** The audit details {@code {"operation", "outcome"}}. */
  static String auditDetails(SyncOperation operation, SyncOutcome outcome) {
    ObjectNode details = JSON.createObjectNode();
    details.put("operation", operation.key());
    details.put("outcome", outcome.key());
    return JSON.writeValueAsString(details);
  }

  /** The first {@code max} code points of {@code text}. */
  static String cut(String text, int max) {
    return text.codePointCount(0, text.length()) <= max
        ? text
        : text.substring(0, text.offsetByCodePoints(0, max));
  }

  /** The names in code point order, joined with {@code ", "}; {@code empty} when there is none. */
  private static String list(List<String> names, String empty) {
    return names.isEmpty() ? empty : String.join(", ", names.stream().sorted(CODE_POINTS).toList());
  }

  private static void texts(ArrayNode array, List<TicketLine> lines) {
    lines.forEach(line -> array.add(line.text()));
  }

  private static Instant instant(JsonNode node) {
    if (node == null || !node.isString()) {
      return null;
    }
    try {
      return Instant.parse(node.stringValue());
    } catch (DateTimeParseException e) {
      return null;
    }
  }
}
