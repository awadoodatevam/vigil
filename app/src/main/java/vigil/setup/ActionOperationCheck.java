package vigil.setup;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import vigil.actions.api.ActionsService;
import vigil.actions.api.RegisteredActionView;
import vigil.actions.api.StateCheck;
import vigil.runner.api.OperationView;
import vigil.runner.api.PermissionKind;
import vigil.runner.api.RunnerService;
import vigil.shared.TenantId;

/**
 * DM-R-60: each registered action of part 14 names an active action operation of part 4 whose
 * parameter check accepts its values, and each of its state checks an active diagnostic operation
 * whose parameter check accepts its parameters.
 */
@Component
class ActionOperationCheck implements SeedKeyCheck {

  private final ActionsService actions;
  private final RunnerService runner;

  ActionOperationCheck(ActionsService actions, RunnerService runner) {
    this.actions = actions;
    this.runner = runner;
  }

  @Override
  public int referringPart() {
    return 14;
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
    List<String> problems = new ArrayList<>();
    for (RegisteredActionView action : actions.registeredActions(tenantId)) {
      String prefix = "registered action '" + action.actionKey() + "': ";
      check(
          tenantId,
          prefix,
          "",
          action.operationKey(),
          action.parameterValuesJson(),
          PermissionKind.ACTION,
          problems);
      for (Map.Entry<String, StateCheck> state :
          List.of(
              Map.entry("precondition", action.precondition()),
              Map.entry("reconciliation", action.reconciliation()))) {
        check(
            tenantId,
            prefix,
            state.getKey() + " ",
            state.getValue().operationKey(),
            state.getValue().parametersJson(),
            PermissionKind.DIAGNOSTIC,
            problems);
      }
    }
    return problems;
  }

  private void check(
      TenantId tenantId,
      String prefix,
      String which,
      String operationKey,
      String parametersJson,
      PermissionKind kind,
      List<String> problems) {
    Optional<OperationView> operation = runner.operation(tenantId, operationKey);
    if (operation.isEmpty() || !operation.get().active()) {
      problems.add(prefix + which + "operation '" + operationKey + "' does not exist");
    } else if (operation.get().permissionKind() != kind) {
      problems.add(
          prefix
              + which
              + "operation '"
              + operationKey
              + "' is not "
              + (kind == PermissionKind.ACTION ? "an action" : "a diagnostic")
              + " operation");
    } else {
      runner
          .parameterProblems(tenantId, operationKey, parametersJson)
          .forEach(problem -> problems.add(prefix + problem));
    }
  }
}
