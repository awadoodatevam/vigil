package vigil.setup;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import vigil.intake.api.AlertTypeView;
import vigil.intake.api.IntakeService;
import vigil.knowledge.api.KnowledgeService;
import vigil.knowledge.api.PastIncidentView;
import vigil.knowledge.api.RunbookView;
import vigil.shared.TenantId;

/** DM-R-60: the alert types of the runbooks and past incidents of part 8 exist in part 6. */
@Component
class KnowledgeAlertTypeCheck implements SeedKeyCheck {

  private final KnowledgeService knowledge;
  private final IntakeService intake;

  KnowledgeAlertTypeCheck(KnowledgeService knowledge, IntakeService intake) {
    this.knowledge = knowledge;
    this.intake = intake;
  }

  @Override
  public int referringPart() {
    return 8;
  }

  @Override
  public int targetPart() {
    return 6;
  }

  @Override
  public List<String> problems(
      TenantId tenantId,
      SeedBundle bundle,
      Map<String, JsonNode> files,
      Map<String, UUID> handles) {
    Set<String> types =
        intake.alertTypes(tenantId).stream()
            .map(AlertTypeView::alertType)
            .collect(Collectors.toSet());
    List<String> problems = new ArrayList<>();
    for (RunbookView runbook : knowledge.runbooks(tenantId)) {
      for (String type : runbook.applicableAlertTypes()) {
        if (!types.contains(type)) {
          problems.add(
              "runbook '" + runbook.runbookKey() + "': alert type '" + type + "' does not exist");
        }
      }
    }
    for (PastIncidentView past : knowledge.pastIncidents(tenantId)) {
      for (String type : past.alertTypes()) {
        if (!types.contains(type)) {
          problems.add(
              "past incident '"
                  + past.pastIncidentKey()
                  + "': alert type '"
                  + type
                  + "' does not exist");
        }
      }
    }
    return problems;
  }
}
