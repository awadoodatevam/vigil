package vigil.intake.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import vigil.shared.TenantId;

/** The seed import of part 6, intake (AR-B-11, DM-R-56 (6)). */
public interface IntakeImport {

  /**
   * Checks the part (DM-R-57) and stores its sources, alert types and mappings; refuses the whole
   * part with {@code INVALID} "Part intake refused." and the list of problems. Returns an empty
   * map.
   */
  Map<String, UUID> importPart(
      TenantId tenantId, IntakePart part, Instant importStartedAt, Map<String, UUID> handles);

  /**
   * Starts the first connection test of each active source of the tenant, each on its own virtual
   * thread, and returns at once (AR-B-11, DM-SM03-T1).
   */
  void startFirstTests(TenantId tenantId);
}
