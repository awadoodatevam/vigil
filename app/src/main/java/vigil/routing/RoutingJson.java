package vigil.routing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import vigil.incidents.api.Severity;
import vigil.routing.api.EscalationLevel;
import vigil.routing.api.OwnerMode;
import vigil.routing.api.RoutingCondition;
import vigil.routing.api.RoutingField;
import vigil.routing.api.RoutingOperator;
import vigil.routing.api.RoutingRule;
import vigil.routing.api.ValueType;

/**
 * The stored JSON of vigil-routing (P-15 spec Section 6.3): keys in snake case, key lists sorted in
 * code point order without repeats, empty members as JSON {@code null}.
 */
final class RoutingJson {

  static final JsonMapper JSON = JsonMapper.builder().build();
  static final Comparator<String> CODE_POINTS =
      Comparator.comparing(text -> text.codePoints().toArray(), Arrays::compare);

  private RoutingJson() {}

  /** The values sorted in code point order without repeats, unmodifiable. */
  static List<String> sorted(Collection<String> values) {
    return values.stream().distinct().sorted(CODE_POINTS).toList();
  }

  /**
   * The rule as it is stored: {@code IN} / {@code NOT_IN} values sorted without repeats, integer
   * values without leading zeros, informed team keys sorted. The rule's conditions are complete.
   */
  static RoutingRule stored(RoutingRule rule) {
    List<RoutingCondition> conditions =
        rule.conditions().stream().map(RoutingJson::stored).toList();
    return new RoutingRule(
        rule.ruleKey(),
        rule.name(),
        conditions,
        rule.severity(),
        rule.ownerMode(),
        rule.ownerTeamKey(),
        sorted(rule.informedTeamKeys()),
        rule.informAffectedServiceOwners());
  }

  private static RoutingCondition stored(RoutingCondition condition) {
    List<String> values = condition.values();
    if (condition.field().valueType() == ValueType.INTEGER) {
      values = values.stream().map(value -> Long.toString(Long.parseLong(value))).toList();
    }
    if (isList(condition.operator())) {
      values = sorted(values);
    }
    return new RoutingCondition(condition.field(), condition.operator(), List.copyOf(values));
  }

  static boolean isList(RoutingOperator operator) {
    return operator == RoutingOperator.IN || operator == RoutingOperator.NOT_IN;
  }

  static String write(JsonNode node) {
    return JSON.writeValueAsString(node);
  }

  static JsonNode read(String json) {
    return JSON.readTree(json);
  }

  /** The {@code rules} JSON of stored rules. */
  static ArrayNode rules(List<RoutingRule> rules) {
    ArrayNode array = JSON.createArrayNode();
    for (RoutingRule rule : rules) {
      ObjectNode node = array.addObject();
      node.put("rule_key", rule.ruleKey());
      node.put("name", rule.name());
      ArrayNode conditions = node.putArray("conditions");
      for (RoutingCondition condition : rule.conditions()) {
        ObjectNode item = conditions.addObject();
        item.put("field", condition.field().key());
        item.put("operator", condition.operator().key());
        if (isList(condition.operator())) {
          condition.values().forEach(item.putArray("value")::add);
        } else if (condition.field().valueType() == ValueType.INTEGER) {
          item.put("value", Long.parseLong(condition.values().getFirst()));
        } else {
          item.put("value", condition.values().getFirst());
        }
      }
      node.put("severity", rule.severity().key());
      node.put("owner_mode", rule.ownerMode().key());
      node.put("owner_team_key", rule.ownerTeamKey());
      rule.informedTeamKeys().forEach(node.putArray("informed_team_keys")::add);
      node.put("inform_affected_service_owners", rule.informAffectedServiceOwners());
    }
    return array;
  }

  static List<RoutingRule> rules(String json) {
    List<RoutingRule> rules = new ArrayList<>();
    for (JsonNode node : read(json)) {
      List<RoutingCondition> conditions = new ArrayList<>();
      for (JsonNode item : node.get("conditions")) {
        JsonNode value = item.get("value");
        conditions.add(
            new RoutingCondition(
                RoutingField.fromKey(item.get("field").stringValue()).orElseThrow(),
                RoutingOperator.fromKey(item.get("operator").stringValue()).orElseThrow(),
                value.isArray() ? strings(value) : List.of(value.asString())));
      }
      rules.add(
          new RoutingRule(
              node.get("rule_key").stringValue(),
              node.get("name").stringValue(),
              List.copyOf(conditions),
              Severity.fromKey(node.get("severity").stringValue()).orElseThrow(),
              OwnerMode.fromKey(node.get("owner_mode").stringValue()).orElseThrow(),
              node.get("owner_team_key").stringValue(),
              strings(node.get("informed_team_keys")),
              node.get("inform_affected_service_owners").booleanValue()));
    }
    return Collections.unmodifiableList(rules);
  }

  /** The {@code levels} JSON. */
  static String levels(List<EscalationLevel> levels) {
    ArrayNode array = JSON.createArrayNode();
    for (EscalationLevel level : levels) {
      ObjectNode node = array.addObject();
      node.put("level", level.level());
      level.teamKeys().forEach(node.putArray("team_keys")::add);
    }
    return write(array);
  }

  static List<EscalationLevel> levels(String json) {
    List<EscalationLevel> levels = new ArrayList<>();
    for (JsonNode node : read(json)) {
      levels.add(
          new EscalationLevel(node.get("level").longValue(), strings(node.get("team_keys"))));
    }
    return Collections.unmodifiableList(levels);
  }

  /** A JSON array of the keys, in the order given. */
  static String keys(List<String> keys) {
    ArrayNode array = JSON.createArrayNode();
    keys.forEach(array::add);
    return write(array);
  }

  static List<String> strings(String json) {
    return strings(read(json));
  }

  private static List<String> strings(JsonNode array) {
    List<String> values = new ArrayList<>();
    array.forEach(value -> values.add(value.stringValue()));
    return List.copyOf(values);
  }
}
