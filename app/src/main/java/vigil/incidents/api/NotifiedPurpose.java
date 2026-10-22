package vigil.incidents.api;

import java.util.Arrays;
import java.util.Optional;

/** The purpose of the first delivered message to a team (DM-E-37, complete list). */
public enum NotifiedPurpose {
  INCIDENT_NOTIFICATION("incident_notification"),
  ESCALATION("escalation"),
  APPROVAL_REQUEST("approval_request");

  private final String key;

  NotifiedPurpose(String key) {
    this.key = key;
  }

  public String key() {
    return key;
  }

  /** The value with exactly this key; empty for {@code null} or an unknown key. */
  public static Optional<NotifiedPurpose> fromKey(String key) {
    return Arrays.stream(values()).filter(value -> value.key.equals(key)).findFirst();
  }
}
