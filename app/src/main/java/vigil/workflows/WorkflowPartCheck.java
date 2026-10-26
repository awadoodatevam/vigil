package vigil.workflows;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import vigil.shared.StepCall.StepType;
import vigil.workflows.api.StepParameter;
import vigil.workflows.api.StepParameterType;
import vigil.workflows.api.StepTypeDefinition;

/**
 * The checks of seed part 16 (DM-R-57; P-41 spec Section 6.5), in their order, and the stored
 * content of version 1 (DM-V-05) when no problem is found.
 */
final class WorkflowPartCheck {

  static final JsonMapper JSON = JsonMapper.builder().build();

  private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
  private static final String VERSION = "workflow.version_1";
  private static final String OTHERWISE = "otherwise";
  private static final String DURATION_SUFFIX = "_s";
  private static final List<String> SEVERITIES = List.of("P1", "P2", "P3", "P4");
  private static final List<String> CRITICALITIES = List.of("critical", "high", "medium", "low");
  private static final List<String> EQUALITY = List.of("equals", "not_equals", "in", "not_in");
  private static final List<String> LISTED = List.of("in", "not_in");

  /** The value types of DM-R-26 with their operators (complete list). */
  private enum ValueType {
    ORDERED_ENUM(
        List.of("equals", "not_equals", "in", "not_in", "at_least", "at_most"), FormCheck.VALUE),
    ENUM(EQUALITY, FormCheck.VALUE),
    KEY(EQUALITY, FormCheck.KEY),
    KEY_SET(List.of("contains", "not_contains"), FormCheck.KEY),
    ENUM_SET(List.of("contains", "not_contains"), FormCheck.VALUE),
    INTEGER(
        List.of(
            "equals",
            "not_equals",
            "greater_than",
            "greater_or_equal",
            "less_than",
            "less_or_equal"),
        FormCheck.INTEGER),
    BOOLEAN(List.of("is"), FormCheck.BOOLEAN);

    final List<String> operators;
    final FormCheck form;

    ValueType(List<String> operators, FormCheck form) {
      this.operators = operators;
      this.form = form;
    }
  }

  private enum FormCheck {
    VALUE,
    KEY,
    INTEGER,
    BOOLEAN
  }

  /** A Condition field of DM-R-25: its value type and allowed values (empty for any). */
  private record Field(ValueType type, List<String> values) {}

  private static final Map<String, Field> FIELDS =
      Map.ofEntries(
          Map.entry("severity", new Field(ValueType.ORDERED_ENUM, SEVERITIES)),
          Map.entry("owner_team", new Field(ValueType.KEY, List.of())),
          Map.entry("informed_teams", new Field(ValueType.KEY_SET, List.of())),
          Map.entry("hypothesis_component", new Field(ValueType.KEY, List.of())),
          Map.entry(
              "hypothesis_component_kind",
              new Field(
                  ValueType.ENUM, List.of("server", "application", "database", "queue", "check"))),
          Map.entry("hypothesis_criticality", new Field(ValueType.ORDERED_ENUM, CRITICALITIES)),
          Map.entry("affected_services", new Field(ValueType.KEY_SET, List.of())),
          Map.entry("affected_service_count", new Field(ValueType.INTEGER, List.of())),
          Map.entry(
              "highest_service_criticality", new Field(ValueType.ORDERED_ENUM, CRITICALITIES)),
          Map.entry("alert_count", new Field(ValueType.INTEGER, List.of())),
          Map.entry("alert_types", new Field(ValueType.KEY_SET, List.of())),
          Map.entry(
              "alert_sources", new Field(ValueType.ENUM_SET, List.of("zabbix", "alertmanager"))),
          Map.entry("acknowledged", new Field(ValueType.BOOLEAN, List.of())),
          Map.entry("escalation_level", new Field(ValueType.INTEGER, List.of())),
          Map.entry("ai_investigation_available", new Field(ValueType.BOOLEAN, List.of())),
          Map.entry("reopen_count", new Field(ValueType.INTEGER, List.of())));

  /** The result: the problems in rule order, or the workflow's name and stored content. */
  record Result(List<String> problems, String name, String contentJson) {}

