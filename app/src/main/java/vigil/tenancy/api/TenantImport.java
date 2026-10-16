package vigil.tenancy.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import vigil.shared.TenantId;

/** The seed import of part 1, tenant settings (AR-B-11, DM-R-56 (1)). */
public interface TenantImport {

  /**
   * Checks the part (DM-R-57) and stores it; refuses the whole part with {@code INVALID} and the
   * list of problems. Returns no handles.
   */
  Map<String, UUID> importPart(
      TenantId tenantId,
      TenantSettingsPart part,
      Instant importStartedAt,
      Map<String, UUID> handles);
}
