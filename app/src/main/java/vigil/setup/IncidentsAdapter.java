package vigil.setup;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import vigil.incidents.api.IncidentImport;
import vigil.incidents.api.IncidentSettingsPart;
import vigil.setup.SeedShapes.Row;
import vigil.shared.TenantId;

/** Seed part 9, incidents, imported through {@link IncidentImport} (DM-R-56 (9)). */
@Component
class IncidentsAdapter implements SeedPartImporter {

  private static final String SECTION = "incident_settings";
  private static final List<String> FIELDS = List.of("reopen_window_s", "closure_check_interval_s");

  private final IncidentImport incidentImport;

  IncidentsAdapter(IncidentImport incidentImport) {
    this.incidentImport = incidentImport;
  }

  @Override
  public int part() {
    return 9;
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
    incidentImport.importPart(
        tenantId,
        new IncidentSettingsPart(
            settings.get("reopen_window_s"), settings.get("closure_check_interval_s")),
        importStartedAt,
        handles);
    return new PartResult(Map.of(SECTION, 1), Map.of());
  }
}
