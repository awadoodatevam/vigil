package vigil.setup;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import vigil.shared.TenantId;

/**
 * The import of one seed part (PM-M22-03, AR-B-11): reads its sections from the checked trees,
 * checks their shape and calls its module's import interface. A part joins the import by a bean of
 * this type.
 */
interface SeedPartImporter {

  /** The part's number in DM-R-56. */
  int part();

  /**
   * Imports the part; {@code files} holds the checked trees by entry name. Throws {@code VigilError
   * INVALID} "Part {name} refused." with {@code problems} for a refused part.
   */
  PartResult importPart(
      TenantId tenantId,
      SeedBundle bundle,
      Map<String, JsonNode> files,
      Instant importStartedAt,
      Map<String, UUID> handles);

  /** Work the part's new records start after its transaction committed (AR-B-11). */
  default void afterCommit(TenantId tenantId, Map<String, UUID> handles) {}

  /** The counts of a part (per section) and the handles of its new records. */
  record PartResult(Map<String, Integer> counts, Map<String, UUID> handles) {}
}
