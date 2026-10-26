package vigil.setup;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import vigil.runner.api.GrantView;
import vigil.runner.api.RunnerService;
import vigil.servicemap.api.ComponentView;
import vigil.servicemap.api.ServiceMapService;
import vigil.shared.TenantId;

/** DM-R-60 "target keys in service map": the targets of part 5 have an active grant of part 4. */
@Component
class ServiceMapTargetCheck implements SeedKeyCheck {

  private final ServiceMapService serviceMap;
  private final RunnerService runner;

  ServiceMapTargetCheck(ServiceMapService serviceMap, RunnerService runner) {
    this.serviceMap = serviceMap;
    this.runner = runner;
  }

  @Override
  public int referringPart() {
    return 5;
  }

  @Override
  public int targetPart() {
    return 4;
  }

  @Override
  public List<String> problems(
      TenantId tenantId,
      SeedBundle bundle,
      Map<String, JsonNode> files,
      Map<String, UUID> handles) {
    List<GrantView> grants = runner.grants(tenantId);
    List<String> problems = new ArrayList<>();
    for (ComponentView component : serviceMap.components(tenantId)) {
      String target = component.targetKey();
      if (target != null
          && grants.stream().noneMatch(g -> g.active() && g.targetKey().equals(target))) {
        problems.add(
            "components '"
                + component.componentKey()
                + "': target '"
                + target
                + "' does not exist");
      }
    }
    return problems;
  }
}
