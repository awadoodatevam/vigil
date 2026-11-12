package vigil.setup;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import vigil.reporting.api.ReportingImport;
import vigil.reporting.api.ReportingPart;
import vigil.reporting.api.ReportingPart.BaselinePart;
import vigil.setup.SeedShapes.Row;
import vigil.shared.TenantId;

/**
 * Seed part 17, reporting, imported through {@link ReportingImport} (DM-R-56 (17)): the section
 * {@code baseline_values} of {@code 07-settings.yaml}.
 */
@Component
class ReportingAdapter implements SeedPartImporter {

  private static final String SECTION = "baseline_values";

  private final ReportingImport reportingImport;

  ReportingAdapter(ReportingImport reportingImport) {
    this.reportingImport = reportingImport;
  }

  @Override
  public int part() {
    return 17;
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
            files.get(SeedParts.SETTINGS),
            SECTION,
            List.of("metric_key", "value", "unit", "label"),
            Set.of());
    shapes.refuseIfAny(SeedParts.part(part()).name());
    reportingImport.importPart(
        tenantId,
        new ReportingPart(
            rows.stream()
                .map(
                    row ->
                        new BaselinePart(
                            row.get("metric_key"),
                            row.get("value"),
                            row.get("unit"),
                            row.get("label")))
                .toList()),
        importStartedAt,
        handles);
    return new PartResult(Map.of(SECTION, rows.size()), Map.of());
  }
}
