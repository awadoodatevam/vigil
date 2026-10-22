package vigil.routing.api;

import java.util.List;

/**
 * Seed part 11 as {@code vigil-setup} reads it from {@code 05-routing-escalation.yaml}; values are
 * {@code null} when absent.
 */
public record RoutingPart(List<RulePart> rules, List<LevelPart> levels) {}
