package vigil.identity.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import vigil.shared.TenantId;

/** The seed import of part 2, identity (AR-B-11, DM-R-56 (2), AR-S-06). */
public interface IdentityImport {

  /**
   * Checks the part (DM-R-57) and stores it, hashing the passwords; refuses the whole part with
   * {@code INVALID} and the list of problems. Returns no handles.
   */
  Map<String, UUID> importPart(
      TenantId tenantId, IdentityPart part, Instant importStartedAt, Map<String, UUID> handles);
}
