package vigil.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import vigil.shared.StepCall.StepType;

/**
 * A run's content (DM-V-05) as the engine reads it, with the engine's own table of each step type's
 * named outputs (DM Section 9).
 */
final class RunContent {

  static final JsonMapper JSON = JsonMapper.builder().build();

  /** The named outputs of each step type except {@code condition} (DM Section 9). */
  private static final Map<StepType, List<String>> OUTPUTS =
      Map.ofEntries(
          Map.entry(StepType.TRIGGER, List.of("next")),
          Map.entry(StepType.DIAGNOSTIC, List.of("done", "failed")),
          Map.entry(StepType.AI_TASK, List.of("done", "unavailable")),
          Map.entry(StepType.APPROVAL, List.of("approved", "rejected", "expired")),
          Map.entry(StepType.ACTION, List.of("succeeded", "failed")),
          Map.entry(StepType.RECOVERY_CHECK, List.of("recovered", "not_recovered")),
          Map.entry(StepType.ESCALATION, List.of("next")),
          Map.entry(StepType.NOTIFICATION, List.of("sent", "failed")),
          Map.entry(StepType.TICKET, List.of("done", "failed")),
          Map.entry(StepType.WAIT, List.of("event_received", "timed_out")));

  private static final String OTHERWISE = "otherwise";

  /** One step: its id, type ({@code null} when not a step type), label and parameters. */
  record Step(String stepId, StepType type, String label, JsonNode parameters) {}

  private final Map<String, Step> steps;
  private final Map<String, String> connections;

  private RunContent(Map<String, Step> steps, Map<String, String> connections) {
    this.steps = steps;
    this.connections = connections;
  }

  /**
   * Reads content; {@code IllegalArgumentException} when it is not a JSON object whose {@code
   * steps} is a list.
   */
  static RunContent parse(String json) {
    JsonNode root;
    try {
      root = JSON.readTree(json);
    } catch (JacksonException e) {
      throw new IllegalArgumentException("the content is not JSON");
    }
    if (root == null || !root.isObject() || !root.path("steps").isArray()) {
      throw new IllegalArgumentException("the content has no list of steps");
    }
    Map<String, Step> steps = new LinkedHashMap<>();
    for (JsonNode step : root.get("steps")) {
      String stepId = text(step.get("step_id"));
      JsonNode parameters = step.get("parameters");
      steps.putIfAbsent(
          stepId,
          new Step(
              stepId,
              type(text(step.get("type"))),
              text(step.get("label")),
              parameters != null && parameters.isObject()
                  ? parameters
                  : JsonNodeFactory.instance.objectNode()));
    }
    Map<String, String> connections = new HashMap<>();
    for (JsonNode connection : root.path("connections")) {
      connections.putIfAbsent(
          key(text(connection.get("from_step_id")), text(connection.get("from_output"))),
          text(connection.get("to_step_id")));
    }
    return new RunContent(steps, connections);
  }

  /** The step type with this key, or {@code null}. */
  static StepType type(String key) {
    for (StepType type : StepType.values()) {
      if (type.key().equals(key)) {
        return type;
      }
    }
    return null;
  }

  private static String text(JsonNode node) {
    return node != null && node.isString() ? node.stringValue() : null;
  }

  private static String key(String stepId, String output) {
    return stepId + "\u0000" + output;
  }

  /** The steps of type {@code trigger}. */
  List<Step> triggers() {
    return steps.values().stream().filter(step -> step.type() == StepType.TRIGGER).toList();
  }

  Optional<Step> step(String stepId) {
    return Optional.ofNullable(steps.get(stepId));
  }

  /** The target of the connection from the step's output, or empty when it ends the run. */
  Optional<String> target(String stepId, String output) {
    return Optional.ofNullable(connections.get(key(stepId, output)));
  }

  /**
   * The named outputs of the step: from the table, or a Condition's branch labels and otherwise.
   */
  static List<String> outputs(Step step) {
    if (step.type() != StepType.CONDITION) {
      return OUTPUTS.getOrDefault(step.type(), List.of());
    }
    List<String> outputs = new ArrayList<>();
    for (JsonNode branch : step.parameters().path("branches")) {
      String label = text(branch.get("label"));
      if (label != null) {
        outputs.add(label);
      }
    }
    outputs.add(OTHERWISE);
    return List.copyOf(outputs);
  }
}
