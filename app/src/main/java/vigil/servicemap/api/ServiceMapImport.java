package vigil.servicemap.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import vigil.shared.TenantId;

/** The seed import of part 5, the service map (AR-B-11, DM-R-56 (5)). */
public interface ServiceMapImport {

  /**
   * Reads the file by the rules of the service map file and stores it as generation 1, with no
   * audit event and no event; refuses the whole part with {@code INVALID} "Part service map
   * refused." and the list of problems. Returns no handles.
   */
  Map<String, UUID> importPart(
      TenantId tenantId, ServiceMapPart part, Instant importStartedAt, Map<String, UUID> handles);
}
