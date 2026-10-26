package vigil.diagnostics.api;

import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * The content of seed part 12 (DM-R-56 (12)). Single values arrive as text and {@code
 * parameterValues} as the JSON tree of the YAML mapping.
 */
public record DiagnosticsPart(List<DefinitionPart> definitions) {

  /** A diagnostic definition of the part. */
  public record DefinitionPart(
      String diagnosticKey,
      String name,
      String operationKey,
      JsonNode parameterValues,
      String summaryTemplate) {}
}
