package vigil.incidents;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import vigil.incidents.api.AffectedServiceCopy;
import vigil.incidents.api.AiStatus;
import vigil.incidents.api.AiUnavailableReason;
import vigil.incidents.api.AlertCopy;
import vigil.incidents.api.EscalationCause;
import vigil.incidents.api.IncidentState;
import vigil.incidents.api.NotifiedPurpose;
import vigil.incidents.api.Severity;

/**
 * The stored texts of vigil-incidents (DC-TX-01, DC-TX-04, DC-TX-10; P-14 spec Section 6.8).
 * Lengths are in Unicode code points; names are resolved by the caller.
 */
final class IncidentTexts {

  /** Orders texts by Unicode code points (DM-C-01). */
  static final Comparator<String> CODE_POINTS = IncidentTexts::compareCodePoints;

  private static final DateTimeFormatter OUTBOUND =
      DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);
  private static final long DURATION_SECONDS_LIMIT = 120;

  static final String RESOLVED = "Resolved: recovery verified.";
  static final String CLOSED = "Closed: the reopen window has passed.";
  static final String MERGED = "Merged into another group.";

  private IncidentTexts() {}

  // Formats (DC-TX-00, DC-TX-01, DC-U-07).

  /** The incident number: {@code INC-} and the number padded to 4 digits. */
  static String number(long number) {
    return "INC-" + String.format("%04d", number);
  }

  static String state(IncidentState state) {
    return switch (state) {
      case FORMING -> "Forming";
      case OPEN -> "Open";
      case RESOLVED -> "Resolved";
      case CLOSED -> "Closed";
      case MERGED -> "Merged";
    };
  }

  static String sourceKind(String sourceKind) {
    return sourceKind.equals("zabbix") ? "Zabbix" : "Alertmanager";
  }

  /** DC-U-07 rule (2). */
  static String duration(Duration duration) {
    long seconds = duration.toSeconds();
    if (seconds < DURATION_SECONDS_LIMIT) {
      return seconds + " s";
    }
    long minutes = seconds / 60;
    long rest = seconds % 60;
    return rest == 0 ? minutes + " min" : minutes + " min " + rest + " s";
  }

  /** The outbound time format of DC-TX-01. */
  static String outbound(Instant instant) {
    return OUTBOUND.format(instant);
  }

  /** A list of names in code point order joined by {@code ", "}; {@code none} when empty. */
  static String names(List<String> names) {
    if (names.isEmpty()) {
      return "none";
    }
    return String.join(", ", names.stream().sorted(CODE_POINTS).toList());
  }

  static String count(long n, String singular, String plural) {
    return n + " " + (n == 1 ? singular : plural);
  }

  /** The first {@code max} code points of {@code text}, or the text when it is not longer. */
  static String cut(String text, int max) {
    if (text == null || length(text) <= max) {
      return text;
    }
    return text.substring(0, text.offsetByCodePoints(0, max));
  }

  static int length(String text) {
    return text.codePointCount(0, text.length());
  }

  static int compareCodePoints(String a, String b) {
    int i = 0;
    int j = 0;
    while (i < a.length() && j < b.length()) {
      int ca = a.codePointAt(i);
      int cb = b.codePointAt(j);
      if (ca != cb) {
        return Integer.compare(ca, cb);
      }
      i += Character.charCount(ca);
      j += Character.charCount(cb);
    }
    return Boolean.compare(i < a.length(), j < b.length());
  }

  // Timeline texts (DC-TX-04).

  static String created(AlertCopy alert) {
    return "Incident created from alert '"
        + alert.title()
        + "' ("
        + sourceKind(alert.sourceKind())
        + ").";
  }

  static String alertJoined(AlertCopy alert) {
    return "Alert '" + alert.title() + "' (" + sourceKind(alert.sourceKind()) + ") joined.";
  }

  static String mergedIn(long alerts) {
    return count(alerts, "alert", "alerts") + " merged in from another group.";
  }

  /** {@code oldName} is {@code null} when there was no hypothesis. */
  static String hypothesisChanged(String oldName, String newName) {
    return oldName == null
        ? "Hypothesis: " + newName + "."
        : "Hypothesis changed from " + oldName + " to " + newName + ".";
  }

  static String impactChanged(List<AffectedServiceCopy> services) {
    if (services.isEmpty()) {
      return "Customer impact: none identified.";
    }
    return "Customer impact: "
        + names(services.stream().map(AffectedServiceCopy::name).toList())
        + ".";
  }

  static String assignmentChanged(
      Severity severity, String team, List<String> informed, String ruleName) {
    return "Assigned "
        + severity.key()
        + ", owner "
        + team
        + ", informed "
        + names(informed)
        + " (rule '"
        + ruleName
        + "').";
  }

  static String opened(long number) {
    return "Opened as " + number(number) + ": the alert group is stable.";
  }

  static String reopened(AlertCopy alert) {
    return "Reopened by alert '" + alert.title() + "' (" + sourceKind(alert.sourceKind()) + ").";
  }

  static String acknowledged(String userDisplayName) {
    return "Acknowledged by " + userDisplayName + ".";
  }

  static String escalated(
      long level, List<String> teamNames, EscalationCause cause, Duration timer) {
    String causeText =
        cause == EscalationCause.ACKNOWLEDGEMENT_TIMEOUT
            ? "not acknowledged within " + duration(timer)
            : "workflow escalation step";
    return "Escalated to level " + level + ": " + names(teamNames) + " (" + causeText + ").";
  }

  static String evidenceAdded(String summary) {
    return "Evidence: " + summary;
  }

  static String aiStatusChanged(AiStatus status, AiUnavailableReason reason) {
    if (status == AiStatus.AVAILABLE) {
      return "AI investigation available.";
    }
    String reasonText =
        switch (reason) {
          case NO_PROVIDER_REACHABLE -> "no allowed AI provider was reachable";
          case NO_PROVIDER_ALLOWED -> "data routing allows no provider for this data";
          case OUTPUT_FAILED_CHECKS -> "the AI output failed the checks and was not used";
        };
    return "AI investigation unavailable: " + reasonText + ".";
  }

  static String notifiedTeamAdded(String team, NotifiedPurpose purpose) {
    String purposeText =
        switch (purpose) {
          case INCIDENT_NOTIFICATION -> "incident notification";
          case ESCALATION -> "escalation";
          case APPROVAL_REQUEST -> "approval request";
        };
    return team + " notified (" + purposeText + ").";
  }

  static String titleChanged(String title) {
    return "Title: " + title;
  }

  // Audit summaries (DC-TX-10); incident_created, incident_acknowledged and incident_escalated
  // use the timeline texts above.

  static String auditStateChanged(IncidentState from, IncidentState to) {
    return state(from) + " → " + state(to) + ".";
  }

  static String auditAssignmentChanged(
      Severity severity, String team, List<String> informed, String ruleName) {
    return "Assigned "
        + severity.key()
        + ", owner "
        + team
        + ", informed "
        + names(informed)
        + " by rule '"
        + ruleName
        + "'.";
  }
}
