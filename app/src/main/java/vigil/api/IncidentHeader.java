package vigil.api;

import java.util.List;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** The answer of AR-I-22: header, hypothesis and impact (DC-S03-02 to DC-S03-04). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
record IncidentHeader(
    String incidentId,
    long number,
    String title,
    String state,
    String stage,
    String severity,
    TeamName owner,
    List<TeamName> informed,
    String ruleName,
    IncidentPage.Acknowledged acknowledged,
    Escalation escalation,
    String firstAlertFiredAt,
    String resolvedAt,
    IncidentPage.Ticket ticket,
    long reopenCount,
    long alertCount,
    Hypothesis hypothesis,
    List<AffectedService> affectedServices) {

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  record Escalation(long level, List<TeamName> teams) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  record Hypothesis(
      String componentKey,
      String name,
      String kind,
      String criticality,
      long otherAffectedCount,
      long dependentCount,
      String tieBreak) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  record AffectedService(
      String serviceKey, String name, String criticality, List<ComponentName> via) {}
}
