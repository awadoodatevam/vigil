package vigil.routing.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import vigil.shared.TenantId;

/** The seed import of part 11, routing (AR-B-11, DM-R-56 (11)). */
public interface RoutingImport {

  /**
   * Checks the part (DM-R-57) and stores routing rule set revision 1 and escalation policy revision
   * 1; refuses the whole part with {@code INVALID} "Part routing refused." and the list of
   * problems. Returns an empty map.
   */
  Map<String, UUID> importPart(
      TenantId tenantId, RoutingPart part, Instant importStartedAt, Map<String, UUID> handles);
}
