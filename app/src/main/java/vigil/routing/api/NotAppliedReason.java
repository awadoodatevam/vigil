package vigil.routing.api;

import java.util.Arrays;
import java.util.Optional;

/** Why a routing evaluation was not applied to its incident (DM-E-43, complete list). */
public enum NotAppliedReason {
  ASSIGNMENT_FROZEN("assignment_frozen"),
  DRY_RUN("dry_run"),
  INCIDENT_NOT_ACTIVE("incident_not_active");

  private final String key;

  NotAppliedReason(String key) {
    this.key = key;
  }

  public String key() {
    return key;
  }

  /** The value with exactly this key; empty for {@code null} or an unknown key. */
  public static Optional<NotAppliedReason> fromKey(String key) {
    return Arrays.stream(values()).filter(value -> value.key.equals(key)).findFirst();
  }
}
