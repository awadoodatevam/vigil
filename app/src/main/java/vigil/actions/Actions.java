package vigil.actions;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import vigil.actions.api.ActionExecutionView;
import vigil.actions.api.ActionPolicyView;
import vigil.actions.api.ActionsService;
import vigil.actions.api.ApprovalRequestView;
import vigil.actions.api.Decision;
import vigil.actions.api.DecisionRefusal;
import vigil.actions.api.ProposalView;
import vigil.actions.api.RegisteredActionView;
import vigil.shared.TenantId;

/** The reads and the decision of vigil-actions (PM-M14). */
@Service
class Actions implements ActionsService {

  private final ActionRecords records;
  private final Decisions decisions;

  Actions(ActionRecords records, Decisions decisions) {
    this.records = records;
    this.decisions = decisions;
  }

  @Override
  public List<RegisteredActionView> registeredActions(TenantId tenantId) {
    require(tenantId);
    return List.copyOf(records.actions(tenantId));
  }

  @Override
  public Optional<RegisteredActionView> registeredAction(TenantId tenantId, String actionKey) {
    require(tenantId, actionKey);
    return records.action(tenantId, actionKey);
  }

  @Override
  public List<ActionPolicyView> policies(TenantId tenantId) {
    require(tenantId);
    return List.copyOf(records.policies(tenantId));
  }

  @Override
  public ApprovalRequestView decide(
      TenantId tenantId, UUID requestId, UUID userId, Decision decision, String snapshotHashShown) {
    require(tenantId, requestId, userId, decision, snapshotHashShown);
    return decisions.decide(tenantId, requestId, userId, decision, snapshotHashShown);
  }

  @Override
  public Optional<ApprovalRequestView> request(TenantId tenantId, UUID requestId) {
    require(tenantId, requestId);
    return records.request(tenantId, requestId);
  }

  @Override
  public List<ApprovalRequestView> requests(TenantId tenantId, UUID runId) {
    require(tenantId, runId);
    return List.copyOf(records.requestsBySequence(tenantId, runId));
  }

  @Override
  public Optional<ProposalView> proposal(TenantId tenantId, UUID runId) {
    require(tenantId, runId);
    return records.proposalView(tenantId, runId);
  }

  @Override
  public boolean withoutProposal(TenantId tenantId, UUID runId) {
    require(tenantId, runId);
    return records.withoutProposal(tenantId, "run_id", runId);
  }

  @Override
  public List<ActionExecutionView> executions(TenantId tenantId, UUID runId) {
    require(tenantId, runId);
    return List.copyOf(records.executions(tenantId, runId));
  }

  @Override
  public Optional<DecisionRefusal> decisionRefusal(TenantId tenantId, UUID requestId, UUID userId) {
    require(tenantId, requestId, userId);
    return decisions.refusal(tenantId, requestId, userId);
  }

  static void require(Object... arguments) {
    for (Object argument : arguments) {
      if (argument == null) {
        throw new IllegalArgumentException("a required argument is missing");
      }
    }
  }
}
