package vigil.setup;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import vigil.correlation.api.CorrelationImport;
import vigil.correlation.api.CorrelationSettingsPart;
import vigil.setup.SeedShapes.Row;
import vigil.shared.TenantId;

/** Seed part 10, correlation, imported through {@link CorrelationImport} (DM-R-56 (10)). */
@Component
class CorrelationAdapter implements SeedPartImporter {

  private static final String SECTION = "correlation_settings";
  private static final List<String> FIELDS =
      List.of(
          "grouping_window_s", "settle_period_s", "max_forming_s", "stability_check_interval_s");

  private final CorrelationImport correlationImport;

  CorrelationAdapter(CorrelationImport correlationImport) {
    this.correlationImport = correlationImport;
  }

  @Override
  public int part() {
    return 10;
  }

  @Override
  public PartResult importPart(
      TenantId tenantId,
      SeedBundle bundle,
      Map<String, JsonNode> files,
      Instant importStartedAt,
      Map<String, UUID> handles) {
    SeedShapes shapes = new SeedShapes();
    Row settings = shapes.mapping(files.get(SeedParts.SETTINGS), SECTION, FIELDS);
    shapes.refuseIfAny(SeedParts.part(part()).name());
    correlationImport.importPart(
        tenantId,
        new CorrelationSettingsPart(
            settings.get("grouping_window_s"),
            settings.get("settle_period_s"),
            settings.get("max_forming_s"),
            settings.get("stability_check_interval_s")),
        importStartedAt,
        handles);
    return new PartResult(Map.of(SECTION, 1), Map.of());
  }
}
