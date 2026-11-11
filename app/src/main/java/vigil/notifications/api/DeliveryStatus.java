package vigil.notifications.api;

import java.util.Arrays;
import java.util.Optional;

/** The statuses of a notification record (DM-E-78, complete list). */
public enum DeliveryStatus {
  SENT("sent"),
  FAILED("failed");

  private final String key;

  DeliveryStatus(String key) {
    this.key = key;
  }

  public String key() {
    return key;
  }

  /** The value with exactly this key; empty for {@code null} or an unknown key. */
  public static Optional<DeliveryStatus> fromKey(String key) {
    return Arrays.stream(values()).filter(value -> value.key.equals(key)).findFirst();
  }
}
