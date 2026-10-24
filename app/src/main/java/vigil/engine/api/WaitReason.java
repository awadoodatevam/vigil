package vigil.engine.api;

import java.util.Arrays;
import java.util.Optional;

/** Why a workflow run waits (DM-E-50 {@code wait_reason}, complete list). */
public enum WaitReason {
  INCIDENT_FORMING("incident_forming"),
  STEP_WAITING("step_waiting");

  private final String key;

  WaitReason(String key) {
    this.key = key;
  }

  public String key() {
    return key;
  }

  /** The value with exactly this key; empty for {@code null} or an unknown key. */
  public static Optional<WaitReason> fromKey(String key) {
    return Arrays.stream(values()).filter(value -> value.key.equals(key)).findFirst();
  }
}
