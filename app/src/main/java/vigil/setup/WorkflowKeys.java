package vigil.setup;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vigil.shared.TenantId;
import vigil.workflows.api.WorkflowService;

/**
 * The keys that the tenant's active workflow version uses (DM-R-60): the parameters {@code
 * eligible_teams}, {@code named_teams} and {@code diagnostics} (Diagnostic steps), and the values
 * of Condition comparisons by field, in step order, then value order.
 */
final class WorkflowKeys {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** One key: its step, the parameter or Condition field that holds it, and the key. */
  record Use(String stepId, String name, String key) {}

  private WorkflowKeys() {}

  static List<Use> of(WorkflowService workflows, TenantId tenantId) {
    List<Use> uses = new ArrayList<>();
    Optional<JsonNode> content =
        workflows.activeVersion(tenantId).map(version -> JSON.readTree(version.contentJson()));
    if (content.isEmpty()) {
      return uses;
    }
    for (JsonNode step : content.get().path("steps")) {
      String stepId = step.path("step_id").asString();
      JsonNode parameters = step.path("parameters");
      for (String name : List.of("eligible_teams", "named_teams", "diagnostics")) {
        for (JsonNode key : parameters.path(name)) {
          uses.add(new Use(stepId, name, key.asString()));
        }
      }
      for (JsonNode branch : parameters.path("branches")) {
        for (JsonNode comparison : branch.path("comparisons")) {
          String field = comparison.path("field").asString();
          JsonNode value = comparison.path("value");
          if (value.isArray()) {
            value.forEach(key -> uses.add(new Use(stepId, field, key.asString())));
          } else {
            uses.add(new Use(stepId, field, value.asString()));
          }
        }
      }
    }
    return uses;
  }
}
