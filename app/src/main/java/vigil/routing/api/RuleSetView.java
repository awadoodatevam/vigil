package vigil.routing.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import vigil.shared.Actor;

/** A routing rule set revision (DM-E-41). */
public record RuleSetView(
    UUID id,
    long revision,
    List<RoutingRule> rules,
    Actor savedBy,
    Instant savedAt,
    String changeSummary) {}
