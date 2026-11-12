package vigil.recovery.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vigil.shared.TenantId;

/** The reads of vigil-recovery (PM-M15); no audit event. */
public interface RecoveryService {

  /** Every recovery check definition, by {@code recovery_check_key}. */
  List<RecoveryCheckView> definitions(TenantId tenantId);

  /** The definition with that key (PM-M15-04, PM-I-26), or empty. */
  Optional<RecoveryCheckView> definition(TenantId tenantId, String recoveryCheckKey);

  /** The incident's verifications, by {@code started_at}, then id. */
  List<RecoveryVerificationView> verifications(TenantId tenantId, UUID incidentId);
}
