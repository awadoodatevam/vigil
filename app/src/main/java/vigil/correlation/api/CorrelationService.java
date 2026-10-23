package vigil.correlation.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vigil.shared.TenantId;

/** The reads of vigil-correlation (PM-M08); each filters by tenant. */
public interface CorrelationService {

  Optional<CorrelationSettingsView> settings(TenantId tenantId);

  /** The decision of the alert (DM-R-61: at most one). */
  Optional<GroupingDecisionView> decision(TenantId tenantId, UUID alertId);

  /** The decisions whose incident is the incident, by ascending alert {@code sequence}. */
  List<GroupingDecisionView> decisions(TenantId tenantId, UUID incidentId);

  /** The incident's hypothesis computations by ascending {@code computed_at}, then id. */
  List<HypothesisComputationView> hypothesisComputations(TenantId tenantId, UUID incidentId);

  /** The last of {@link #hypothesisComputations}. */
  Optional<HypothesisComputationView> latestHypothesisComputation(
      TenantId tenantId, UUID incidentId);

  /**
   * PM-M08-10, DM-R-35: the alert ids of the decisions whose matched component is the key, by
   * ascending alert {@code sequence}.
   */
  List<UUID> alertsOfComponent(TenantId tenantId, String componentKey);

  /** The stored cursor (AR-R-17): the highest decided {@code sequence}, 0 without one. */
  long cursor(TenantId tenantId);
}
