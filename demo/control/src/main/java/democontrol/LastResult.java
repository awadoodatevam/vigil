package democontrol;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/** The last result of an operation (spec 6.3 item 4): seconds with three decimals. */
record LastResult(
    String operation,
    String outcome,
    Instant finishedAt,
    BigDecimal seconds,
    BigDecimal branchASeconds,
    BigDecimal quietSeconds,
    BigDecimal branchBSeconds,
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

  Map<String, Object> toMap() {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("operation", operation);
    map.put("outcome", outcome);
    map.put("finished_at", Panel.iso(finishedAt));
    map.put("seconds", seconds);
    map.put("branch_a_seconds", branchASeconds);
    map.put("quiet_seconds", quietSeconds);
    map.put("branch_b_seconds", branchBSeconds);
    map.put("error", error);
    return map;
  }

  static LastResult of(JsonNode json) {
    if (json == null || !json.isObject()) {
      return null;
    }
    return new LastResult(
        json.path("operation").asString(),
        json.path("outcome").asString(),
        Instant.parse(json.path("finished_at").asString()),
        decimal(json.get("seconds")),
        decimal(json.get("branch_a_seconds")),
        decimal(json.get("quiet_seconds")),
        decimal(json.get("branch_b_seconds")),
        json.path("error").isString() ? json.path("error").stringValue() : null);
  }

  private static BigDecimal decimal(JsonNode node) {
    return node == null || !node.isNumber()
        ? null
        : node.decimalValue().setScale(3, RoundingMode.HALF_UP);
  }
}
