package vigil.routing.api;

import java.util.List;

/**
 * A rule of {@code routing_rule_set_revision_1} in seed part 11; values {@code null} when absent.
 */
public record RulePart(
    String ruleKey,
    String name,
    List<ConditionPart> conditions,
    String severity,
    String ownerMode,
    String ownerTeamKey,
    List<String> informedTeamKeys,
    String informAffectedServiceOwners) {}
