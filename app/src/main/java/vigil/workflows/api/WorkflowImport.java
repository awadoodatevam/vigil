package vigil.workflows.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import vigil.shared.TenantId;

/** The seed import of part 16 (AR-B-11, DD-F-06). */
public interface WorkflowImport {

  /**
   * Checks the part as a whole (DM-R-57) and stores the workflow and its version 1 in one
   * transaction; returns no handles. A refused part is {@code VigilError INVALID} "Part workflows
   * refused." with details {@code problems}, and nothing is stored.
   */
  Map<String, UUID> importPart(
      TenantId tenantId, WorkflowPart part, Instant importStartedAt, Map<String, UUID> handles);
}
