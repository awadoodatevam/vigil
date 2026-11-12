package vigil.api;

import java.math.BigDecimal;
import java.util.List;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** The answer of AR-I-80: the four metrics and the data line of the dashboard (DC-S10). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
record Dashboard(
    List<Metric> metrics, long liveIncidents, long pastIncidents, List<String> recordLabels) {

  /** One metric; {@code value} {@code null} for "no data", the baseline {@code null} when none. */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  record Metric(
      String key, String unit, BigDecimal value, BigDecimal baselineValue, String baselineLabel) {}
}
