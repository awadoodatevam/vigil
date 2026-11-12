package vigil.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** The answer of AR-I-20: the four parts of the incoming alerts strip (DC-S02-02). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
record AlertStrip(
    @JsonProperty("received_last_10_minutes") long receivedLast10Minutes,
    Forming forming,
    Sources sources,
    List<LatestAlert> latest) {

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  record Forming(long incidents, long alerts) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  record Sources(long healthy, long total, List<NotHealthy> notHealthy) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  record NotHealthy(String sourceKey, String name, String health) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  record LatestAlert(
      String alertId,
      String firstReceivedAt,
      String sourceKind,
      String alertTypeName,
      String title) {}
}
