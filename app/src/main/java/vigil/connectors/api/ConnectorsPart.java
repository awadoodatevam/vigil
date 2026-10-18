package vigil.connectors.api;

import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * The content of seed part 3 (DM-R-56 (3)). Values arrive as text, and {@code settings} as the JSON
 * tree of the YAML mapping, so the import reports every bad value as a problem.
 */
public record ConnectorsPart(
    List<CredentialReferencePart> credentialReferences,
    List<ConnectorInstancePart> connectorInstances) {

  /** A credential reference of the part. */
  public record CredentialReferencePart(String handle, String purpose, String locator) {}

  /** A connector instance of the part; {@code credentialRef} is a handle of the part or empty. */
  public record ConnectorInstancePart(
      String handle,
      String name,
      String kind,
      String address,
      JsonNode settings,
      String credentialRef) {}
}
