package vigil.recovery.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import vigil.shared.TenantId;

/** The seed import of part 13, recovery (AR-B-11, DM-R-56 (13)). */
public interface RecoveryImport {

  /**
   * Checks the part (DM-R-57) and stores its recovery check definitions; refuses the whole part
   * with {@code INVALID} and the list of problems. Returns an empty map.
   */
  Map<String, UUID> importPart(
      TenantId tenantId, RecoveryPart part, Instant importStartedAt, Map<String, UUID> handles);
}
