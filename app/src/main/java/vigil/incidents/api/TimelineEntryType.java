package vigil.incidents.api;

import java.util.Arrays;
import java.util.Optional;

/** The types of timeline entry (DM-E-39 {@code entry_type}, complete list). */
public enum TimelineEntryType {
  CREATED("created"),
  ALERT_JOINED("alert_joined"),
  MERGED_IN("merged_in"),
  HYPOTHESIS_CHANGED("hypothesis_changed"),
  IMPACT_CHANGED("impact_changed"),
  ASSIGNMENT_CHANGED("assignment_changed"),
  STATE_CHANGED("state_changed"),
  ACKNOWLEDGED("acknowledged"),
  ESCALATED("escalated"),
  EVIDENCE_ADDED("evidence_added"),
  AI_STATUS_CHANGED("ai_status_changed"),
  NOTIFIED_TEAM_ADDED("notified_team_added"),
  TITLE_CHANGED("title_changed");

  private final String key;

  TimelineEntryType(String key) {
    this.key = key;
  }

  public String key() {
    return key;
  }

  /** The value with exactly this key; empty for {@code null} or an unknown key. */
  public static Optional<TimelineEntryType> fromKey(String key) {
    return Arrays.stream(values()).filter(value -> value.key.equals(key)).findFirst();
  }
}
