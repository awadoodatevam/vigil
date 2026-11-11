package vigil.recovery.api;

import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * The content of seed part 13 (DM-R-56 (13)). Single values arrive as text and {@code conditions}
 * as the JSON tree of the YAML list.
 */
public record RecoveryPart(List<DefinitionPart> definitions) {

  /** A recovery check definition of the part. */
  public record DefinitionPart(
      String recoveryCheckKey,
      String name,
      JsonNode conditions,
      String observationWindowS,
      String isDefault) {}
}
