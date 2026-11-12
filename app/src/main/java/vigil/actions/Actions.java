package vigil.actions;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import vigil.actions.api.ActionPolicyView;
import vigil.actions.api.ActionsService;
import vigil.actions.api.ApprovalRequestView;
import vigil.actions.api.Decision;
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

  static void require(Object... arguments) {
    for (Object argument : arguments) {
      if (argument == null) {
        throw new IllegalArgumentException("a required argument is missing");
      }
    }
  }
}
