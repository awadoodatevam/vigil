package vigil.setup;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import vigil.diagnostics.api.DiagnosticDefinitionView;
import vigil.diagnostics.api.DiagnosticsService;
import vigil.shared.TenantId;
import vigil.workflows.api.WorkflowService;

/**
 * DM-R-60 "diagnostic keys in workflow version 1": the keys of the parameter {@code diagnostics} of
 * Diagnostic steps exist in part 12 as active definitions.
 */
@Component
class WorkflowDiagnosticCheck implements SeedKeyCheck {

  private final WorkflowService workflows;
  private final DiagnosticsService diagnostics;

  WorkflowDiagnosticCheck(WorkflowService workflows, DiagnosticsService diagnostics) {
    this.workflows = workflows;
    this.diagnostics = diagnostics;
  }

  @Override
  public int referringPart() {
    return 16;
  }

  @Override
  public int targetPart() {
    return 12;
  }

  @Override
  public List<String> problems(
      TenantId tenantId,
      SeedBundle bundle,
      Map<String, JsonNode> files,
      Map<String, UUID> handles) {
    List<String> problems = new ArrayList<>();
    for (WorkflowKeys.Use use : WorkflowKeys.of(workflows, tenantId)) {
      if (use.name().equals("diagnostics")
          && diagnostics
              .definition(tenantId, use.key())
              .filter(DiagnosticDefinitionView::active)
              .isEmpty()) {
        problems.add(
            "workflow step '" + use.stepId() + "': diagnostic '" + use.key() + "' does not exist");
      }
    }
    return problems;
  }
}
