package vigil.diagnostics.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import vigil.shared.TenantId;

/** The seed import of part 12, diagnostics (AR-B-11, DM-R-56 (12)). */
public interface DiagnosticsImport {

  /**
   * Checks the part (DM-R-57) and stores its diagnostic definitions; refuses the whole part with
   * {@code INVALID} and the list of problems. Returns an empty map.
   */
  Map<String, UUID> importPart(
      TenantId tenantId, DiagnosticsPart part, Instant importStartedAt, Map<String, UUID> handles);
}
