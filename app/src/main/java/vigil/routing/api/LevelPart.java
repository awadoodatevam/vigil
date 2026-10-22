package vigil.routing.api;

import java.util.List;

/**
 * A level of {@code escalation_policy_revision_1} in seed part 11; values {@code null} when absent.
 */
public record LevelPart(String level, List<String> teamKeys) {}
