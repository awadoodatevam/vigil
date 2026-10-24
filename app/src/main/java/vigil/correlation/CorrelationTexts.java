package vigil.correlation;

import vigil.correlation.api.Decision;

/** The audit summaries of vigil-correlation (DC-TX-10, DC-TX-00 Counts, DC-TX-01). */
final class CorrelationTexts {

  static final int SUMMARY_MAX = 300;

  private CorrelationTexts() {}

  /**
   * {@code alert_grouped}: "Alert '{title}' ({source kind}) {created a new incident | joined the
   * incident | reopened the incident}." with "; {k} {group or groups} merged" when k ≥ 1.
   */
  static String alertGrouped(String title, String sourceKind, Decision decision, int merged) {
    String outcome =
        switch (decision) {
          case CREATED_INCIDENT -> "created a new incident";
          case JOINED -> "joined the incident";
          case REOPENED -> "reopened the incident";
        };
    String groups =
        merged < 1 ? "" : "; " + merged + (merged == 1 ? " group" : " groups") + " merged";
    return cut(
        "Alert '" + title + "' (" + sourceKindName(sourceKind) + ") " + outcome + groups + ".");
  }

  /** {@code hypothesis_changed}: "Hypothesis: {old name or 'none'} → {new name or 'none'}." */
  static String hypothesisChanged(String oldName, String newName) {
    return cut(
        "Hypothesis: "
            + (oldName == null ? "none" : oldName)
            + " → "
            + (newName == null ? "none" : newName)
            + ".");
  }

  /** DC-TX-01: the display name of a source kind. */
  static String sourceKindName(String sourceKind) {
    return switch (sourceKind) {
      case "zabbix" -> "Zabbix";
      case "alertmanager" -> "Alertmanager";
      default -> throw new IllegalArgumentException("unknown source kind");
    };
  }

  /** The first {@code SUMMARY_MAX} code points. */
  static String cut(String text) {
    if (text.codePointCount(0, text.length()) <= SUMMARY_MAX) {
      return text;
    }
    return text.substring(0, text.offsetByCodePoints(0, SUMMARY_MAX));
  }
}
