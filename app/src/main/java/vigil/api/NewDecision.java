package vigil.api;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** The answer of AR-I-35: the request's new state, who decided and when (DC-S05-05). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
record NewDecision(String state, UserName user, String at) {}
