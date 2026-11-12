package vigil.setup;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import vigil.actions.api.ActionPolicyView;
import vigil.actions.api.ActionsService;
import vigil.servicemap.api.ServiceMapService;
import vigil.shared.TenantId;

/** DM-R-60: the services of the action policies of part 14 exist in part 5. */
@Component
class ActionServiceCheck implements SeedKeyCheck {

  private final ActionsService actions;
  private final ServiceMapService serviceMap;

  ActionServiceCheck(ActionsService actions, ServiceMapService serviceMap) {
    this.actions = actions;
    this.serviceMap = serviceMap;
  }

  @Override
  public int referringPart() {
    return 14;
  }

  @Override
  public int targetPart() {
    return 5;
  }

  @Override
  public List<String> problems(
      TenantId tenantId,
      SeedBundle bundle,
      Map<String, JsonNode> files,
      Map<String, UUID> handles) {
    List<String> problems = new ArrayList<>();
    for (ActionPolicyView policy : actions.policies(tenantId)) {
      String service = policy.serviceKey();
      if (service != null && serviceMap.service(tenantId, service).isEmpty()) {
        problems.add(
            "action policy of '"
                + policy.actionKey()
                + "': service '"
                + service
                + "' does not exist");
      }
    }
    return problems;
  }
}