  /** A step that connections can refer to: its type ({@code null} when not valid). */
  private record Target(StepTypeDefinition type, JsonNode parameters) {}

  private final Set<String> problems = new LinkedHashSet<>();

  private WorkflowPartCheck() {}

  /** Checks rules 2 to 9; rule 1 (an existing workflow) is the importer's. */
  static Result check(String workflowJson) {
    return new WorkflowPartCheck().run(workflowJson);
  }

  private Result run(String workflowJson) {
    JsonNode workflow = parse(workflowJson);
    if (workflow == null || !workflow.isObject()) {
      return new Result(List.of("workflow: missing"), null, null);
    }
    JsonNode name = present(workflow.get("name"));
    if (name == null || !name.isString() || !length(name.stringValue(), 100)) {
      problems.add("workflow.name: 1 to 100 characters");
    }
    JsonNode version = present(workflow.get("version_1"));
    if (version == null || !version.isObject()) {
      problems.add(VERSION + ": missing");
      return result(null, null);
    }
    JsonNode origin = present(version.get("origin"));
    if (origin == null || !origin.isString() || !origin.stringValue().equals("seed")) {
      problems.add(VERSION + ".origin: seed");
    }
    JsonNode steps = present(version.get("steps"));
    if (steps == null || !steps.isArray() || steps.isEmpty()) {
      problems.add(VERSION + ".steps: at least one step");
      return result(null, null);
    }
    Map<String, Target> targets = new LinkedHashMap<>();
    int triggers = 0;
    for (int i = 0; i < steps.size(); i++) {
      StepTypeDefinition type = step(steps.get(i), VERSION + ".steps[" + i + "]", targets);
      if (type != null && type.type() == StepType.TRIGGER) {
        triggers++;
      }
    }
    JsonNode connections = present(version.get("connections"));
    if (connections != null && !connections.isArray()) {
      problems.add(VERSION + ".connections: not a list");
    } else if (connections != null) {
      Set<List<String>> pairs = new HashSet<>();
      for (int j = 0; j < connections.size(); j++) {
        connection(connections.get(j), VERSION + ".connections[" + j + "]", targets, pairs);
      }
    }
    if (problems.isEmpty() && triggers != 1) {
      problems.add(VERSION + ": exactly one trigger step");
    }
    return result(name, problems.isEmpty() ? stored(steps, connections) : null);
  }

  private Result result(JsonNode name, String contentJson) {
    return problems.isEmpty()
        ? new Result(List.of(), name.stringValue(), contentJson)
        : new Result(List.copyOf(problems), null, null);
  }

  /** Rule 7 for one step; returns its type when the step's type is valid. */
  private StepTypeDefinition step(JsonNode step, String at, Map<String, Target> targets) {
    if (!step.isObject()) {
      problems.add(at + ": not a mapping");
      return null;
    }
    JsonNode stepId = step.get("step_id");
    boolean key = isKey(stepId);
    if (!key) {
      problems.add(at + ".step_id: not a key");
    } else if (targets.containsKey(stepId.stringValue())) {
      problems.add(at + ".step_id: used more than once");
    }
    JsonNode typeKey = step.get("type");
    StepTypeDefinition type =
        typeKey != null && typeKey.isString()
            ? StepCatalogue.ofKey(typeKey.stringValue()).orElse(null)
            : null;
    if (type == null) {
      problems.add(at + ".type: not a step type");
    }
    JsonNode label = present(step.get("label"));
    if (label == null || !label.isString() || !length(label.stringValue(), 60)) {
      problems.add(at + ".label: 1 to 60 characters");
    }
    JsonNode position = present(step.get("position"));
    if (position == null
        || !position.isObject()
        || !isInteger(position.get("x"))
        || !isInteger(position.get("y"))) {
      problems.add(at + ".position: x and y must be integers");
    }
    JsonNode parameters = present(step.get("parameters"));
    if (parameters != null && !parameters.isObject()) {
      problems.add(at + ".parameters: not a mapping");
    } else if (type != null) {
      parameters(type, parameters, at + ".parameters");
    }
    if (key) {
      targets.putIfAbsent(
          stepId.stringValue(),
          new Target(type, parameters != null && parameters.isObject() ? parameters : null));
    }
    return type;
  }

