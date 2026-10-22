package vigil.setup;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import vigil.shared.VigilError;

/**
 * The shape checks of the part adapters (P-11 spec Section 6.5): problems are collected in section
 * order, then entry order, then field order.
 */
final class SeedShapes {

  private final Set<String> problems = new LinkedHashSet<>();

  /** One list entry, its single values by field name ({@code null} for null or absent). */
  record Row(Map<String, String> values, Map<String, JsonNode> trees) {

    String get(String field) {
      return values.get(field);
    }

    /** The tree of a field that takes any form; {@code null} for YAML null or absent. */
    JsonNode tree(String field) {
      JsonNode node = trees.get(field);
      return node == null || node.isNull() ? null : node;
    }
  }

  /** The section {@code section} of {@code file}; {@code null} with a problem when absent. */
  JsonNode section(JsonNode file, String section) {
    JsonNode node = file == null ? null : file.get(section);
    if (node == null) {
      problems.add(section + ": missing");
    }
    return node;
  }

  /**
   * The entries of a list section with the given fields; {@code anyForm} fields take any form.
   * Returns the rows of the entries that are mappings.
   */
  List<Row> list(JsonNode file, String section, List<String> fields, Set<String> anyForm) {
    JsonNode node = section(file, section);
    List<Row> rows = new ArrayList<>();
    if (node == null) {
      return rows;
    }
    if (!node.isArray()) {
      problems.add(section + ": not a list");
      return rows;
    }
    for (int i = 0; i < node.size(); i++) {
      JsonNode entry = node.get(i);
      String at = section + "[" + i + "]";
      if (!entry.isObject()) {
        problems.add(at + ": not a mapping");
        continue;
      }
      rows.add(row(entry, at, at + ": unknown field ", at + ".", fields, anyForm));
    }
    return rows;
  }

  /** A mapping section with the given fields, all single values; {@code null} when not one. */
  Row mapping(JsonNode file, String section, List<String> fields) {
    JsonNode node = section(file, section);
    if (node == null) {
      return null;
    }
    if (!node.isObject()) {
      problems.add(section + ": not a mapping");
      return null;
    }
    return row(node, section, section + ": unknown field ", section + ".", fields, Set.of());
  }

  /**
   * A mapping section from key to a list of single values ({@code permission_grants}); {@code null}
   * when the section is not a mapping. Keys whose value is not a list are left out.
   */
  Map<String, List<String>> lists(JsonNode file, String section) {
    JsonNode node = section(file, section);
    if (node == null) {
      return null;
    }
    if (!node.isObject()) {
      problems.add(section + ": not a mapping");
      return null;
    }
    Map<String, List<String>> lists = new LinkedHashMap<>();
    for (Map.Entry<String, JsonNode> property : node.properties()) {
      String at = section + "." + property.getKey();
      JsonNode value = property.getValue();
      if (!value.isArray()) {
        problems.add(at + ": not a list");
        continue;
      }
      List<String> items = new ArrayList<>();
      for (int i = 0; i < value.size(); i++) {
        if (isSingle(value.get(i))) {
          items.add(text(value.get(i)));
        } else {
          problems.add(at + "[" + i + "]: not a single value");
        }
      }
      lists.put(property.getKey(), items);
    }
    return lists;
  }

  /** Adds a problem that the adapter finds after the shape checks. */
  void add(String problem) {
    problems.add(problem);
  }

  /** Throws {@code INVALID} "Part {name} refused." when any problem was found. */
  void refuseIfAny(String partName) {
    if (!problems.isEmpty()) {
      throw new VigilError(
          VigilError.Code.INVALID,
          "Part " + partName + " refused.",
          Map.of("problems", List.copyOf(problems)));
    }
  }

  private Row row(
      JsonNode node,
      String at,
      String unknownPrefix,
      String fieldPrefix,
      List<String> fields,
      Set<String> anyForm) {
    Map<String, String> values = new LinkedHashMap<>();
    Map<String, JsonNode> trees = new LinkedHashMap<>();
    for (Map.Entry<String, JsonNode> property : node.properties()) {
      String key = property.getKey();
      JsonNode value = property.getValue();
      if (!fields.contains(key)) {
        problems.add(unknownPrefix + key);
      } else if (anyForm.contains(key)) {
        trees.put(key, value);
      } else if (!isSingle(value)) {
        problems.add(fieldPrefix + key + ": not a single value");
      } else {
        values.put(key, text(value));
      }
    }
    return new Row(values, trees);
  }

  private static boolean isSingle(JsonNode node) {
    return node.isValueNode();
  }

  /** The text of a single value: a string as written, a number or boolean as Jackson writes it. */
  static String text(JsonNode node) {
    if (node.isNull()) {
      return null;
    }
    return node.isString() ? node.stringValue() : node.asString();
  }
}
