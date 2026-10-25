package vigil.engine.api;

import java.util.Arrays;
import java.util.Optional;

/** Why a workflow run ended (DM-E-50 {@code end_reason}, complete list). */
public enum EndReason {
  PATH_ENDED("path_ended"),
  INCIDENT_MERGED("incident_merged"),
  INCIDENT_CLOSED("incident_closed"),
  INCIDENT_REOPENED("incident_reopened"),
  STEP_FAILED_REPEATEDLY("step_failed_repeatedly"),
  STEP_LIMIT_REACHED("step_limit_reached");

  private final String key;

  EndReason(String key) {
    this.key = key;
  }

  public String key() {
    return key;
  }

  /** The value with exactly this key; empty for {@code null} or an unknown key. */
  public static Optional<EndReason> fromKey(String key) {
    return Arrays.stream(values()).filter(value -> value.key.equals(key)).findFirst();
  }
}
