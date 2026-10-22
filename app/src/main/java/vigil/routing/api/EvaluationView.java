package vigil.routing.api;

import java.time.Instant;
import java.util.UUID;

/**
 * A routing evaluation (DM-E-43). {@code matchedRuleName} is the name of the matched rule in the
 * revision used; {@code notAppliedReason} is {@code null} when applied.
 */
public record EvaluationView(
    UUID id,
    UUID incidentId,
    RequestedBy requestedBy,
    long ruleSetRevision,
    String inputsJson,
    String matchedRuleKey,
    String matchedRuleName,
    RoutingResult result,
    boolean applied,
    NotAppliedReason notAppliedReason,
    Instant at) {}
