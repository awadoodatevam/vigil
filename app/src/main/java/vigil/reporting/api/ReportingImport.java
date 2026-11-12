package vigil.reporting.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import vigil.shared.TenantId;

/** The seed import of part 17, reporting (AR-B-11, DM-R-56 (17)). */
public interface ReportingImport {

  /**
   * Checks the part (DM-R-57) and stores its baseline values; refuses the whole part with {@code
   * INVALID} and the list of problems. Returns an empty map.
   */
  Map<String, UUID> importPart(
      TenantId tenantId, ReportingPart part, Instant importStartedAt, Map<String, UUID> handles);
}
