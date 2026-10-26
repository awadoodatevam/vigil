package vigil.setup;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import vigil.diagnostics.api.DiagnosticDefinitionView;
import vigil.diagnostics.api.DiagnosticsService;
import vigil.runner.api.OperationView;
import vigil.runner.api.PermissionKind;
import vigil.runner.api.RunnerService;
import vigil.shared.TenantId;

/**
 * DM-R-60 "operation keys of diagnostic definitions": each definition of part 12 names an active
 * diagnostic operation of part 4 whose parameter check accepts its values.
 */
@Component
class DiagnosticOperationCheck implements SeedKeyCheck {

  private final DiagnosticsService diagnostics;
  private final RunnerService runner;

  DiagnosticOperationCheck(DiagnosticsService diagnostics, RunnerService runner) {
    this.diagnostics = diagnostics;
    this.runner = runner;
  }

  @Override
  public int referringPart() {
    return 12;
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
    for (DiagnosticDefinitionView definition : diagnostics.definitions(tenantId)) {
      String prefix = "diagnostic definition '" + definition.diagnosticKey() + "': ";
      String operationKey = definition.operationKey();
      Optional<OperationView> operation = runner.operation(tenantId, operationKey);
      if (operation.isEmpty() || !operation.get().active()) {
        problems.add(prefix + "operation '" + operationKey + "' does not exist");
      } else if (operation.get().permissionKind() == PermissionKind.ACTION) {
        problems.add(prefix + "operation '" + operationKey + "' is not a diagnostic operation");
      } else {
        runner
            .parameterProblems(tenantId, operationKey, definition.parameterValuesJson())
            .forEach(problem -> problems.add(prefix + problem));
      }
    }
    return problems;
  }
}
