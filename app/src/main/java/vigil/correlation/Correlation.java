package vigil.correlation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import vigil.correlation.api.CorrelationService;
import vigil.correlation.api.CorrelationSettingsView;
import vigil.correlation.api.GroupingDecisionView;
import vigil.correlation.api.HypothesisComputationView;
import vigil.shared.TenantId;

/** The reads of vigil-correlation (P-16 spec Section 6.4); reads have no transaction. */
@Service
class Correlation implements CorrelationService {

  private final CorrelationRecords records;

  Correlation(CorrelationRecords records) {
    this.records = records;
  }

  @Override
  public Optional<CorrelationSettingsView> settings(TenantId tenantId) {
    required(tenantId);
    return records.settings(tenantId);
  }

  @Override
  public Optional<GroupingDecisionView> decision(TenantId tenantId, UUID alertId) {
    required(tenantId, alertId);
    return records.decision(tenantId, alertId);
  }

  @Override
  public List<GroupingDecisionView> decisions(TenantId tenantId, UUID incidentId) {
    required(tenantId, incidentId);
    return records.decisions(tenantId, incidentId);
  }

  @Override
  public List<HypothesisComputationView> hypothesisComputations(
      TenantId tenantId, UUID incidentId) {
    required(tenantId, incidentId);
    return records.computations(tenantId, incidentId);
  }

  @Override
  public Optional<HypothesisComputationView> latestHypothesisComputation(
      TenantId tenantId, UUID incidentId) {
    required(tenantId, incidentId);
    return records.latestComputation(tenantId, incidentId);
  }

  @Override
  public List<UUID> alertsOfComponent(TenantId tenantId, String componentKey) {
    required(tenantId, componentKey);
    return records.alertsOfComponent(tenantId, componentKey);
  }

  @Override
  public long cursor(TenantId tenantId) {
    required(tenantId);
    return records.cursor(tenantId);
  }

  static void required(Object... arguments) {
    for (Object argument : arguments) {
      if (argument == null) {
        throw new IllegalArgumentException("a required argument is missing");
      }
    }
  }
}
