package vigil.setup;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import vigil.identity.api.IdentityService;
import vigil.servicemap.api.ComponentView;
import vigil.servicemap.api.ServiceMapService;
import vigil.servicemap.api.ServiceView;
import vigil.shared.Key;
import vigil.shared.TenantId;

/** DM-R-60 "team keys in service map": the owner teams of part 5 exist in part 2. */
@Component
class ServiceMapTeamCheck implements SeedKeyCheck {

  private final ServiceMapService serviceMap;
  private final IdentityService identity;

  ServiceMapTeamCheck(ServiceMapService serviceMap, IdentityService identity) {
    this.serviceMap = serviceMap;
    this.identity = identity;
  }

  @Override
  public int referringPart() {
    return 5;
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
    for (ServiceView service : serviceMap.services(tenantId)) {
      check(tenantId, "services", service.serviceKey(), service.ownerTeamKey(), problems);
    }
    for (ComponentView component : serviceMap.components(tenantId)) {
      check(tenantId, "components", component.componentKey(), component.ownerTeamKey(), problems);
    }
    return problems;
  }

  private void check(
      TenantId tenantId, String section, String key, String team, List<String> problems) {
    if (team != null && identity.team(tenantId, new Key(team)).isEmpty()) {
      problems.add(section + " '" + key + "': team '" + team + "' does not exist");
    }
  }
}
