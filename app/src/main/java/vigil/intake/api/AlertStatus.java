package vigil.intake.api;

import java.util.Arrays;
import java.util.Optional;

/** The status of an alert (DM-E-21 {@code status}, DM-SM-04). */
public enum AlertStatus {
  FIRING("firing"),
  RESOLVED("resolved");

  private final String key;

  AlertStatus(String key) {
    this.key = key;
  }

  public String key() {
    return key;
  }

  /** The status with exactly this key; empty for {@code null} or an unknown key. */
  public static Optional<AlertStatus> fromKey(String key) {
    return Arrays.stream(values()).filter(status -> status.key.equals(key)).findFirst();
  }
}
