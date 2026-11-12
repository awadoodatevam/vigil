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

  /** The approval request with this id (DM-E-58), or empty. */
  Optional<ApprovalRequestView> request(TenantId tenantId, UUID requestId);

  /** The run's approval requests, by {@code sequence_in_run}. */
  List<ApprovalRequestView> requests(TenantId tenantId, UUID runId);

  /** The run's action proposal (DM-E-57), or empty. */
  Optional<ProposalView> proposal(TenantId tenantId, UUID runId);

  /** Whether an Approval step execution of the run completed without a proposal (DM-R-29). */
  boolean withoutProposal(TenantId tenantId, UUID runId);

  /** The run's action executions (DM-E-60), by attempt number, then started_at, then id. */
  List<ActionExecutionView> executions(TenantId tenantId, UUID runId);

  /**
   * DM-E59-R1, CTX-D-079 (3): the refusal of checks 1 to 6 of a decision that the user would get
   * now, read without a lock; empty when none applies or the request does not exist.
   */
  Optional<DecisionRefusal> decisionRefusal(TenantId tenantId, UUID requestId, UUID userId);
}
