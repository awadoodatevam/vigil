package vigil.runner.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import vigil.shared.TenantId;

/** The seed import of part 4, runner (AR-B-11, DM-R-56 (4)). */
public interface RunnerImport {

  /**
   * Checks the part (DM-R-57) and stores its operations and grants; refuses the whole part with
   * {@code INVALID} and the list of problems. {@code handles} holds the handles of part 3. Returns
   * an empty map.
   */
  Map<String, UUID> importPart(
      TenantId tenantId, RunnerPart part, Instant importStartedAt, Map<String, UUID> handles);
}
