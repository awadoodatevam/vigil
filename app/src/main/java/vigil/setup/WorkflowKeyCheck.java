package vigil.setup;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import vigil.servicemap.api.ServiceMapService;
import vigil.shared.TenantId;
import vigil.workflows.api.WorkflowService;

/**
 * DM-R-60 "component and service keys in workflow version 1": the values of comparisons on {@code
 * hypothesis_component} and {@code affected_services} exist in part 5.
 */
@Component
class WorkflowKeyCheck implements SeedKeyCheck {

  private final WorkflowService workflows;
  private final ServiceMapService serviceMap;

  WorkflowKeyCheck(WorkflowService workflows, ServiceMapService serviceMap) {
    this.workflows = workflows;
    this.serviceMap = serviceMap;
  }

  @Override
  public int referringPart() {
    return 16;
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
    for (WorkflowKeys.Use use : WorkflowKeys.of(workflows, tenantId)) {
      String prefix = "workflow step '" + use.stepId() + "': ";
      if (use.name().equals("hypothesis_component")
          && serviceMap.component(tenantId, use.key()).isEmpty()) {
        problems.add(prefix + "component '" + use.key() + "' does not exist");
      } else if (use.name().equals("affected_services")
          && serviceMap.service(tenantId, use.key()).isEmpty()) {
        problems.add(prefix + "service '" + use.key() + "' does not exist");
      }
    }
    return problems;
  }
}