  /** The parameter checks (a) to (c) of one step; {@code parameters} absent is empty. */
  private void parameters(StepTypeDefinition type, JsonNode parameters, String at) {
    Map<String, JsonNode> written = written(parameters);
    for (String name : written.keySet()) {
      if (parameter(type, name) == null) {
        problems.add(at + "." + name + ": not a parameter of " + type.type().key());
      }
    }
    for (StepParameter parameter : type.parameters()) {
      boolean required =
          parameter.required()
              || (parameter.requiredWhen() != null && requiredWhen(type, parameter, written));
      if (required && !written.containsKey(writtenName(parameter))) {
        problems.add(at + "." + writtenName(parameter) + ": missing");
      }
    }
    written.forEach(
        (name, value) -> {
          StepParameter parameter = parameter(type, name);
          if (parameter != null) {
            value(parameter, value, at + "." + name);
          }
        });
  }

  private static boolean requiredWhen(
      StepTypeDefinition type, StepParameter parameter, Map<String, JsonNode> written) {
    StepParameter other = parameter(type, parameter.requiredWhen().parameter());
    JsonNode value = other == null ? null : written.get(writtenName(other));
    if (value == null) {
      return false;
    }
    String wanted = parameter.requiredWhen().value();
    if (other.type() == StepParameterType.ENUM_SET && value.isArray()) {
      for (JsonNode element : value) {
        if (element.isString() && element.stringValue().equals(wanted)) {
          return true;
        }
      }
      return false;
    }
    return value.isString() && value.stringValue().equals(wanted);
  }

  /** Check (c) for one written parameter. */
  private void value(StepParameter parameter, JsonNode value, String at) {
    switch (parameter.type()) {
      case ENUM -> {
        if (!isAllowed(value, parameter.allowedValues())) {
          problems.add(at + ": one of " + String.join(", ", parameter.allowedValues()));
        }
      }
      case ENUM_SET, KEY_LIST, KEY_SET -> list(parameter, value, at);
      case DURATION -> {
        if (!value.isIntegralNumber()) {
          problems.add(at + ": not a whole number of seconds");
        } else if (!value.canConvertToLong()
            || value.longValue() < parameter.min()
            || value.longValue() > parameter.max()) {
          problems.add(at + ": " + parameter.min() + " to " + parameter.max() + " seconds");
        }
      }
      case CONDITION_BRANCHES -> branches(parameter, value, at);
    }
  }

  private void list(StepParameter parameter, JsonNode value, String at) {
    if (!value.isArray()) {
      problems.add(at + ": not a list");
      return;
    }
    List<JsonNode> seen = new ArrayList<>();
    for (int k = 0; k < value.size(); k++) {
      JsonNode element = value.get(k);
      String elementAt = at + "[" + k + "]";
      if (parameter.type() == StepParameterType.ENUM_SET
          && !isAllowed(element, parameter.allowedValues())) {
        problems.add(elementAt + ": one of " + String.join(", ", parameter.allowedValues()));
      } else if (parameter.type() != StepParameterType.ENUM_SET && !isKey(element)) {
        problems.add(elementAt + ": not a key");
      } else if (seen.contains(element)) {
        problems.add(elementAt + ": used more than once");
      }
      seen.add(element);
    }
    if (value.size() < parameter.minItems()) {
      problems.add(at + ": at least " + parameter.minItems());
    }
  }

  private void branches(StepParameter parameter, JsonNode value, String at) {
    if (!value.isArray()) {
      problems.add(at + ": not a list");
      return;
    }
    if (value.size() < parameter.minItems()) {
      problems.add(at + ": at least " + parameter.minItems());
    }
    Set<String> labels = new HashSet<>();
    for (int k = 0; k < value.size(); k++) {
      JsonNode branch = value.get(k);
      String branchAt = at + "[" + k + "]";
      if (!branch.isObject()) {
        problems.add(branchAt + ": not a mapping");
        continue;
      }
      JsonNode label = branch.get("label");
      if (!isKey(label)) {
        problems.add(branchAt + ".label: not a key");
      } else if (label.stringValue().equals(OTHERWISE)) {
        problems.add(branchAt + ".label: otherwise is reserved");
      } else if (!labels.add(label.stringValue())) {
        problems.add(branchAt + ".label: used more than once");
      }
      JsonNode comparisons = present(branch.get("comparisons"));
      if (comparisons == null || !comparisons.isArray() || comparisons.isEmpty()) {
        problems.add(branchAt + ".comparisons: at least one comparison");
        continue;
      }
      for (int m = 0; m < comparisons.size(); m++) {
        comparison(comparisons.get(m), branchAt + ".comparisons[" + m + "]");
      }
    }
  }

