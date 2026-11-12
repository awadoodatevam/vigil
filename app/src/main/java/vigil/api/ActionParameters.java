package vigil.api;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vigil.runner.api.OperationView;
import vigil.runner.api.RunnerService;
import vigil.shared.TenantId;

/**
 * The parameters of an action in the order of its operation's parameter definitions (DM-E-12);
 * names the definitions do not list follow in code point order, and with no operation found all are
 * in code point order.
 */
final class ActionParameters {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private ActionParameters() {}

  static List<ParameterValue> ordered(
      RunnerService runner, TenantId tenant, String operationKey, JsonNode values) {
    if (values == null || !values.isObject()) {
      return List.of();
    }
    Map<String, JsonNode> remaining = new TreeMap<>();
    values.properties().forEach(entry -> remaining.put(entry.getKey(), entry.getValue()));
    List<ParameterValue> ordered = new ArrayList<>();
    for (String name : definitionNames(runner, tenant, operationKey)) {
      JsonNode value = remaining.remove(name);
      if (value != null) {
        ordered.add(new ParameterValue(name, value));
      }
    }
    remaining.forEach((name, value) -> ordered.add(new ParameterValue(name, value)));
    return List.copyOf(ordered);
  }

  private static Set<String> definitionNames(
      RunnerService runner, TenantId tenant, String operationKey) {
    Set<String> names = new LinkedHashSet<>();
    if (operationKey == null) {
      return names;
    }
    OperationView operation = runner.operation(tenant, operationKey).orElse(null);
    if (operation == null || operation.parametersJson() == null) {
      return names;
    }
    JsonNode definitions = JSON.readTree(operation.parametersJson());
    if (definitions.isArray()) {
      for (JsonNode definition : definitions) {
        JsonNode name = definition.get("name");
        if (name != null && name.isString()) {
          names.add(name.stringValue());
        }
      }
    }
    return names;
  }
}
