package vigil.reporting.api;

import vigil.shared.TenantId;

/** The reads of vigil-reporting (PM-M21); no audit event. */
public interface ReportingService {

  /** The dashboard metrics over the tenant's live and seeded past incidents (DM-R-52, DM-R-53). */
  DashboardView dashboard(TenantId tenantId);
}
