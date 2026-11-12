package vigil.actions;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import vigil.actions.ActionRecords.Proposal;
import vigil.actions.api.ActionPolicyView;
import vigil.actions.api.PolicyOutcome;
import vigil.actions.api.RegisteredActionView;
import vigil.incidents.api.AffectedServiceCopy;
import vigil.incidents.api.IncidentView;
import vigil.knowledge.api.KnowledgeService;
import vigil.knowledge.api.SearchOutcome;
import vigil.knowledge.api.SimilarityResultView;
import vigil.servicemap.api.ServiceMapService;
import vigil.shared.Ids;
import vigil.shared.StepCall;
import vigil.shared.TenantId;

/**
 * The run's action proposal from the incident's latest similarity search result (DM-R-29, DM-E-57)
 * and the policy check (DM-E56-R2; P-24 spec Section 6.4). Joins the caller's transaction.
 */
@Component
class Proposals {

  private final ActionRecords records;
  private final KnowledgeService knowledge;
  private final ServiceMapService serviceMap;

  Proposals(ActionRecords records, KnowledgeService knowledge, ServiceMapService serviceMap) {
    this.records = records;
    this.knowledge = knowledge;
    this.serviceMap = serviceMap;
  }

  /** A new proposal for the call's run, not stored; empty in every "no proposal" case. */
  Optional<Proposal> propose(StepCall call, IncidentView incident) {
    TenantId tenantId = call.tenantId();
    Optional<SimilarityResultView> found = knowledge.latestSearchResult(tenantId, incident.id());
    if (found.isEmpty()
        || found.get().outcome() != SearchOutcome.MATCH
        || found.get().fixActionKey() == null) {
      return Optional.empty();
    }
    SimilarityResultView result = found.get();
    Optional<RegisteredActionView> action =
        records.action(tenantId, result.fixActionKey()).filter(RegisteredActionView::active);
    String component = incident.hypothesisComponentKey();
    if (action.isEmpty() || component == null) {
      return Optional.empty();
    }
    Optional<String> target = serviceMap.target(tenantId, component);
    if (target.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        new Proposal(
            Ids.newId(),
            incident.id(),
            call.runId(),
            action.get().actionKey(),
            ActionRecords.JSON.writeValueAsString(Snapshots.action(action.get())),
            component,
            target.get(),
            result.id(),
            result.bestPastIncidentKey(),
            result.score(),
            result.successCount() == null ? 0 : result.successCount()));
  }

  /** DM-E56-R2: the most restrictive policy over the incident's affected services. */
  PolicyOutcome check(TenantId tenantId, String actionKey, IncidentView incident) {
    List<ActionPolicyView> policies =
        records.policies(tenantId).stream()
            .filter(policy -> policy.active() && policy.actionKey().equals(actionKey))
            .toList();
    ActionPolicyView fallback =
        policies.stream()
            .filter(policy -> policy.serviceKey() == null)
            .findFirst()
            .orElseThrow(
                () -> new IllegalStateException("no default policy for action " + actionKey));
    List<PolicyOutcome> taken = new ArrayList<>();
    for (AffectedServiceCopy service : incident.affectedServices()) {
      taken.add(
          policies.stream()
              .filter(policy -> service.serviceKey().equals(policy.serviceKey()))
              .findFirst()
              .orElse(fallback)
              .outcome());
    }
    if (taken.isEmpty()) {
      taken.add(fallback.outcome());
    }
    return taken.stream().min(Enum::compareTo).orElseThrow();
  }
}
