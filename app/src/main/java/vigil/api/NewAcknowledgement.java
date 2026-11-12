package vigil.api;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** The answer of AR-I-33: who acknowledged and when (DC-S04-04). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
record NewAcknowledgement(UserName user, String at) {}
