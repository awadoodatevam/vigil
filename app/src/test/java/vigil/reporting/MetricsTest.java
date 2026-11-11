package vigil.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vigil.reporting.BaselineRecords.Baseline;
import vigil.reporting.Metrics.Counted;
import vigil.reporting.api.MetricKey;
import vigil.reporting.api.MetricUnit;
import vigil.reporting.api.MetricView;

class MetricsTest {

  private static final Instant T = Instant.parse("2026-11-12T10:00:00Z");

  private static Instant after(long millis) {
    return T.plus(Duration.ofMillis(millis));
  }

  private static Counted counted(Long ackMillis, Long recoverMillis, long alerts) {
    return new Counted(
        T,
        ackMillis == null ? null : after(ackMillis),
        recoverMillis == null ? null : after(recoverMillis),
        alerts);
  }

  private static MetricView metric(List<MetricView> metrics, MetricKey key) {
    return metrics.stream().filter(m -> m.key() == key).findFirst().orElseThrow();
  }

  @Test
  @DisplayName("DM-R-53 the four metrics: half-up means, no data, suppressed and merged counts")
  void metrics() {
    // A mean of 5.65 min (339,000 ms) rounds up to 5.7; of 5.649 min (338,940 ms) down to 5.6.
    List<MetricView> up =
        Metrics.compute(
            List.of(counted(300_000L, 339_000L * 2, 1), counted(378_000L, 0L, 1)), 2, Map.of());
    assertThat(metric(up, MetricKey.TIME_TO_ACKNOWLEDGE).value()).isEqualTo(new BigDecimal("5.7"));
    assertThat(metric(up, MetricKey.TIME_TO_RECOVER).value()).isEqualTo(new BigDecimal("5.7"));
    List<MetricView> down = Metrics.compute(List.of(counted(338_940L, null, 1)), 1, Map.of());
    assertThat(metric(down, MetricKey.TIME_TO_ACKNOWLEDGE).value())
        .isEqualTo(new BigDecimal("5.6"));
    assertThat(metric(down, MetricKey.TIME_TO_RECOVER).value()).isNull();

    List<MetricView> none = Metrics.compute(List.of(counted(null, null, 1)), 1, Map.of());
    assertThat(metric(none, MetricKey.TIME_TO_ACKNOWLEDGE).value()).isNull();
    assertThat(metric(none, MetricKey.TIME_TO_RECOVER).value()).isNull();
    List<MetricView> empty = Metrics.compute(List.of(), 0, Map.of());
    assertThat(metric(empty, MetricKey.TIME_TO_ACKNOWLEDGE).value()).isNull();
    assertThat(metric(empty, MetricKey.ALERTS_SUPPRESSED).value()).isEqualTo(BigDecimal.ZERO);

    // Live and past incidents mixed: a live one acknowledged after 3 min and resolved after 13
    // min, a past one acknowledged after 4 min and recovered after 31 min, a past one with
    // neither.
    List<Counted> mixed =
        List.of(
            counted(180_000L, 780_000L, 2),
            counted(240_000L, 1_860_000L, 126),
            counted(null, null, 1));
    List<MetricView> metrics = Metrics.compute(mixed, 129 + 1, Map.of());
    assertThat(metric(metrics, MetricKey.TIME_TO_ACKNOWLEDGE).value())
        .isEqualTo(new BigDecimal("3.5"));
    assertThat(metric(metrics, MetricKey.TIME_TO_RECOVER).value())
        .isEqualTo(new BigDecimal("22.0"));
    assertThat(metric(metrics, MetricKey.ALERTS_SUPPRESSED).value())
        .isEqualTo(BigDecimal.valueOf(127));
    assertThat(metric(metrics, MetricKey.INCIDENTS_MERGED).value())
        .isEqualTo(BigDecimal.valueOf(2));
    // Not clamped: fewer alerts received than counted incidents.
    assertThat(metric(Metrics.compute(mixed, 1, Map.of()), MetricKey.ALERTS_SUPPRESSED).value())
        .isEqualTo(BigDecimal.valueOf(-2));
  }

  @Test
  @DisplayName("DM-E-79 the four metrics in order, with their units, scales and baselines")
  void definitions() {
    Map<MetricKey, Baseline> baselines =
        Map.of(
            MetricKey.TIME_TO_ACKNOWLEDGE,
                new Baseline(new BigDecimal("11.0"), "Illustrative baseline"),
            MetricKey.ALERTS_SUPPRESSED,
                new Baseline(new BigDecimal("0.0"), "Illustrative baseline"));
    List<MetricView> metrics =
        Metrics.compute(List.of(counted(180_000L, 780_000L, 2)), 2, baselines);
    assertThat(metrics)
        .extracting(MetricView::key)
        .containsExactly(
            MetricKey.TIME_TO_ACKNOWLEDGE,
            MetricKey.TIME_TO_RECOVER,
            MetricKey.ALERTS_SUPPRESSED,
            MetricKey.INCIDENTS_MERGED);
    assertThat(metrics)
        .extracting(MetricView::unit)
        .containsExactly(
            MetricUnit.MINUTES, MetricUnit.MINUTES, MetricUnit.COUNT, MetricUnit.COUNT);
    assertThat(metrics.get(0).value().scale()).isEqualTo(1);
    assertThat(metrics.get(0).value()).isEqualTo(new BigDecimal("3.0"));
    assertThat(metrics.get(2).value().scale()).isZero();
    assertThat(metrics.get(3).value()).isEqualTo(BigDecimal.ONE);
    assertThat(metrics.get(0).baselineValue()).isEqualTo(new BigDecimal("11.0"));
    assertThat(metrics.get(0).baselineLabel()).isEqualTo("Illustrative baseline");
    assertThat(metrics.get(1).baselineValue()).isNull();
    assertThat(metrics.get(1).baselineLabel()).isNull();
    assertThat(metrics.get(2).baselineValue()).isEqualTo(new BigDecimal("0"));
    assertThat(metrics.get(2).baselineValue().scale()).isZero();
    for (MetricKey key : MetricKey.values()) {
      assertThat(MetricKey.fromKey(key.key())).contains(key);
    }
    assertThat(MetricKey.TIME_TO_RECOVER.unit()).isEqualTo(MetricUnit.MINUTES);
    assertThat(MetricKey.INCIDENTS_MERGED.unit()).isEqualTo(MetricUnit.COUNT);
  }
}
