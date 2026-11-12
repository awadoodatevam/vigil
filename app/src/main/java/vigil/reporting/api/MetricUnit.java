package vigil.reporting.api;

import java.util.Arrays;
import java.util.Optional;

/** The units of a dashboard metric (DM-E-79, DM-E-80, complete list). */
public enum MetricUnit {
  MINUTES("minutes"),
  COUNT("count");

  private final String key;

  MetricUnit(String key) {
    this.key = key;
  }

  public String key() {
    return key;
  }

  /** The value with exactly this key; empty for {@code null} or an unknown key. */
  public static Optional<MetricUnit> fromKey(String key) {
    return Arrays.stream(values()).filter(value -> value.key.equals(key)).findFirst();
  }
}
