package vigil.routing.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vigil.shared.Actor;
import vigil.shared.TenantId;

/**
 * Routing rules, escalation levels and routing evaluations (PM-M10). A revision is current when it
 * has the tenant's highest revision number.
 */
public interface RoutingService {

  /**
   * PM-M10-02: evaluates the current revision for the incident, records the evaluation and, while
   * the incident is {@code forming} and not frozen, requests its assignment from vigil-incidents.
   * {@code CORRELATION} only from vigil-correlation, {@code WORKFLOWS} and {@code TEST_REPLAY} only
   * from vigil-workflows ({@code IllegalArgumentException}); an unknown incident is {@code
   * NOT_FOUND}; a tenant without routing rules is an {@code IllegalStateException}.
   */
  EvaluationView evaluate(
      TenantId tenantId, Actor caller, UUID incidentId, RequestedBy requestedBy);

  /**
   * DM-R-27: saves the rules as the next revision for the signed-in user. A malformed request is an
   * {@code IllegalArgumentException}; refusals are {@code FORBIDDEN}, {@code CONFLICT} (the base
   * revision is not the current one) and {@code INVALID} with details {@code problems} and {@code
   * places}.
   */
  RuleSetView saveRuleSet(
      TenantId tenantId, UUID userId, long baseRevision, List<RoutingRule> rules);

  Optional<RuleSetView> ruleSet(TenantId tenantId);

  Optional<RuleSetView> ruleSetRevision(TenantId tenantId, long revision);

  Optional<EscalationPolicyView> escalationPolicy(TenantId tenantId);

  /** By ascending {@code at}, then id; empty for an incident the tenant does not have. */
  List<EvaluationView> evaluations(TenantId tenantId, UUID incidentId);

  Optional<EvaluationView> evaluation(TenantId tenantId, UUID evaluationId);
}
