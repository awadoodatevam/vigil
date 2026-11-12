package vigil.setup;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import vigil.recovery.api.ConditionKind;
import vigil.recovery.api.RecoveryCheckView;
import vigil.recovery.api.RecoveryCondition;
import vigil.recovery.api.RecoveryService;
import vigil.servicemap.api.ServiceMapService;
import vigil.shared.TenantId;

/** DM-R-60: the components of the recovery conditions of part 13 exist in part 5. */
@Component
class RecoveryServiceMapCheck implements SeedKeyCheck {

  private final RecoveryService recovery;
  private final ServiceMapService serviceMap;

  RecoveryServiceMapCheck(RecoveryService recovery, ServiceMapService serviceMap) {
    this.recovery = recovery;
    this.serviceMap = serviceMap;
  }

  @Override
  public int referringPart() {
    return 13;
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
    for (RecoveryCheckView definition : recovery.definitions(tenantId)) {
      for (RecoveryCondition condition : definition.conditions()) {
        if (condition.kind() == ConditionKind.COMPONENT_NO_FIRING_ALERT
            && serviceMap.component(tenantId, condition.componentKey()).isEmpty()) {
          problems.add(
              "recovery check '"
                  + definition.recoveryCheckKey()
                  + "': component '"
                  + condition.componentKey()
                  + "' does not exist");
        }
      }
    }
    return problems;
  }
}
