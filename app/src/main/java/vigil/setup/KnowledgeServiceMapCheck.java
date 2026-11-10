package vigil.setup;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import vigil.knowledge.api.KnowledgeService;
import vigil.knowledge.api.PastIncidentView;
import vigil.servicemap.api.ServiceMapService;
import vigil.shared.TenantId;

/** DM-R-60: the components and services of the past incidents of part 8 exist in part 5. */
@Component
class KnowledgeServiceMapCheck implements SeedKeyCheck {

  private final KnowledgeService knowledge;
  private final ServiceMapService serviceMap;

  KnowledgeServiceMapCheck(KnowledgeService knowledge, ServiceMapService serviceMap) {
    this.knowledge = knowledge;
    this.serviceMap = serviceMap;
  }

  @Override
  public int referringPart() {
    return 8;
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
    for (PastIncidentView past : knowledge.pastIncidents(tenantId)) {
      String at = "past incident '" + past.pastIncidentKey() + "': ";
      String component = past.hypothesisComponentKey();
      if (serviceMap.component(tenantId, component).isEmpty()) {
        problems.add(at + "component '" + component + "' does not exist");
      }
      for (String service : past.affectedServiceKeys()) {
        if (serviceMap.service(tenantId, service).isEmpty()) {
          problems.add(at + "service '" + service + "' does not exist");
        }
      }
    }
    return problems;
  }
}
