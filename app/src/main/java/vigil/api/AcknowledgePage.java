package vigil.api;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** The answer of AR-I-32: the Acknowledge page (DC-S04-03 to DC-S04-05). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
record AcknowledgePage(
    String incidentId,
    long number,
    String title,
    String severity,
    TeamName owner,
    String firstAlertFiredAt,
    long alertCount,
    String summary,
    String state,
    IncidentPage.Acknowledged acknowledged,
    boolean mayAct,
    Refusal refusal) {}
