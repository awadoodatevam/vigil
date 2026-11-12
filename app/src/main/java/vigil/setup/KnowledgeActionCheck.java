package vigil.setup;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import vigil.actions.api.ActionsService;
import vigil.knowledge.api.KnowledgeService;
import vigil.knowledge.api.PastIncidentView;
import vigil.shared.TenantId;

/** DM-R-60: the fix action of each past incident of part 8 is a registered action of part 14. */
@Component
class KnowledgeActionCheck implements SeedKeyCheck {

  private final KnowledgeService knowledge;
  private final ActionsService actions;

  KnowledgeActionCheck(KnowledgeService knowledge, ActionsService actions) {
    this.knowledge = knowledge;
    this.actions = actions;
  }

  @Override
  public int referringPart() {
    return 8;
  }

  @Override
  public int targetPart() {
    return 14;
  }

  @Override
  public List<String> problems(
      TenantId tenantId,
      SeedBundle bundle,
      Map<String, JsonNode> files,
      Map<String, UUID> handles) {
    List<String> problems = new ArrayList<>();
    for (PastIncidentView past : knowledge.pastIncidents(tenantId)) {
      String action = past.fixActionKey();
      if (action != null && actions.registeredAction(tenantId, action).isEmpty()) {
        problems.add(
            "past incident '"
                + past.pastIncidentKey()
                + "': action '"
                + action
                + "' does not exist");
      }
    }
    return problems;
  }
}
