package vigil.api;

import java.util.List;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** The answer of AR-I-23: the member alerts grouped by type with their reasons (DC-S03-05). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
record IncidentAlerts(long firing, long resolved, Long groupingWindowSeconds, List<Group> groups) {

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  record Group(String alertType, String alertTypeName, long count, List<Alert> alerts) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  record Alert(
      String alertId,
      String firedAt,
      String sourceKind,
      String title,
      String componentName,
      String status,
      Reason reason) {}

  /** The final reason of a membership (DM-E-34) with the facts of DC-TX-02. */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  record Reason(
      String relation,
      boolean reopened,
      Related related,
      List<ComponentName> path,
      Long timeDeltaSeconds,
      Identity identity) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  record Related(String title, String sourceKind) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  record Identity(String name, String value) {}
}
