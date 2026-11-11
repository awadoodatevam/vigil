package vigil.recovery;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vigil.recovery.RecoveryRecords.Definition;
import vigil.recovery.api.RecoveryCheckView;
import vigil.recovery.api.RecoveryService;
import vigil.recovery.api.RecoveryVerificationView;
import vigil.shared.TenantId;

/** The reads of vigil-recovery (PM-M15). */
@Service
class Recovery implements RecoveryService {

  private final RecoveryRecords records;
  private final Evaluation evaluation;

  Recovery(RecoveryRecords records, Evaluation evaluation) {
    this.records = records;
    this.evaluation = evaluation;
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED, readOnly = true)
  public List<RecoveryCheckView> definitions(TenantId tenantId) {
    require(tenantId);
    return records.definitions(tenantId).stream().map(d -> view(tenantId, d)).toList();
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED, readOnly = true)
  public Optional<RecoveryCheckView> definition(TenantId tenantId, String recoveryCheckKey) {
    require(tenantId, recoveryCheckKey);
    return records.definition(tenantId, recoveryCheckKey).map(d -> view(tenantId, d));
  }

  @Override
  public List<RecoveryVerificationView> verifications(TenantId tenantId, UUID incidentId) {
    require(tenantId, incidentId);
    return List.copyOf(records.verifications(tenantId, incidentId));
  }

  private RecoveryCheckView view(TenantId tenantId, Definition definition) {
    return new RecoveryCheckView(
        definition.recoveryCheckKey(),
        definition.name(),
        definition.conditions(),
        Duration.ofSeconds(definition.observationWindowSeconds()),
        definition.isDefault(),
        definition.active(),
        definition.conditions().stream()
            .map(condition -> evaluation.requirementText(tenantId, condition))
            .toList());
  }

  static void require(Object... arguments) {
    for (Object argument : arguments) {
      if (argument == null) {
        throw new IllegalArgumentException("a required argument is missing");
      }
    }
  }
}
