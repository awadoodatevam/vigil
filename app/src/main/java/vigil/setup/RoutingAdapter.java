package vigil.setup;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import vigil.routing.api.ConditionPart;
import vigil.routing.api.LevelPart;
import vigil.routing.api.RoutingImport;
import vigil.routing.api.RoutingPart;
import vigil.routing.api.RulePart;
import vigil.shared.TenantId;

/**
 * Seed part 11, routing, imported through {@link RoutingImport} (DM-R-56 (11)): the two sections of
 * {@code 05-routing-escalation.yaml} read by one walk over the schema of P-15 spec Section 6.5.
 */
@Component
class RoutingAdapter implements SeedPartImporter {

  private static final String RULE_SET = "routing_rule_set_revision_1";
  private static final String POLICY = "escalation_policy_revision_1";
  private static final List<String> RULE_SINGLES =
      List.of(
          "rule_key",
          "name",
          "severity",
          "owner_mode",
          "owner_team_key",
          "inform_affected_service_owners");

  private final RoutingImport routingImport;

  RoutingAdapter(RoutingImport routingImport) {
    this.routingImport = routingImport;
  }

  @Override
  public int part() {
    return 11;
  }

  @Override
  public PartResult importPart(
      TenantId tenantId,
      SeedBundle bundle,
      Map<String, JsonNode> files,
      Instant importStartedAt,
      Map<String, UUID> handles) {
    SeedShapes shapes = new SeedShapes();
    Walk walk = new Walk(shapes);
    JsonNode file = files.get(SeedParts.ROUTING);
    List<RulePart> rules =
        walk.section(shapes.section(file, RULE_SET), RULE_SET, "rules", walk::rule);
    List<LevelPart> levels =
        walk.section(shapes.section(file, POLICY), POLICY, "levels", walk::level);
    shapes.refuseIfAny(SeedParts.part(part()).name());
    routingImport.importPart(tenantId, new RoutingPart(rules, levels), importStartedAt, handles);
    Map<String, Integer> counts = new LinkedHashMap<>();
    counts.put("rules", rules.size());
    counts.put("escalation_levels", levels.size());
    return new PartResult(counts, Map.of());
  }

  /** The walk: each node that does not fit adds one problem with its path. */
  private record Walk(SeedShapes shapes) {

    /** A section mapping with the one list field {@code field}; {@code null} when not read. */
    <T> List<T> section(
        JsonNode node, String at, String field, BiFunction<JsonNode, String, T> entry) {
      if (node == null || !mapping(node, at)) {
        return null;
      }
      List<T> list = null;
      boolean present = false;
      for (Map.Entry<String, JsonNode> property : node.properties()) {
        if (property.getKey().equals(field)) {
          present = true;
          if (property.getValue().isNull()) {
            shapes.add(at + "." + field + ": not a list");
          } else {
            list = list(property.getValue(), at + "." + field, entry);
          }
        } else {
          shapes.add(at + ": unknown field " + property.getKey());
        }
      }
      if (!present) {
        shapes.add(at + "." + field + ": not a list");
      }
      return list;
    }

    RulePart rule(JsonNode node, String at) {
      if (!mapping(node, at)) {
        return null;
      }
      Map<String, String> singles = new LinkedHashMap<>();
      List<ConditionPart> conditions = null;
      List<String> informed = null;
      for (Map.Entry<String, JsonNode> property : node.properties()) {
        String name = property.getKey();
        String path = at + "." + name;
        if (RULE_SINGLES.contains(name)) {
          singles.put(name, single(property.getValue(), path));
        } else if (name.equals("conditions")) {
          conditions = list(property.getValue(), path, this::condition);
        } else if (name.equals("informed_team_keys")) {
          informed = list(property.getValue(), path, this::single);
        } else {
          shapes.add(at + ": unknown field " + name);
        }
      }
      return new RulePart(
          singles.get("rule_key"),
          singles.get("name"),
          conditions,
          singles.get("severity"),
          singles.get("owner_mode"),
          singles.get("owner_team_key"),
          informed,
          singles.get("inform_affected_service_owners"));
    }

    ConditionPart condition(JsonNode node, String at) {
      if (!mapping(node, at)) {
        return null;
      }
      Map<String, String> singles = new LinkedHashMap<>();
      List<String> values = null;
      for (Map.Entry<String, JsonNode> property : node.properties()) {
        String name = property.getKey();
        JsonNode value = property.getValue();
        if (name.equals("field") || name.equals("operator")) {
          singles.put(name, single(value, at + "." + name));
        } else if (!name.equals("value")) {
          shapes.add(at + ": unknown field " + name);
        } else if (value.isArray() && value.valueStream().allMatch(JsonNode::isValueNode)) {
          values = list(value, at + "." + name, this::single);
        } else if (value.isValueNode()) {
          values = value.isNull() ? null : Collections.singletonList(SeedShapes.text(value));
        } else {
          shapes.add(at + ".value: not a single value or a list of single values");
        }
      }
      return new ConditionPart(singles.get("field"), singles.get("operator"), values);
    }

    LevelPart level(JsonNode node, String at) {
      if (!mapping(node, at)) {
        return null;
      }
      String level = null;
      List<String> teamKeys = null;
      for (Map.Entry<String, JsonNode> property : node.properties()) {
        String name = property.getKey();
        String path = at + "." + name;
        if (name.equals("level")) {
          level = single(property.getValue(), path);
        } else if (name.equals("team_keys")) {
          teamKeys = list(property.getValue(), path, this::single);
        } else {
          shapes.add(at + ": unknown field " + name);
        }
      }
      return new LevelPart(level, teamKeys);
    }

    /** A list field: YAML {@code null} is {@code null}; each entry read by {@code entry}. */
    private <T> List<T> list(JsonNode node, String at, BiFunction<JsonNode, String, T> entry) {
      if (node.isNull()) {
        return null;
      }
      if (!node.isArray()) {
        shapes.add(at + ": not a list");
        return null;
      }
      List<T> list = new ArrayList<>();
      for (int i = 0; i < node.size(); i++) {
        list.add(entry.apply(node.get(i), at + "[" + i + "]"));
      }
      return Collections.unmodifiableList(list);
    }

    private boolean mapping(JsonNode node, String at) {
      if (!node.isObject()) {
        shapes.add(at + ": not a mapping");
        return false;
      }
      return true;
    }

    private String single(JsonNode node, String at) {
      if (!node.isValueNode()) {
        shapes.add(at + ": not a single value");
        return null;
      }
      return SeedShapes.text(node);
    }
  }
}
