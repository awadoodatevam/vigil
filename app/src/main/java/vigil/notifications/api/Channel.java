package vigil.notifications.api;

import java.util.Arrays;
import java.util.Optional;

/**
 * The channels of a delivery address and a notification record (DM-E-77, DM-E-78, complete list).
 */
public enum Channel {
  TEAMS("teams"),
  EMAIL("email");

  private final String key;

  Channel(String key) {
    this.key = key;
  }

  public String key() {
    return key;
  }

  /** The value with exactly this key; empty for {@code null} or an unknown key. */
  public static Optional<Channel> fromKey(String key) {
    return Arrays.stream(values()).filter(value -> value.key.equals(key)).findFirst();
  }
}
