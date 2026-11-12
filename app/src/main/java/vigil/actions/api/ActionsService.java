package vigil.actions.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vigil.shared.TenantId;

/** Registered actions, policies and approval decisions (PM-M14); reads have no audit event. */
public interface ActionsService {

  /** Every registered action, by {@code action_key}. */
  List<RegisteredActionView> registeredActions(TenantId tenantId);

  Optional<RegisteredActionView> registeredAction(TenantId tenantId, String actionKey);

  /** Every policy, by action key, then service key with {@code null} first. */
  List<ActionPolicyView> policies(TenantId tenantId);

  /**
   * PM-M14-05, DM-E59-R1: the signed-in user's decision on a request; refusals are {@code
   * FORBIDDEN}, {@code NOT_FOUND} or {@code REFUSED} with details {@code refusal} {@code
   * cancelled}, {@code not_pending}, {@code already_decided}, {@code expired}, {@code
   * not_eligible}, {@code excluded} or {@code snapshot_changed}. Returns the decided request.
   */
  ApprovalRequestView decide(
      TenantId tenantId, UUID requestId, UUID userId, Decision decision, String snapshotHashShown);
}
