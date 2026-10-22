package vigil.incidents.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import vigil.shared.TenantId;

/** The seed import of part 9, incidents (AR-B-11, DM-R-56 (9)). */
public interface IncidentImport {

  /**
   * Checks the part (DM-R-57) and stores the tenant's incident settings; refuses the whole part
   * with {@code INVALID} "Part incidents refused." and the list of problems. Returns an empty map.
   */
  Map<String, UUID> importPart(
      TenantId tenantId,
      IncidentSettingsPart part,
      Instant importStartedAt,
      Map<String, UUID> handles);
}
