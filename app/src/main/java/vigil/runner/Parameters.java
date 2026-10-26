package vigil.runner;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The parameter definitions of an operation and the checks of their values (DM-V-06; P-18 spec
 * Sections 6.5 rule 3 and 6.6).
 */
final class Parameters {

  static final JsonMapper JSON = JsonMapper.builder().build();
  static final Pattern KEY = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
  private static final Pattern ABSOLUTE_PATH = Pattern.compile("^(/[^/]+)+$");
  private static final List<String> FIELDS =
      List.of(
          "name",
          "value_type",
          "required",
          "min",
          "max",
          "allowed_values",
          "max_length",
          "allowed_prefixes");
  private static final List<String> VALUE_TYPES =
      List.of("integer", "decimal", "text", "path", "duration", "enum", "boolean");
  private static final List<String> CONSTRAINTS =
      List.of("min", "max", "allowed_values", "max_length", "allowed_prefixes");
  private static final Map<String, Set<String>> TAKEN_BY =
      Map.of(
          "min", Set.of("integer", "decimal", "duration"),
          "max", Set.of("integer", "decimal", "duration"),
          "allowed_values", Set.of("enum"),
          "max_length", Set.of("text"),
          "allowed_prefixes", Set.of("path"));
  private static final String NUL = "\u0000";

  private Parameters() {}

  /** The definition checks of one {@code parameters} list; {@code prefix} names the list. */
  static List<String> definitionProblems(String prefix, JsonNode parameters) {
    List<String> problems = new ArrayList<>();
    Set<String> names = new HashSet<>();
    for (int j = 0; j < parameters.size(); j++) {
      String at = prefix + "[" + j + "]";
      JsonNode definition = parameters.get(j);
      if (!definition.isObject()) {
        problems.add(at + ": not a mapping");
        continue;
      }
      for (String field : definition.propertyNames()) {
        if (!FIELDS.contains(field)) {
          problems.add(at + ": unknown field " + field);
        }
      }
      JsonNode name = definition.get("name");
      if (!isKey(name)) {
        problems.add(at + ".name: not a key");
      } else if (!names.add(name.stringValue())) {
        problems.add(at + ".name: used more than once");
      }
      JsonNode valueType = definition.get("value_type");
      boolean typeValid = valueType != null && VALUE_TYPES.contains(text(valueType));
      if (!typeValid) {
        problems.add(at + ".value_type: one of " + String.join(", ", VALUE_TYPES));
      }
      JsonNode required = definition.get("required");
      if (required == null || !required.isBoolean()) {
        problems.add(at + ".required: true or false");
      }
      if (typeValid) {
        constraintProblems(at, valueType.stringValue(), definition, problems);
      }
    }
    return problems;
  }

  private static void constraintProblems(
      String at, String type, JsonNode definition, List<String> problems) {
    for (String field : CONSTRAINTS) {
      if (present(definition, field) && !TAKEN_BY.get(field).contains(type)) {
        problems.add(at + "." + field + ": not allowed for " + type);
      }
    }
    switch (type) {
      case "integer", "duration", "decimal" -> boundProblems(at, type, definition, problems);
      case "enum" -> listProblems(at, definition, "allowed_values", "at least one value", problems);
      case "text" -> {
        JsonNode maxLength = definition.get("max_length");
        if (present(definition, "max_length")
            && !(maxLength.isIntegralNumber() && maxLength.bigIntegerValue().signum() > 0)) {
          problems.add(at + ".max_length: a whole number of at least 1");
        }
      }
      case "path" ->
          listProblems(at, definition, "allowed_prefixes", "at least one path", problems);
      default -> {
        // boolean takes no constraint.
      }
    }
  }

  private static void boundProblems(
      String at, String type, JsonNode definition, List<String> problems) {
    boolean bothValid = true;
    for (String field : List.of("min", "max")) {
      if (!present(definition, field)) {
        bothValid = false;
        continue;
      }
      JsonNode bound = definition.get(field);
      if (type.equals("decimal")) {
        if (!bound.isNumber()) {
          problems.add(at + "." + field + ": not a number");
          bothValid = false;
        }
      } else if (!bound.isIntegralNumber()) {
        problems.add(at + "." + field + ": not a whole number");
        bothValid = false;
      } else if (type.equals("duration") && bound.bigIntegerValue().signum() < 0) {
        problems.add(at + "." + field + ": at least 0");
        bothValid = false;
      }
    }
    if (bothValid
        && definition.get("min").decimalValue().compareTo(definition.get("max").decimalValue())
            > 0) {
      problems.add(at + ".max: less than min");
    }
  }

  private static void listProblems(
      String at, JsonNode definition, String field, String empty, List<String> problems) {
    if (!present(definition, field)) {
      problems.add(at + "." + field + ": missing");
      return;
    }
    JsonNode list = definition.get(field);
    if (!list.isArray() || list.isEmpty()) {
      problems.add(at + "." + field + ": " + empty);
      return;
    }
    Set<String> seen = new HashSet<>();
    for (int k = 0; k < list.size(); k++) {
      JsonNode element = list.get(k);
      String elementAt = at + "." + field + "[" + k + "]";
      if (field.equals("allowed_prefixes")) {
        if (!element.isString() || !isAbsolutePath(element.stringValue())) {
          problems.add(elementAt + ": not an absolute path");
        }
      } else if (!element.isString()) {
        problems.add(elementAt + ": not a text");
      } else if (!seen.add(element.stringValue())) {
        problems.add(elementAt + ": used more than once");
      }
    }
  }

