package vigil.intake.api;

import java.util.Arrays;
import java.util.Optional;

/** The health of a monitoring source (DM-E-15 {@code health}, DM-SM-03). */
public enum Health {
  HEALTHY("healthy"),
  STALE("stale"),
  DISCONNECTED("disconnected");

  private final String key;

  Health(String key) {
    this.key = key;
  }

  public String key() {
    return key;
  }

  /** The health with exactly this key; empty for {@code null} or an unknown key. */
  public static Optional<Health> fromKey(String key) {
    return Arrays.stream(values()).filter(health -> health.key.equals(key)).findFirst();
  }
}
