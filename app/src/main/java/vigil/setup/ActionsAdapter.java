package vigil.setup;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import vigil.actions.api.ActionsImport;
import vigil.actions.api.ActionsPart;
import vigil.actions.api.ActionsPart.ActionPart;
import vigil.actions.api.ActionsPart.PolicyPart;
import vigil.setup.SeedShapes.Row;
import vigil.shared.TenantId;

/**
 * Seed part 14, actions, imported through {@link ActionsImport} (DM-R-56 (14)): the sections {@code
 * registered_actions} and {@code action_policies} of {@code 04-operations-actions-recovery.yaml}.
 */
@Component
class ActionsAdapter implements SeedPartImporter {

  private static final String ACTIONS = "registered_actions";
  private static final String POLICIES = "action_policies";

  private final ActionsImport actionsImport;

  ActionsAdapter(ActionsImport actionsImport) {
    this.actionsImport = actionsImport;
  }

  @Override
  public int part() {
    return 14;
  }

  @Override
  public PartResult importPart(
      TenantId tenantId,
      SeedBundle bundle,
      Map<String, JsonNode> files,
      Instant importStartedAt,
      Map<String, UUID> handles) {
    SeedShapes shapes = new SeedShapes();
    JsonNode file = files.get(SeedParts.OPERATIONS);
    List<Row> actions =
        shapes.list(
            file,
            ACTIONS,
            List.of(
                "action_key",
                "name",
                "operation_key",
                "parameter_values",
                "limits",
                "attempt_limit",
                "precondition",
                "reconciliation",
                "recovery_check_key"),
            Set.of("parameter_values", "limits", "precondition", "reconciliation"));
    List<Row> policies =
        shapes.list(file, POLICIES, List.of("action_key", "service_key", "outcome"), Set.of());
    shapes.refuseIfAny(SeedParts.part(part()).name());
    actionsImport.importPart(
        tenantId,
        new ActionsPart(
            actions.stream()
                .map(
                    a ->
                        new ActionPart(
                            a.get("action_key"),
                            a.get("name"),
                            a.get("operation_key"),
                            a.tree("parameter_values"),
                            a.tree("limits"),
                            a.get("attempt_limit"),
                            a.tree("precondition"),
                            a.tree("reconciliation"),
                            a.get("recovery_check_key")))
                .toList(),
            policies.stream()
                .map(
                    p ->
                        new PolicyPart(p.get("action_key"), p.get("service_key"), p.get("outcome")))
                .toList()),
        importStartedAt,
        handles);
    Map<String, Integer> counts = new LinkedHashMap<>();
    counts.put(ACTIONS, actions.size());
    counts.put(POLICIES, policies.size());
    return new PartResult(counts, Map.of());
  }
}
