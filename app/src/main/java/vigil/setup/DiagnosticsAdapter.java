package vigil.setup;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import vigil.diagnostics.api.DiagnosticsImport;
import vigil.diagnostics.api.DiagnosticsPart;
import vigil.diagnostics.api.DiagnosticsPart.DefinitionPart;
import vigil.setup.SeedShapes.Row;
import vigil.shared.TenantId;

/** Seed part 12, diagnostics, imported through {@link DiagnosticsImport} (DM-R-56 (12)). */
@Component
class DiagnosticsAdapter implements SeedPartImporter {

  private final DiagnosticsImport diagnosticsImport;

  DiagnosticsAdapter(DiagnosticsImport diagnosticsImport) {
    this.diagnosticsImport = diagnosticsImport;
  }

  @Override
  public int part() {
    return 12;
  }

  @Override
  public PartResult importPart(
      TenantId tenantId,
      SeedBundle bundle,
      Map<String, JsonNode> files,
      Instant importStartedAt,
      Map<String, UUID> handles) {
    SeedShapes shapes = new SeedShapes();
    List<Row> definitions =
        shapes.list(
            files.get(SeedParts.OPERATIONS),
            "diagnostic_definitions",
            List.of(
                "diagnostic_key", "name", "operation_key", "parameter_values", "summary_template"),
            Set.of("parameter_values"));
    shapes.refuseIfAny(SeedParts.part(part()).name());
    diagnosticsImport.importPart(
        tenantId,
        new DiagnosticsPart(
            definitions.stream()
                .map(
                    d ->
                        new DefinitionPart(
                            d.get("diagnostic_key"),
                            d.get("name"),
                            d.get("operation_key"),
                            d.tree("parameter_values"),
                            d.get("summary_template")))
                .toList()),
        importStartedAt,
        handles);
    Map<String, Integer> counts = new LinkedHashMap<>();
    counts.put("diagnostic_definitions", definitions.size());
    return new PartResult(counts, Map.of());
  }
}
