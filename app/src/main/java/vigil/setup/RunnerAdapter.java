package vigil.setup;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import vigil.runner.api.RunnerImport;
import vigil.runner.api.RunnerPart;
import vigil.runner.api.RunnerPart.GrantPart;
import vigil.runner.api.RunnerPart.OperationPart;
import vigil.setup.SeedShapes.Row;
import vigil.shared.TenantId;

/** Seed part 4, runner, imported through {@link RunnerImport} (DM-R-56 (4)). */
@Component
class RunnerAdapter implements SeedPartImporter {

  private final RunnerImport runnerImport;

  RunnerAdapter(RunnerImport runnerImport) {
    this.runnerImport = runnerImport;
  }

  @Override
  public int part() {
    return 4;
  }

  @Override
  public PartResult importPart(
      TenantId tenantId,
      SeedBundle bundle,
      Map<String, JsonNode> files,
      Instant importStartedAt,
      Map<String, UUID> handles) {
    JsonNode file = files.get(SeedParts.OPERATIONS);
    SeedShapes shapes = new SeedShapes();
    List<Row> operations =
        shapes.list(
            file,
            "operations",
            List.of(
                "operation_key",
                "name",
                "permission_kind",
                "command_template",
                "parameters",
                "output_parser",
                "timeout_s"),
            Set.of("parameters"));
    List<Row> grants =
        shapes.list(
            file,
            "target_access_grants",
            List.of("target_key", "connector_instance", "permission_kind", "credential_ref"),
            Set.of());
    shapes.refuseIfAny(SeedParts.part(part()).name());
    runnerImport.importPart(
        tenantId,
        new RunnerPart(
            operations.stream()
                .map(
                    o ->
                        new OperationPart(
                            o.get("operation_key"),
                            o.get("name"),
                            o.get("permission_kind"),
                            o.get("command_template"),
                            o.tree("parameters"),
                            o.get("output_parser"),
                            o.get("timeout_s")))
                .toList(),
            grants.stream()
                .map(
                    g ->
                        new GrantPart(
                            g.get("target_key"),
                            g.get("connector_instance"),
                            g.get("permission_kind"),
                            g.get("credential_ref")))
                .toList()),
        importStartedAt,
        handles);
    Map<String, Integer> counts = new LinkedHashMap<>();
    counts.put("operations", operations.size());
    counts.put("target_access_grants", grants.size());
    return new PartResult(counts, Map.of());
  }
}
