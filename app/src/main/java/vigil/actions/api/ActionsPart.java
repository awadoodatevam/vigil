package vigil.actions.api;

import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * The content of seed part 14 (DM-R-56 (14)). Single values arrive as text, the others as the JSON
 * trees of their YAML values.
 */
public record ActionsPart(List<ActionPart> actions, List<PolicyPart> policies) {

  /** A registered action of the part. */
  public record ActionPart(
      String actionKey,
      String name,
      String operationKey,
      JsonNode parameterValues,
      JsonNode limits,
      String attemptLimit,
      JsonNode precondition,
      JsonNode reconciliation,
      String recoveryCheckKey) {}

  /** An action policy of the part. */
  public record PolicyPart(String actionKey, String serviceKey, String outcome) {}
}
