package vigil.api;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** The refusal that applies before an action, with the module's key and text (AR-I-03). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
record Refusal(String key, String message) {}
