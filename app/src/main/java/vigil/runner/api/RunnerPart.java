package vigil.runner.api;

import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * The content of seed part 4 (DM-R-56 (4)). Single values arrive as text and {@code parameters} as
 * the JSON tree of the YAML list, so the import reports every bad value as a problem; {@code
 * connectorInstance} and {@code credentialRef} are handles of part 3.
 */
public record RunnerPart(List<OperationPart> operations, List<GrantPart> grants) {

  /** An operation of the part; {@code timeout} is the written {@code timeout_s}. */
  public record OperationPart(
      String operationKey,
      String name,
      String permissionKind,
      String commandTemplate,
      JsonNode parameters,
      String outputParser,
      String timeout) {}

  /** A target access grant of the part. */
  public record GrantPart(
      String targetKey, String connectorInstance, String permissionKind, String credentialRef) {}
}
