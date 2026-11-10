package vigil.investigation.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vigil.shared.TenantId;

/** AI investigation results, summaries and re-runs (PM-M17). */
public interface InvestigationService {

  /**
   * PM-M17-07, AR-R-29: starts a re-run on its own thread and returns its request group id; {@code
   * NOT_FOUND} for an unknown incident, {@code REFUSED} {@code not_rerunnable} for an incident that
   * is not open, resolved or closed. The permission check is the caller's.
   */
  UUID requestRerun(TenantId tenantId, UUID userId, UUID incidentId);

  /** By created_at, then id. */
  List<InvestigationResultView> results(TenantId tenantId, UUID incidentId);

  /** DM-E68-R1: the latest accepted result. */
  Optional<InvestigationResultView> latestAccepted(TenantId tenantId, UUID incidentId);

  /** By created_at, then id. */
  Optional<IncidentSummaryView> latestSummary(TenantId tenantId, UUID incidentId);

  /**
   * DM-E69-R2: the latest summary, or the template summary stored now; joins the caller's
   * transaction.
   */
  IncidentSummaryView summaryForMessage(TenantId tenantId, UUID incidentId);
}
