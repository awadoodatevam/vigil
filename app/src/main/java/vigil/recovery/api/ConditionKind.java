package vigil.recovery.api;

import java.util.Arrays;
import java.util.Optional;

/** The kinds of recovery condition (DM-V-07, complete list). */
public enum ConditionKind {
  INCIDENT_ALERTS_RESOLVED("incident_alerts_resolved"),
  COMPONENT_NO_FIRING_ALERT("component_no_firing_alert");

  private final String key;

  ConditionKind(String key) {
    this.key = key;
  }

  public String key() {
    return key;
  }

  /** The value with exactly this key; empty for {@code null} or an unknown key. */
  public static Optional<ConditionKind> fromKey(String key) {
    return Arrays.stream(values()).filter(value -> value.key.equals(key)).findFirst();
  }
}
