package democontrol;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** The last result of an operation (spec 6.3 item 4): seconds with three decimals. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
record LastResult(
    String operation,
    String outcome,
    Instant finishedAt,
    BigDecimal seconds,
    @JsonProperty("branch_a_seconds") BigDecimal branchASeconds,
    BigDecimal quietSeconds,
    @JsonProperty("branch_b_seconds") BigDecimal branchBSeconds,
    String error) {

  static BigDecimal seconds(Duration duration) {
    return duration == null
        ? null
        : BigDecimal.valueOf(duration.toNanos(), 9).setScale(3, RoundingMode.HALF_UP);
  }

  boolean completed() {
    return "completed".equals(outcome);
  }

  /** The status line of DC-S12-05 for this result. */
  String line() {
    long whole = seconds.setScale(0, RoundingMode.DOWN).longValueExact();
    return completed()
        ? operation + " completed in " + whole + " s"
        : operation + " failed after " + whole + " s: " + error;
  }
}
