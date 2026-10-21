package vigil.servicemap.api;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * The criticality of a service or component, highest first (DM-E-25, DM-E-26). An empty ({@code
 * null}) criticality ranks below {@code LOW} (DM-R-18).
 */
public enum Criticality {
  CRITICAL,
  HIGH,
  MEDIUM,
  LOW;

  /** The stored key (Example: {@code critical}). */
  public String key() {
    return name().toLowerCase(Locale.ROOT);
  }

  /** The criticality whose key is {@code key}, or empty. */
  public static Optional<Criticality> fromKey(String key) {
    return Arrays.stream(values()).filter(value -> value.key().equals(key)).findFirst();
  }
}
