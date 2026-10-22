package vigil.setup;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import vigil.routing.api.RoutingCondition;
import vigil.routing.api.RoutingField;
import vigil.routing.api.RoutingRule;
import vigil.routing.api.RoutingService;
import vigil.servicemap.api.ServiceMapService;
import vigil.shared.TenantId;

/**
 * DM-R-60 "component and service keys in routing rules": the comparison values of part 11 exist in
 * part 5.
 */
@Component
class RoutingKeyCheck implements SeedKeyCheck {

  private final RoutingService routing;
  private final ServiceMapService serviceMap;

  RoutingKeyCheck(RoutingService routing, ServiceMapService serviceMap) {
    this.routing = routing;
    this.serviceMap = serviceMap;
  }

  @Override
  public int referringPart() {
    return 11;
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
    for (RoutingRule rule :
        routing.ruleSet(tenantId).map(ruleSet -> ruleSet.rules()).orElse(List.of())) {
      String prefix = "routing rule '" + rule.ruleKey() + "': ";
      for (RoutingCondition condition : rule.conditions()) {
        for (String value : condition.values()) {
          if (condition.field() == RoutingField.HYPOTHESIS_COMPONENT
              && serviceMap.component(tenantId, value).isEmpty()) {
            problems.add(prefix + "component '" + value + "' does not exist");
          } else if (condition.field() == RoutingField.AFFECTED_SERVICES
              && serviceMap.service(tenantId, value).isEmpty()) {
            problems.add(prefix + "service '" + value + "' does not exist");
          }
        }
      }
    }
    return problems;
  }
}
