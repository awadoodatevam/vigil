package vigil.routing.api;

import java.util.List;
import vigil.incidents.api.Severity;

/** A routing result (DM-V-09); {@code informedTeamKeys} sorted. */
public record RoutingResult(
    Severity severity, String ownerTeamKey, List<String> informedTeamKeys) {}
