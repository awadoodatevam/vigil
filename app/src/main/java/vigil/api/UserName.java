package vigil.api;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** A user id with its display name (DC-U-06); the id as text when the user does not exist. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
record UserName(String userId, String displayName) {}
