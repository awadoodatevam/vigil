package vigil.reporting;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import vigil.reporting.BaselineRecords.Baseline;
import vigil.reporting.api.MetricKey;
import vigil.reporting.api.MetricUnit;
import vigil.reporting.api.MetricView;

/**
 * The four dashboard metrics (DM-E-79, DM-R-53; P-23 spec Section 6.10), a pure computation over
 * the counted incidents, the alerts received and the baselines.
 */
final class Metrics {

  private static final BigDecimal MINUTE_MILLIS = BigDecimal.valueOf(60_000);

  /**
   * A counted incident, live or past: detection, the first acknowledgement and the first resolution
   * ({@code null} when there is none) and its alert count.
   */
  record Counted(Instant detectedAt, Instant acknowledgedAt, Instant resolvedAt, long alertCount) {}

  private Metrics() {}

  /** The four metrics in {@link MetricKey} order, each with its baseline. */
  static List<MetricView> compute(
      List<Counted> counted, long alertsReceived, Map<MetricKey, Baseline> baselines) {
    List<MetricView> metrics = new ArrayList<>();
    for (MetricKey key : MetricKey.values()) {
      BigDecimal value =
          switch (key) {
            case TIME_TO_ACKNOWLEDGE -> mean(counted, Counted::acknowledgedAt);
            case TIME_TO_RECOVER -> mean(counted, Counted::resolvedAt);
            case ALERTS_SUPPRESSED -> BigDecimal.valueOf(alertsReceived - counted.size());
            case INCIDENTS_MERGED ->
                BigDecimal.valueOf(counted.stream().filter(c -> c.alertCount() >= 2).count());
          };
      Baseline baseline = baselines.get(key);
      metrics.add(
          new MetricView(
              key,
              key.unit(),
              value,
              baseline == null ? null : baseline.value().setScale(scale(key), RoundingMode.HALF_UP),
              baseline == null ? null : baseline.label()));
    }
    return List.copyOf(metrics);
  }

  /** The mean of (time − detection) in minutes, half-up to 1 place; {@code null} for no data. */
  private static BigDecimal mean(List<Counted> counted, Function<Counted, Instant> time) {
    long millis = 0;
    long count = 0;
    for (Counted incident : counted) {
      Instant at = time.apply(incident);
      if (at != null) {
        millis += Duration.between(incident.detectedAt(), at).toMillis();
        count++;
      }
    }
    if (count == 0) {
      return null;
    }
    return BigDecimal.valueOf(millis)
        .divide(MINUTE_MILLIS.multiply(BigDecimal.valueOf(count)), 1, RoundingMode.HALF_UP);
  }

  private static int scale(MetricKey key) {
    return key.unit() == MetricUnit.MINUTES ? 1 : 0;
  }
}