  private static boolean isAbsolutePath(String path) {
    return ABSOLUTE_PATH.matcher(path).matches() && !List.of(path.split("/")).contains("..");
  }

  /**
   * The problems of the parameters of a run against the operation's definitions (DM-V-06), in the
   * order of P-18 spec Section 6.6.
   */
  static List<String> valueProblems(
      String operationKey, JsonNode definitions, String parametersJson) {
    JsonNode values = readOrNull(parametersJson);
    if (values == null || !values.isObject()) {
      return List.of("parameters: not an object");
    }
    List<String> problems = new ArrayList<>();
    Set<String> defined = new HashSet<>();
    definitions.forEach(definition -> defined.add(definition.get("name").stringValue()));
    for (String name : values.propertyNames()) {
      if (!defined.contains(name)) {
        problems.add("parameter " + name + ": not a parameter of " + operationKey);
      }
    }
    for (JsonNode definition : definitions) {
      String name = definition.get("name").stringValue();
      JsonNode value = values.get(name);
      if (value == null || value.isNull()) {
        if (definition.get("required").booleanValue()) {
          problems.add("parameter " + name + ": missing");
        }
        continue;
      }
      String problem = valueProblem(definition, value);
      if (problem != null) {
        problems.add("parameter " + name + ": " + problem);
      }
    }
    return Collections.unmodifiableList(problems);
  }

  /** The problem of one present value, or {@code null}. */
  private static String valueProblem(JsonNode definition, JsonNode value) {
    String type = definition.get("value_type").stringValue();
    return switch (type) {
      case "integer", "duration" ->
          value.isIntegralNumber() ? boundProblem(definition, type, value) : "not a whole number";
      case "decimal" -> value.isNumber() ? boundProblem(definition, type, value) : "not a number";
      case "text", "path", "enum" -> textProblem(definition, type, value);
      default -> value.isBoolean() ? null : "true or false";
    };
  }

  private static String boundProblem(JsonNode definition, String type, JsonNode value) {
    BigDecimal min = present(definition, "min") ? definition.get("min").decimalValue() : null;
    BigDecimal max = present(definition, "max") ? definition.get("max").decimalValue() : null;
    if (type.equals("duration") && min == null) {
      min = BigDecimal.ZERO;
    }
    BigDecimal number = value.decimalValue();
    boolean outside =
        (min != null && number.compareTo(min) < 0) || (max != null && number.compareTo(max) > 0);
    if (!outside) {
      return null;
    }
    if (min != null && max != null) {
      return min.toPlainString() + " to " + max.toPlainString();
    }
    return min != null ? "at least " + min.toPlainString() : "at most " + max.toPlainString();
  }

  private static String textProblem(JsonNode definition, String type, JsonNode value) {
    if (!value.isString()) {
      return "not a text";
    }
    String text = value.stringValue();
    if (text.contains(NUL)) {
      return "holds the character U+0000";
    }
    switch (type) {
      case "text" -> {
        if (present(definition, "max_length")
            && definition.get("max_length").bigIntegerValue().longValue()
                < text.codePointCount(0, text.length())) {
          return "at most " + definition.get("max_length").bigIntegerValue() + " characters";
        }
        return null;
      }
      case "path" -> {
        List<String> prefixes = texts(definition.get("allowed_prefixes"));
        boolean under =
            prefixes.stream()
                .anyMatch(prefix -> text.equals(prefix) || text.startsWith(prefix + "/"));
        return text.startsWith("/") && !text.contains("..") && under
            ? null
            : "not under " + String.join(", ", prefixes);
      }
      default -> {
        List<String> allowed = texts(definition.get("allowed_values"));
        return allowed.contains(text) ? null : "one of " + String.join(", ", allowed);
      }
    }
  }

  /**
   * The text of each defined parameter's value for the command (AR-K-12): a string as it is, an
   * integer its digits, a decimal its plain form, a boolean {@code true} or {@code false}, and the
   * empty text for an optional parameter without a value.
   */
  static Map<String, String> texts(JsonNode definitions, JsonNode values) {
    Map<String, String> texts = new LinkedHashMap<>();
    for (JsonNode definition : definitions) {
      String name = definition.get("name").stringValue();
      JsonNode value = values == null ? null : values.get(name);
      String text;
      if (value == null || value.isNull()) {
        text = "";
      } else if (value.isString()) {
        text = value.stringValue();
      } else if (value.isIntegralNumber()) {
        text = value.bigIntegerValue().toString();
      } else if (value.isNumber()) {
        text = value.decimalValue().toPlainString();
      } else {
        text = value.asString();
      }
      texts.put(name, text);
    }
    return Collections.unmodifiableMap(texts);
  }

  /** The JSON tree of {@code json}; {@code null} when it is not JSON. */
  static JsonNode readOrNull(String json) {
    try {
      return json == null ? null : JSON.readTree(json);
    } catch (JacksonException e) {
      return null;
    }
  }

  static boolean isKey(JsonNode node) {
    return node != null && node.isString() && KEY.matcher(node.stringValue()).matches();
  }

  private static boolean present(JsonNode definition, String field) {
    JsonNode node = definition.get(field);
    return node != null && !node.isNull();
  }

  private static String text(JsonNode node) {
    return node.isString() ? node.stringValue() : null;
  }

  private static List<String> texts(JsonNode list) {
    List<String> texts = new ArrayList<>();
    list.forEach(element -> texts.add(element.stringValue()));
    return texts;
  }
}
