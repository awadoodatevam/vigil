package vigil.actions.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import vigil.shared.TenantId;

/** The seed import of part 14, actions (AR-B-11, DM-R-56 (14)). */
public interface ActionsImport {

  /**
   * Checks the part (DM-R-57) and stores its registered actions and policies; refuses the whole
   * part with {@code INVALID} and the list of problems. Returns an empty map.
   */
  Map<String, UUID> importPart(
      TenantId tenantId, ActionsPart part, Instant importStartedAt, Map<String, UUID> handles);
}