  private void comparison(JsonNode comparison, String at) {
    if (!comparison.isObject()) {
      problems.add(at + ": not a mapping");
      return;
    }
    JsonNode fieldName = comparison.get("field");
    Field field =
        fieldName != null && fieldName.isString() ? FIELDS.get(fieldName.stringValue()) : null;
    if (field == null) {
      problems.add(at + ".field: not a condition field");
      return;
    }
    JsonNode operator = comparison.get("operator");
    if (operator == null
        || !operator.isString()
        || !field.type().operators.contains(operator.stringValue())) {
      problems.add(at + ".operator: not an operator of " + fieldName.stringValue());
      return;
    }
    JsonNode value = present(comparison.get("value"));
    boolean listed = LISTED.contains(operator.stringValue());
    if (listed && (value == null || (value.isArray() && value.isEmpty()))) {
      problems.add(at + ".value: at least one value");
      return;
    }
    if (!listed && (value == null || value.isArray())) {
      problems.add(at + ".value: one value");
      return;
    }
    for (JsonNode single : values(value)) {
      String problem = form(field, fieldName.stringValue(), single);
      if (problem != null) {
        problems.add(at + ".value: " + problem);
      }
    }
  }

  /** The problem of one comparison value of the wrong form, or {@code null}. */
  private static String form(Field field, String fieldName, JsonNode value) {
    return switch (field.type().form) {
      case VALUE -> isAllowed(value, field.values()) ? null : "not a value of " + fieldName;
      case KEY -> isKey(value) ? null : "not a key";
      case INTEGER -> value.isIntegralNumber() ? null : "not an integer";
      case BOOLEAN -> value.isBoolean() ? null : "true or false";
    };
  }

  /** Rule 8 for one connection. */
  private void connection(
      JsonNode connection, String at, Map<String, Target> targets, Set<List<String>> pairs) {
    if (!connection.isObject()) {
      problems.add(at + ": not a mapping");
      return;
    }
    JsonNode from = connection.get("from_step_id");
    Target source = from != null && from.isString() ? targets.get(from.stringValue()) : null;
    if (source == null) {
      problems.add(at + ".from_step_id: not a step");
    }
    JsonNode to = connection.get("to_step_id");
    if (to == null || !to.isString() || !targets.containsKey(to.stringValue())) {
      problems.add(at + ".to_step_id: not a step");
    }
    JsonNode output = connection.get("from_output");
    if (source != null
        && source.type() != null
        && (output == null
            || !output.isString()
            || !outputs(source).contains(output.stringValue()))) {
      problems.add(at + ".from_output: not an output of " + from.stringValue());
    }
    if (from != null
        && from.isString()
        && output != null
        && output.isString()
        && !pairs.add(List.of(from.stringValue(), output.stringValue()))) {
      problems.add(
          at + ": a second connection from " + from.stringValue() + " " + output.stringValue());
    }
  }

  /** The named outputs of a step of a valid type; a Condition's from its branches' labels. */
  private static List<String> outputs(Target target) {
    List<String> outputs = new ArrayList<>();
    target.type().outputs().forEach(output -> outputs.add(output.key()));
    if (target.type().type() == StepType.CONDITION && target.parameters() != null) {
      JsonNode branches = target.parameters().get("branches");
      if (branches != null && branches.isArray()) {
        for (JsonNode branch : branches) {
          JsonNode label = branch.isObject() ? branch.get("label") : null;
          if (label != null && label.isString()) {
            outputs.add(label.stringValue());
          }
        }
      }
    }
    return outputs;
  }

