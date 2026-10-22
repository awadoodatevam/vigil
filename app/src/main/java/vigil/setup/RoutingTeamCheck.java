package vigil.setup;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import vigil.identity.api.IdentityService;
import vigil.routing.api.EscalationLevel;
import vigil.routing.api.RoutingService;
import vigil.shared.Key;
import vigil.shared.TenantId;

/** DM-R-60 "team keys in escalation levels": the teams of part 11's levels exist in part 2. */
@Component
class RoutingTeamCheck implements SeedKeyCheck {

  private final RoutingService routing;
  private final IdentityService identity;

  RoutingTeamCheck(RoutingService routing, IdentityService identity) {
    this.routing = routing;
    this.identity = identity;
  }

  @Override
  public int referringPart() {
    return 11;
  }

  @Override
  public int targetPart() {
    return 2;
  }

  @Override
  public List<String> problems(
      TenantId tenantId,
      SeedBundle bundle,
      Map<String, JsonNode> files,
      Map<String, UUID> handles) {
    List<String> problems = new ArrayList<>();
    for (EscalationLevel level :
        routing.escalationPolicy(tenantId).map(policy -> policy.levels()).orElse(List.of())) {
      for (String team : level.teamKeys()) {
        if (identity.team(tenantId, new Key(team)).isEmpty()) {
          problems.add(
              "escalation level " + level.level() + ": team '" + team + "' does not exist");
        }
      }
    }
    return problems;
  }
}
