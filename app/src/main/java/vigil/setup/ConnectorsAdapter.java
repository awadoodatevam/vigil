package vigil.setup;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import vigil.connectors.api.ConnectorImport;
import vigil.connectors.api.ConnectorsPart;
import vigil.connectors.api.ConnectorsPart.ConnectorInstancePart;
import vigil.connectors.api.ConnectorsPart.CredentialReferencePart;
import vigil.setup.SeedShapes.Row;
import vigil.shared.TenantId;

/** Seed part 3, connectors, imported through {@link ConnectorImport} (DM-R-56 (3)). */
@Component
class ConnectorsAdapter implements SeedPartImporter {

  private final ConnectorImport connectorImport;

  ConnectorsAdapter(ConnectorImport connectorImport) {
    this.connectorImport = connectorImport;
  }

  @Override
  public int part() {
    return 3;
  }

  @Override
  public PartResult importPart(
      TenantId tenantId,
      SeedBundle bundle,
      Map<String, JsonNode> files,
      Instant importStartedAt,
      Map<String, UUID> handles) {
    JsonNode file = files.get(SeedParts.SETTINGS);
    SeedShapes shapes = new SeedShapes();
    List<Row> references =
        shapes.list(
            file, "credential_references", List.of("handle", "purpose", "locator"), Set.of());
    List<Row> instances =
        shapes.list(
            file,
            "connector_instances",
            List.of("handle", "name", "kind", "address", "settings", "credential_ref"),
            Set.of("settings"));
    shapes.refuseIfAny(SeedParts.part(part()).name());
    Map<String, UUID> created =
        connectorImport.importPart(
            tenantId,
            new ConnectorsPart(
                references.stream()
                    .map(
                        r ->
                            new CredentialReferencePart(
                                r.get("handle"), r.get("purpose"), r.get("locator")))
                    .toList(),
                instances.stream()
                    .map(
                        i ->
                            new ConnectorInstancePart(
                                i.get("handle"),
                                i.get("name"),
                                i.get("kind"),
                                i.get("address"),
                                i.tree("settings"),
                                i.get("credential_ref")))
                    .toList()),
            importStartedAt,
            handles);
    Map<String, Integer> counts = new LinkedHashMap<>();
    counts.put("credential_references", references.size());
    counts.put("connector_instances", instances.size());
    return new PartResult(counts, created);
  }
}
