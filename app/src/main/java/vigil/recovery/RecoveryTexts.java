package vigil.recovery;

import java.time.Duration;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** The texts of vigil-recovery (DC-TX-03, DC-TX-10; P-23 spec Section 6.6). */
final class RecoveryTexts {

  static final Comparator<String> CODE_POINTS =
      Comparator.comparing(text -> text.codePoints().toArray(), Arrays::compare);
  static final String GUARD_HELD = "Every monitoring source is healthy";
  static final int SOURCE_SYSTEM_MAX = 200;
  private static final long SECONDS_RULE = 120;

  private RecoveryTexts() {}

  /** {@code incident_alerts_resolved}. */
  static String incidentAlerts(boolean held, long firing) {
    return held
        ? "Every alert of the incident is resolved at its source"
        : firing + " " + alerts(firing) + " of the incident " + isOrAre(firing) + " still firing";
  }

  /**
   * {@code component_no_firing_alert}; {@code alertTypeNames} the display names of the condition's
   * alert types (empty for every alert type).
   */
  static String componentAlerts(
      boolean held, long firing, String componentName, List<String> alertTypeNames) {
    String types =
        alertTypeNames.isEmpty()
            ? ""
            : String.join(" or ", alertTypeNames.stream().sorted(CODE_POINTS).toList()) + " ";
    if (held) {
      return "No " + types + "alert of " + componentName + " is firing";
    }
    return firing
        + " "
        + types
        + alerts(firing)
        + " of "
        + componentName
        + " "
        + isOrAre(firing)
        + " firing";
  }

  /** The source health guard (DM-R-36): the names of the unhealthy active sources. */
  static String guard(List<String> unhealthySourceNames) {
    return unhealthySourceNames.isEmpty()
        ? GUARD_HELD
        : list(unhealthySourceNames) + " not healthy";
  }

  /** The evidence summary of a recovered verification. */
  static String recovered(String checkName, Duration observationWindow) {
    return "Recovered: '"
        + checkName
        + "' held for "
        + duration(observationWindow)
        + " with every monitoring source healthy.";
  }

  /**
   * The evidence summary of a verification not recovered: the texts not holding, or when there is
   * none, that the conditions held for less than the window.
   */
  static String notRecovered(
      Duration maxWait, List<String> textsNotHolding, Duration observationWindow) {
    String reason =
        textsNotHolding.isEmpty()
            ? "the conditions held for less than " + duration(observationWindow)
            : String.join("; ", textsNotHolding);
    return "Not recovered within " + duration(maxWait) + ": " + reason + ".";
  }

  /** The evidence {@code source_system}: the names of the active sources. */
  static String sourceSystem(List<String> activeSourceNames) {
    return cut("Monitoring: " + list(activeSourceNames), SOURCE_SYSTEM_MAX);
  }

  /** The audit summary of {@code recovery_verified} (DC-TX-10). */
  static String auditSummary(boolean recovered, String checkName) {
    return (recovered ? "Recovered: '" : "Not recovered: '") + checkName + "'.";
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

  /** The names in code point order, joined with {@code ", "}; {@code none} when there is none. */
  static String list(List<String> names) {
    return names.isEmpty()
        ? "none"
        : String.join(", ", names.stream().sorted(CODE_POINTS).toList());
  }

  static String cut(String text, int max) {
    return text.codePointCount(0, text.length()) <= max
        ? text
        : text.substring(0, text.offsetByCodePoints(0, max));
  }

  private static String alerts(long count) {
    return count == 1 ? "alert" : "alerts";
  }

  private static String isOrAre(long count) {
    return count == 1 ? "is" : "are";
  }
}
