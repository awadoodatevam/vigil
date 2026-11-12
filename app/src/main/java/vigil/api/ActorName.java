package vigil.api;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * An actor (DM-V-01) as an answer: {@code kind} {@code user} or {@code module}; for a module,
 * {@code user_id} and {@code display_name} are {@code null}.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
record ActorName(String kind, String userId, String displayName) {}
