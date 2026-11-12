package vigil.api;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** A target key with its name (DC-TX-01 "{target name} ({target key})"). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
record Target(String targetKey, String name) {}
