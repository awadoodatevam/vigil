package vigil.api;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** The answer of AR-I-22 for a {@code merged} incident (DC-S03-01). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
record MergedIncident(String mergedIntoIncidentId) {}
