package vigil.setup;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import vigil.recovery.api.RecoveryImport;
import vigil.recovery.api.RecoveryPart;
import vigil.recovery.api.RecoveryPart.DefinitionPart;
import vigil.setup.SeedShapes.Row;
import vigil.shared.TenantId;

/**
 * Seed part 13, recovery, imported through {@link RecoveryImport} (DM-R-56 (13)): the section
 * {@code recovery_check_definitions} of {@code 04-operations-actions-recovery.yaml}.
 */
@Component
class RecoveryAdapter implements SeedPartImporter {

  private static final String SECTION = "recovery_check_definitions";

  private final RecoveryImport recoveryImport;

  RecoveryAdapter(RecoveryImport recoveryImport) {
    this.recoveryImport = recoveryImport;
  }

  @Override
  public int part() {
    return 13;
  }

  @Override
  public PartResult importPart(
      TenantId tenantId,
      SeedBundle bundle,
      Map<String, JsonNode> files,
      Instant importStartedAt,
      Map<String, UUID> handles) {
    SeedShapes shapes = new SeedShapes();
    List<Row> rows =
        shapes.list(
            files.get(SeedParts.OPERATIONS),
            SECTION,
            List.of(
                "recovery_check_key", "name", "conditions", "observation_window_s", "is_default"),
            Set.of("conditions"));
    shapes.refuseIfAny(SeedParts.part(part()).name());
    recoveryImport.importPart(
        tenantId,
        new RecoveryPart(
            rows.stream()
                .map(
                    row ->
                        new DefinitionPart(
                            row.get("recovery_check_key"),
                            row.get("name"),
                            row.tree("conditions"),
                            row.get("observation_window_s"),
                            row.get("is_default")))
                .toList()),
        importStartedAt,
        handles);
    return new PartResult(Map.of(SECTION, rows.size()), Map.of());
  }
}
