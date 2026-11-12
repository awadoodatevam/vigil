package vigil.api;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** A parameter of an action with its value as stored (any JSON value). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
record ParameterValue(String name, JsonNode value) {}
