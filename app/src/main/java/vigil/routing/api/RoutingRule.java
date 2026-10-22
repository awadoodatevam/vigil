package vigil.routing.api;

import java.util.List;
import vigil.incidents.api.Severity;

/**
 * A routing rule (DM-E-41). In a save, {@code ruleKey} {@code null} means a new rule and {@code
 * name} and {@code ownerTeamKey} may be {@code null} or empty; in a view every field is set and
 * {@code informedTeamKeys} is sorted.
 */
public record RoutingRule(
    String ruleKey,
    String name,
    List<RoutingCondition> conditions,
    Severity severity,
    OwnerMode ownerMode,
    String ownerTeamKey,
    List<String> informedTeamKeys,
    boolean informAffectedServiceOwners) {}
