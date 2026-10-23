package vigil.correlation.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import vigil.shared.TenantId;

/** The seed import of part 10, correlation (AR-B-11, DM-R-56 (10)). */
public interface CorrelationImport {

  /**
   * Checks the part (DM-R-57) and stores the tenant's correlation settings; refuses the whole part
   * with {@code INVALID} "Part correlation refused." and the list of problems. Returns an empty
   * map.
   */
  Map<String, UUID> importPart(
      TenantId tenantId,
      CorrelationSettingsPart part,
      Instant importStartedAt,
      Map<String, UUID> handles);
}
