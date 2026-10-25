package vigil.actions.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vigil.shared.Completed;
import vigil.shared.Key;
import vigil.shared.StepCall;
import vigil.shared.TenantId;

/**
 * What vigil-engine reads from vigil-actions about a workflow run (DM-A-15, DM-R-31, PM-M12-07),
 * and the reconciliation of an interrupted Action step (PM-M12-10).
 */
public interface ActionRuns {

  /** The run's proposal (DM-R-29). */
  Optional<UUID> proposal(TenantId tenantId, UUID runId);

  /** The proposal the approval request refers to. */
  Optional<UUID> proposalOfRequest(TenantId tenantId, UUID approvalRequestId);

  /**
   * The Action step execution of the run's most recent action execution that reached {@code
   * executing}.
   */
  Optional<UUID> lastExecutedStepExecution(TenantId tenantId, UUID runId);

  /** The users who approved the run's approval requests, in decision order. */
  List<UUID> earlierApprovers(TenantId tenantId, UUID runId);

  /** The recovery check key of the run's latest executed action. */
  Optional<Key> latestRecoveryCheckKey(TenantId tenantId, UUID runId);

  /**
   * Reconciles an Action step execution that has no named output, after a restart. External call:
   * not inside a transaction.
   */
  Completed reconcile(StepCall call);
}
