package vigil.api;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** A component key with its name (DC-U-06). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
record ComponentName(String componentKey, String name) {}
