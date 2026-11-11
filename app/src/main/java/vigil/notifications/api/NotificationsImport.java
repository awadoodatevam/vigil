package vigil.notifications.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import vigil.shared.TenantId;

/** The import of seed part 15 (AR-B-11), in the caller's transaction. */
public interface NotificationsImport {

  /**
   * Checks the part as a whole (DM-R-57) and stores every address {@code active}; a refused part is
   * {@code VigilError INVALID} "Part notifications refused." with {@code problems}. Returns no
   * handles.
   */
  Map<String, UUID> importPart(
      TenantId tenantId,
      NotificationsPart part,
      Instant importStartedAt,
      Map<String, UUID> handles);
}
