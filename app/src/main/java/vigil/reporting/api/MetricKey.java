package vigil.reporting.api;

import java.util.Arrays;
import java.util.Optional;

/** The four dashboard metrics (DM-E-79, complete list), in the order of DC-S10-02. */
public enum MetricKey {
  TIME_TO_ACKNOWLEDGE("time_to_acknowledge"),
  TIME_TO_RECOVER("time_to_recover"),
  ALERTS_SUPPRESSED("alerts_suppressed"),
  INCIDENTS_MERGED("incidents_merged");

  private final String key;

  MetricKey(String key) {
    this.key = key;
  }

  public String key() {
    return key;
  }

  /** {@code minutes} for the two times, {@code count} for the two counts (DM-E-79). */
  public MetricUnit unit() {
    return this == TIME_TO_ACKNOWLEDGE || this == TIME_TO_RECOVER
        ? MetricUnit.MINUTES
        : MetricUnit.COUNT;
  }

  /** The value with exactly this key; empty for {@code null} or an unknown key. */
  public static Optional<MetricKey> fromKey(String key) {
    return Arrays.stream(values()).filter(value -> value.key.equals(key)).findFirst();
  }
}
