package vigil.recovery.api;

import java.util.Arrays;
import java.util.Optional;

/** The states of a recovery verification (DM-E-62, DM-SM-13, complete list). */
public enum VerificationState {
  OBSERVING("observing"),
  RECOVERED("recovered"),
  NOT_RECOVERED("not_recovered"),
  CANCELLED("cancelled");

  private final String key;

  VerificationState(String key) {
    this.key = key;
  }

  public String key() {
    return key;
  }

  /** The value with exactly this key; empty for {@code null} or an unknown key. */
  public static Optional<VerificationState> fromKey(String key) {
    return Arrays.stream(values()).filter(value -> value.key.equals(key)).findFirst();
  }
}
