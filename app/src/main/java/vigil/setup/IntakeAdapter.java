package vigil.setup;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import vigil.intake.api.IntakeImport;
import vigil.intake.api.IntakePart;
import vigil.intake.api.IntakePart.AlertTypePart;
import vigil.intake.api.IntakePart.MappingPart;
import vigil.intake.api.IntakePart.SourcePart;
import vigil.setup.SeedShapes.Row;
import vigil.shared.TenantId;

/**
 * Seed part 6, intake, imported through {@link IntakeImport} (DM-R-56 (6)); the first connection
 * tests of its sources start after the part committed (AR-B-11).
 */
@Component
class IntakeAdapter implements SeedPartImporter {

  private final IntakeImport intakeImport;

  IntakeAdapter(IntakeImport intakeImport) {
    this.intakeImport = intakeImport;
  }

  @Override
  public int part() {
    return 6;
  }

  @Override
  public PartResult importPart(
      TenantId tenantId,
      SeedBundle bundle,
      Map<String, JsonNode> files,
      Instant importStartedAt,
      Map<String, UUID> handles) {
    JsonNode file = files.get(SeedParts.MONITORING);
    SeedShapes shapes = new SeedShapes();
    List<Row> sources =
        shapes.list(
            file,
            "monitoring_sources",
            List.of(
                "name",
                "source_key",
                "source_kind",
                "connector_instance",
                "webhook_token_ref",
                "health_check_interval_s",
                "stale_after_s",
                "disconnected_after_s"),
            Set.of());
    List<Row> types =
        shapes.list(file, "alert_types", List.of("alert_type", "display_name"), Set.of());
    List<Row> mappings =
        shapes.list(
            file,
            "alert_type_mappings",
            List.of("source_kind", "order", "field", "operator", "value", "alert_type"),
            Set.of());
    shapes.refuseIfAny(SeedParts.part(part()).name());
    intakeImport.importPart(
        tenantId,
        new IntakePart(
            sources.stream()
                .map(
                    s ->
                        new SourcePart(
                            s.get("name"),
                            s.get("source_key"),
                            s.get("source_kind"),
                            s.get("connector_instance"),
                            s.get("webhook_token_ref"),
                            s.get("health_check_interval_s"),
                            s.get("stale_after_s"),
                            s.get("disconnected_after_s")))
                .toList(),
            types.stream()
                .map(t -> new AlertTypePart(t.get("alert_type"), t.get("display_name")))
                .toList(),
            mappings.stream()
                .map(
                    m ->
                        new MappingPart(
                            m.get("source_kind"),
                            m.get("order"),
                            m.get("field"),
                            m.get("operator"),
                            m.get("value"),
                            m.get("alert_type")))
                .toList()),
        importStartedAt,
        handles);
    Map<String, Integer> counts = new LinkedHashMap<>();
    counts.put("monitoring_sources", sources.size());
    counts.put("alert_types", types.size());
    counts.put("alert_type_mappings", mappings.size());
    return new PartResult(counts, Map.of());
  }

  @Override
  public void afterCommit(TenantId tenantId, Map<String, UUID> handles) {
    intakeImport.startFirstTests(tenantId);
  }
}
