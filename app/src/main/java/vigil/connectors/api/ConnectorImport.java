package vigil.connectors.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import vigil.shared.TenantId;

/** The seed import of part 3, connectors (AR-B-11, DM-R-56 (3)). */
public interface ConnectorImport {

  /**
   * Checks the part (DM-R-57) and stores its credential references and connector instances; refuses
   * the whole part with {@code INVALID} and the list of problems. Returns the new id of every
   * handle of the part.
   */
  Map<String, UUID> importPart(
      TenantId tenantId, ConnectorsPart part, Instant importStartedAt, Map<String, UUID> handles);
}
