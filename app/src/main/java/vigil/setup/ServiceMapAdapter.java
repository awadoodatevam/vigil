package vigil.setup;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import vigil.servicemap.api.ServiceMapImport;
import vigil.servicemap.api.ServiceMapPart;
import vigil.shared.TenantId;

/**
 * Seed part 5, the service map, imported through {@link ServiceMapImport} (DM-R-56 (5)): the module
 * reads the file by its own rules (BP-A-24).
 */
@Component
class ServiceMapAdapter implements SeedPartImporter {

  private final ServiceMapImport serviceMapImport;

  ServiceMapAdapter(ServiceMapImport serviceMapImport) {
    this.serviceMapImport = serviceMapImport;
  }

  @Override
  public int part() {
    return 5;
  }

  @Override
  public PartResult importPart(
      TenantId tenantId,
      SeedBundle bundle,
      Map<String, JsonNode> files,
      Instant importStartedAt,
      Map<String, UUID> handles) {
    serviceMapImport.importPart(
        tenantId,
        new ServiceMapPart(SeedParts.SERVICE_MAP, bundle.entries().get(SeedParts.SERVICE_MAP)),
        importStartedAt,
        handles);
    JsonNode file = files.get(SeedParts.SERVICE_MAP);
    Map<String, Integer> counts = new LinkedHashMap<>();
    for (String section : SeedParts.part(part()).sections().get(SeedParts.SERVICE_MAP)) {
      counts.put(section, file.get(section).size());
    }
    return new PartResult(counts, Map.of());
  }
}
