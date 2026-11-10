package vigil.knowledge.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import vigil.shared.TenantId;

/** The import interface of seed part 8 (DM-R-56 (8), DM-R-57). */
public interface KnowledgeImport {

  /**
   * Checks the part, embeds its past incidents and stores it as a whole; throws {@code VigilError
   * INVALID} "Part knowledge refused." with {@code problems} otherwise. Returns no handles.
   *
   * <p>external call: not inside a transaction
   */
  Map<String, UUID> importPart(
      TenantId tenantId, KnowledgePart part, Instant importStartedAt, Map<String, UUID> handles);
}
