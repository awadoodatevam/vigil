package vigil.routing.api;

import java.util.List;

/** One escalation level (DM-E-42); {@code teamKeys} sorted. */
public record EscalationLevel(long level, List<String> teamKeys) {}
