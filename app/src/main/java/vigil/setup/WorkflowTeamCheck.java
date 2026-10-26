package vigil.setup;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import vigil.identity.api.IdentityService;
import vigil.shared.Key;
import vigil.shared.TenantId;
import vigil.workflows.api.WorkflowService;

/**
 * DM-R-60 "team keys in workflow version 1": the teams of Approval and Notification steps and of
 * comparisons on {@code owner_team} and {@code informed_teams} exist in part 2.
 */
@Component
class WorkflowTeamCheck implements SeedKeyCheck {

  private static final Set<String> TEAMS =
      Set.of("eligible_teams", "named_teams", "owner_team", "informed_teams");

  private final WorkflowService workflows;
  private final IdentityService identity;

  WorkflowTeamCheck(WorkflowService workflows, IdentityService identity) {
    this.workflows = workflows;
    this.identity = identity;
  }

  @Override
  public int referringPart() {
    return 16;
  }

  @Override
  public int targetPart() {
    return 2;
  }

  @Override
  public List<String> problems(
      TenantId tenantId,
      SeedBundle bundle,
      Map<String, JsonNode> files,
      Map<String, UUID> handles) {
    List<String> problems = new ArrayList<>();
    for (WorkflowKeys.Use use : WorkflowKeys.of(workflows, tenantId)) {
      if (TEAMS.contains(use.name()) && identity.team(tenantId, new Key(use.key())).isEmpty()) {
        problems.add(
            "workflow step '" + use.stepId() + "': team '" + use.key() + "' does not exist");
      }
    }
    return problems;
  }
}
