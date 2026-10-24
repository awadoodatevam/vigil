package vigil.engine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.UnaryOperator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/** Workflow contents for engine tests (P-17 spec Section 7). */
public final class TestContents {

  static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Path WORKFLOW = Path.of("../docs/31-demo-data/06-workflow.yaml");

  /** The parameters of each step type in {@link #linear} (complete list). */
  private static final Map<String, String> PARAMETERS =
      Map.of(
          "approval", "{\"eligible_teams\": [\"db-ops\"], \"expiry\": 900}",
          "recovery_check", "{\"max_wait\": 300}",
          "escalation", "{\"mode\": \"escalate_now\"}",
          "notification", "{\"recipients\": [\"owner_team\"]}",
          "ticket", "{\"operation\": \"create\"}",
          "wait", "{\"mode\": \"duration\", \"duration\": 60}",
          "diagnostic", "{\"diagnostics\": [\"disk_usage\"], \"target\": \"hypothesis_component\"}",
          "ai_task", "{\"task\": \"SL-T-1\"}",
          "action", "{}");

  /** The named outputs of each step type in {@link #linear}. */
  private static final Map<String, String[]> OUTPUTS =
      Map.of(
          "approval", new String[] {"approved", "rejected", "expired"},
          "recovery_check", new String[] {"recovered", "not_recovered"},
          "escalation", new String[] {"next"},
          "notification", new String[] {"sent", "failed"},
          "ticket", new String[] {"done", "failed"},
          "wait", new String[] {"event_received", "timed_out"},
          "diagnostic", new String[] {"done", "failed"},
          "ai_task", new String[] {"done", "unavailable"},
          "action", new String[] {"succeeded", "failed"});

  private TestContents() {}

  /**
   * A Trigger {@code s-trigger} and one step {@code s-{n}} per type key, each output of step n
   * connected to step n + 1; the last step's outputs unconnected.
   */
  public static String linear(String... typeKeys) {
    ObjectNode content = trigger();
    ArrayNode steps = (ArrayNode) content.get("steps");
    ArrayNode connections = (ArrayNode) content.get("connections");
    for (int n = 1; n <= typeKeys.length; n++) {
      steps.add(step("s-" + n, typeKeys[n - 1], "Step " + n, PARAMETERS.get(typeKeys[n - 1])));
      if (n == 1) {
        connections.add(connection("s-trigger", "next", "s-1"));
      } else {
        for (String output : OUTPUTS.get(typeKeys[n - 2])) {
          connections.add(connection("s-" + (n - 1), output, "s-" + n));
        }
      }
    }
    return JSON.writeValueAsString(content);
  }

  /** A Trigger and one Wait step {@code s-1} waiting for the acknowledgement. */
  public static String waitUntilEvent(long timeoutS) {
    ObjectNode content = trigger();
    ((ArrayNode) content.get("steps"))
        .add(
            step(
                "s-1",
                "wait",
                "Step 1",
                "{\"mode\": \"until_event\", \"event\": \"incident_acknowledged\", \"timeout\": "
                    + timeoutS
                    + "}"));
    ((ArrayNode) content.get("connections")).add(connection("s-trigger", "next", "s-1"));
    return JSON.writeValueAsString(content);
  }

  /**
   * Section {@code workflow.version_1} of {@code 06-workflow.yaml}: steps and connections in their
   * order; a parameter ending in {@code _s} renamed without the suffix (DD-R-07 (2)).
   */
  public static String version1() {
    JsonNode file;
    try {
      file =
          YAMLMapper.builder().build().readTree(Files.readString(WORKFLOW, StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    JsonNode version = file.get("workflow").get("version_1");
    ObjectNode content = JSON.createObjectNode();
    ArrayNode steps = content.putArray("steps");
    for (JsonNode step : version.get("steps")) {
      ObjectNode parameters = JSON.createObjectNode();
      for (Map.Entry<String, JsonNode> parameter : step.get("parameters").properties()) {
        String name = parameter.getKey();
        parameters.set(
            name.endsWith("_s") ? name.substring(0, name.length() - 2) : name,
            parameter.getValue());
      }
      ObjectNode copy = steps.addObject();
      copy.set("step_id", step.get("step_id"));
      copy.set("type", step.get("type"));
      copy.set("label", step.get("label"));
      copy.set("position", step.get("position"));
      copy.set("parameters", parameters);
    }
    ArrayNode connections = content.putArray("connections");
    for (JsonNode connection : version.get("connections")) {
      connections.add(connection);
    }
    return JSON.writeValueAsString(content);
  }

  /** The content with a one-off change. */
  public static String with(String content, UnaryOperator<ObjectNode> change) {
    return JSON.writeValueAsString(change.apply((ObjectNode) JSON.readTree(content)));
  }

  private static ObjectNode trigger() {
    ObjectNode content = JSON.createObjectNode();
    content
        .putArray("steps")
        .add(
            step(
                "s-trigger",
                "trigger",
                "Trigger",
                "{\"event\": \"incident_created_or_reopened\"}"));
    content.putArray("connections");
    return content;
  }

  private static ObjectNode step(String stepId, String type, String label, String parameters) {
    ObjectNode step = JSON.createObjectNode();
    step.put("step_id", stepId);
    step.put("type", type);
    step.put("label", label);
    step.putObject("position").put("x", 0).put("y", 0);
    step.set("parameters", JSON.readTree(parameters));
    return step;
  }

  private static ObjectNode connection(String from, String output, String to) {
    ObjectNode connection = JSON.createObjectNode();
    connection.put("from_step_id", from);
    connection.put("from_output", output);
    connection.put("to_step_id", to);
    return connection;
  }
}