  /** The stored content (DM-V-05) of a version without problems. */
  private static String stored(JsonNode steps, JsonNode connections) {
    ObjectNode content = JSON.createObjectNode();
    ArrayNode storedSteps = content.putArray("steps");
    for (JsonNode step : steps) {
      StepTypeDefinition type = StepCatalogue.ofKey(step.get("type").stringValue()).orElseThrow();
      ObjectNode stored = storedSteps.addObject();
      stored.set("step_id", step.get("step_id"));
      stored.set("type", step.get("type"));
      stored.set("label", step.get("label"));
      ObjectNode position = stored.putObject("position");
      position.set("x", step.get("position").get("x"));
      position.set("y", step.get("position").get("y"));
      ObjectNode parameters = stored.putObject("parameters");
      written(present(step.get("parameters")))
          .forEach(
              (name, value) -> {
                StepParameter parameter = parameter(type, name);
                parameters.set(
                    parameter.name(),
                    parameter.type() == StepParameterType.CONDITION_BRANCHES
                        ? storedBranches(value)
                        : value);
              });
    }
    ArrayNode storedConnections = content.putArray("connections");
    if (connections != null) {
      for (JsonNode connection : connections) {
        ObjectNode stored = storedConnections.addObject();
        stored.set("from_step_id", connection.get("from_step_id"));
        stored.set("from_output", connection.get("from_output"));
        stored.set("to_step_id", connection.get("to_step_id"));
      }
    }
    return JSON.writeValueAsString(content);
  }

  private static ArrayNode storedBranches(JsonNode branches) {
    ArrayNode stored = JSON.createArrayNode();
    for (JsonNode branch : branches) {
      ObjectNode storedBranch = stored.addObject();
      storedBranch.set("label", branch.get("label"));
      ArrayNode comparisons = storedBranch.putArray("comparisons");
      for (JsonNode comparison : branch.get("comparisons")) {
        ObjectNode storedComparison = comparisons.addObject();
        storedComparison.set("field", comparison.get("field"));
        storedComparison.set("operator", comparison.get("operator"));
        JsonNode value = comparison.get("value");
        if (LISTED.contains(comparison.get("operator").stringValue())) {
          ArrayNode list = storedComparison.putArray("value");
          values(value).forEach(list::add);
        } else {
          storedComparison.set("value", value);
        }
      }
    }
    return stored;
  }

  /** The written parameters whose value is not {@code null}, in the order written. */
  private static Map<String, JsonNode> written(JsonNode parameters) {
    Map<String, JsonNode> written = new LinkedHashMap<>();
    if (parameters != null) {
      for (Map.Entry<String, JsonNode> property : parameters.properties()) {
        if (!property.getValue().isNull()) {
          written.put(property.getKey(), property.getValue());
        }
      }
    }
    return written;
  }

  /** The catalogue parameter written as {@code name}; a Duration only with {@code _s}. */
  private static StepParameter parameter(StepTypeDefinition type, String name) {
    for (StepParameter parameter : type.parameters()) {
      if (writtenName(parameter).equals(name)) {
        return parameter;
      }
    }
    return null;
  }

  private static String writtenName(StepParameter parameter) {
    return parameter.type() == StepParameterType.DURATION
        ? parameter.name() + DURATION_SUFFIX
        : parameter.name();
  }

  /** A list's elements, or a single value as a list of one. */
  private static List<JsonNode> values(JsonNode value) {
    List<JsonNode> values = new ArrayList<>();
    if (value.isArray()) {
      value.forEach(values::add);
    } else {
      values.add(value);
    }
    return values;
  }

  private static JsonNode parse(String json) {
    if (json == null) {
      return null;
    }
    try {
      return JSON.readTree(json);
    } catch (JacksonException e) {
      return null;
    }
  }

  /** The node, or {@code null} when it is absent or JSON {@code null}. */
  private static JsonNode present(JsonNode node) {
    return node == null || node.isNull() ? null : node;
  }

  private static boolean isKey(JsonNode node) {
    return node != null && node.isString() && KEY.matcher(node.stringValue()).matches();
  }

  private static boolean isInteger(JsonNode node) {
    return node != null && node.isIntegralNumber();
  }

  private static boolean isAllowed(JsonNode node, List<String> allowed) {
    return node.isString() && allowed.contains(node.stringValue());
  }

  private static boolean length(String text, int max) {
    int length = text.codePointCount(0, text.length());
    return length >= 1 && length <= max;
  }
}
