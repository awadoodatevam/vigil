package vigil.api;

import java.util.List;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** The answer of AR-I-31: the teams notified about the incident (DC-S03-14). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
record NotifiedTeams(List<Team> teams) {

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  record Team(String teamKey, String name, String purpose, String firstNotifiedAt) {}
}
