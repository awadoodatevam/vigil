package vigil.reporting.api;

import java.math.BigDecimal;

/**
 * One dashboard metric (PM-M21-01, PM-M21-02): {@code value} with scale 1 for minutes and 0 for
 * counts, {@code null} for "no data" (DM-R-53); {@code baselineValue} with the same scale and
 * {@code baselineLabel} {@code null} when the tenant has no baseline for the metric.
 */
public record MetricView(
    MetricKey key,
    MetricUnit unit,
    BigDecimal value,
    BigDecimal baselineValue,
    String baselineLabel) {}
