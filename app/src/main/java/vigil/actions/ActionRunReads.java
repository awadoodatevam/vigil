package vigil.actions;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import vigil.actions.ActionRecords.Proposal;
import vigil.actions.api.ActionRuns;
import vigil.actions.api.ApprovalRequestView;
import vigil.shared.Completed;
import vigil.shared.Key;
import vigil.shared.StepCall;
import vigil.shared.TenantId;
import vigil.shared.TransactionGuard;

/**
 * The reads of vigil-engine (DM-A-15, DM-R-31, DM-R-34; P-24 spec Section 6.8a), each in the
 * caller's transaction, and the reconciliation of an Action step (PM-M12-10). No other bean injects
 * it.
 */
@Component
class ActionRunReads implements ActionRuns {

  private final ActionRecords records;
  private final ActionStep action;

  ActionRunReads(ActionRecords records, ActionStep action) {
    this.records = records;
    this.action = action;
  }

  @Override
  public Optional<UUID> proposal(TenantId tenantId, UUID runId) {
    Actions.require(tenantId, runId);
    return records.proposalOfRun(tenantId, runId).map(Proposal::id);
  }

  @Override
  public Optional<UUID> proposalOfRequest(TenantId tenantId, UUID approvalRequestId) {
    Actions.require(tenantId, approvalRequestId);
    return records.request(tenantId, approvalRequestId).map(ApprovalRequestView::proposalId);
  }

  @Override
  public Optional<UUID> lastExecutedStepExecution(TenantId tenantId, UUID runId) {
    Actions.require(tenantId, runId);
    return records.lastExecuted(tenantId, runId).map(execution -> execution.stepExecutionId);
  }

  @Override
  public List<UUID> earlierApprovers(TenantId tenantId, UUID runId) {
    Actions.require(tenantId, runId);
    return records.approvers(tenantId, runId);
  }

  @Override
  public Optional<Key> latestRecoveryCheckKey(TenantId tenantId, UUID runId) {
    Actions.require(tenantId, runId);
    return records
        .lastExecuted(tenantId, runId)
        .flatMap(execution -> records.proposal(tenantId, execution.proposalId))
        .map(
            proposal ->
                new Key(
                    ActionRecords.JSON
                        .readTree(proposal.actionSnapshotJson())
                        .get("recovery_check_key")
                        .stringValue()));
  }

  @Override
  public Completed reconcile(StepCall call) {
    TransactionGuard.assertNoTransaction();
    Actions.require(call);
    return action.run(call);
  }
}
