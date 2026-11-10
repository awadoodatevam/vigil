package vigil.aigateway.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import vigil.shared.TenantId;

/** The import interface of seed part 7 (DM-R-56 (7), DM-R-57). */
public interface AiGatewayImport {

  /**
   * Checks and stores the part as a whole; throws {@code VigilError INVALID} "Part AI gateway
   * refused." with {@code problems} otherwise. Returns no handles.
   */
  Map<String, UUID> importPart(
      TenantId tenantId, AiGatewayPart part, Instant importStartedAt, Map<String, UUID> handles);
}
