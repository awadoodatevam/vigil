package vigil.incidents.api;

import java.util.Arrays;
import java.util.Optional;

/** The AI status of an incident (DM-E-32 {@code ai_status}, complete list). */
public enum AiStatus {
  NOT_RUN("not_run"),
  AVAILABLE("available"),
  UNAVAILABLE("unavailable");

  private final String key;

  AiStatus(String key) {
    this.key = key;
  }

  public String key() {
    return key;
  }

  /** The value with exactly this key; empty for {@code null} or an unknown key. */
  public static Optional<AiStatus> fromKey(String key) {
    return Arrays.stream(values()).filter(value -> value.key.equals(key)).findFirst();
  }
}
