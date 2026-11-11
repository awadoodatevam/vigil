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
import vigil.recovery.api.RecoveryCheckView;
import vigil.recovery.api.RecoveryCondition;
import vigil.recovery.api.RecoveryService;
import vigil.shared.TenantId;

/** DM-R-60: the alert types of the recovery conditions of part 13 exist in part 6. */
@Component
class RecoveryAlertTypeCheck implements SeedKeyCheck {

  private final RecoveryService recovery;
  private final IntakeService intake;

  RecoveryAlertTypeCheck(RecoveryService recovery, IntakeService intake) {
    this.recovery = recovery;
    this.intake = intake;
  }

  @Override
  public int referringPart() {
    return 13;
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
    for (RecoveryCheckView definition : recovery.definitions(tenantId)) {
      for (RecoveryCondition condition : definition.conditions()) {
        for (String type : condition.alertTypes()) {
          if (!types.contains(type)) {
            problems.add(
                "recovery check '"
                    + definition.recoveryCheckKey()
                    + "': alert type '"
                    + type
                    + "' does not exist");
          }
        }
      }
    }
    return problems;
  }
}
