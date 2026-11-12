package vigil.setup;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import vigil.actions.api.ActionsService;
import vigil.actions.api.RegisteredActionView;
import vigil.recovery.api.RecoveryCheckView;
import vigil.recovery.api.RecoveryService;
import vigil.shared.TenantId;

/** DM-R-60: the recovery check of each registered action of part 14 is active in part 13. */
@Component
class ActionRecoveryCheck implements SeedKeyCheck {

  private final ActionsService actions;
  private final RecoveryService recovery;

  ActionRecoveryCheck(ActionsService actions, RecoveryService recovery) {
    this.actions = actions;
    this.recovery = recovery;
  }

  @Override
  public int referringPart() {
    return 14;
  }

  @Override
  public int targetPart() {
    return 13;
  }

  @Override
  public List<String> problems(
      TenantId tenantId,
      SeedBundle bundle,
      Map<String, JsonNode> files,
      Map<String, UUID> handles) {
    List<String> problems = new ArrayList<>();
    for (RegisteredActionView action : actions.registeredActions(tenantId)) {
      String key = action.recoveryCheckKey();
      if (recovery.definition(tenantId, key).filter(RecoveryCheckView::active).isEmpty()) {
        problems.add(
            "registered action '"
                + action.actionKey()
                + "': recovery check '"
                + key
                + "' does not exist");
      }
    }
    return problems;
  }
}
