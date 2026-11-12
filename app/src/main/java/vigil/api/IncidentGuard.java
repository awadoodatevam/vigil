package vigil.api;

import java.util.UUID;
import java.util.regex.Pattern;
import vigil.incidents.api.IncidentService;
import vigil.incidents.api.IncidentState;
import vigil.incidents.api.IncidentView;
import vigil.shared.TenantId;
import vigil.shared.VigilError;
import vigil.shared.VigilError.Code;

/**
 * The incident of a workspace operation (DC-S03-01, DM-E32-R3): a text that is not a UUID in the
 * 8-4-4-4-12 form, an id of no incident of the tenant and a {@code forming} incident are {@code
 * not_found}.
 */
final class IncidentGuard {

  private static final Pattern UUID_TEXT =
      Pattern.compile(
          "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

  private IncidentGuard() {}

  /** The incident, which may be {@code merged}. */
  static IncidentView incident(IncidentService incidents, TenantId tenantId, String incidentId) {
    if (incidentId == null || !UUID_TEXT.matcher(incidentId).matches()) {
      throw new VigilError(Code.NOT_FOUND);
    }
    IncidentView incident =
        incidents
            .incident(tenantId, UUID.fromString(incidentId))
            .orElseThrow(() -> new VigilError(Code.NOT_FOUND));
    if (incident.state() == IncidentState.FORMING) {
      throw new VigilError(Code.NOT_FOUND);
    }
    return incident;
  }

  /** The incident of a workspace section: a {@code merged} incident is also {@code not_found}. */
  static IncidentView workspace(IncidentService incidents, TenantId tenantId, String incidentId) {
    IncidentView incident = incident(incidents, tenantId, incidentId);
    if (incident.state() == IncidentState.MERGED) {
      throw new VigilError(Code.NOT_FOUND);
    }
    return incident;
  }
}
