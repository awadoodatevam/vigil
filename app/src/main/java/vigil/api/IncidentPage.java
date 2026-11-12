package vigil.api;

import java.util.List;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** The answer of AR-I-21: one page of the incident list (DC-S02-04, AR-I-01). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
record IncidentPage(List<Row> items, String nextCursor) {

  /** One row of DC-S02-04. */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  record Row(
      String incidentId,
      long number,
      String title,
      String severity,
      String state,
      String stage,
      TeamName owner,
      Acknowledged acknowledged,
      long alertCount,
      String firstAlertFiredAt,
      Ticket ticket) {}

  /** The acknowledgement of the current episode. */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  record Acknowledged(UserName user, String at) {}

  /** The incident's ticket link. */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  record Ticket(String key, String url) {}
}
