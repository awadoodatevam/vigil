package vigil.api;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** A team key with its name (DC-U-06); the name is the key when the team does not exist. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
record TeamName(String teamKey, String name) {}
