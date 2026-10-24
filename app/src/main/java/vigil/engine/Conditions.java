package vigil.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import vigil.incidents.api.AffectedServiceCopy;
import vigil.incidents.api.AiStatus;
import vigil.incidents.api.IncidentView;

/**
 * The Condition step (DM-S-02, PM-M12-03): structured comparisons over the incident fields of
 * DM-R-25 with the operators of DM-R-26, joined by AND; branches in list order, else {@code
 * otherwise}.
 */
final class Conditions {

  static final String OTHERWISE = "otherwise";

  private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
  private static final List<String> SEVERITIES = List.of("P1", "P2", "P3", "P4");
  private static final List<String> CRITICALITIES = List.of("critical", "high", "medium", "low");
  private static final Set<String> KINDS =
      Set.of("server", "application", "database", "queue", "check");
  private static final Set<String> SOURCES = Set.of("zabbix", "alertmanager");

  private enum ValueType {
    ORDERED,
    KEY_OR_ENUM,
    SET,
    INTEGER,
    BOOLEAN
  }

  /**
   * A field: its value type, the allowed values ({@code null} for any key), highest first for an
   * ordered enum, and how it is read from the incident ({@code null} for no value).
   */
  private record Field(ValueType type, List<String> values, Function<IncidentView, Object> read) {}

  private static final Map<String, Field> FIELDS =
      Map.ofEntries(
          Map.entry(
              "severity",
              new Field(
                  ValueType.ORDERED,
                  SEVERITIES,
                  i -> i.severity() == null ? null : i.severity().key())),
          Map.entry(
              "owner_team", new Field(ValueType.KEY_OR_ENUM, null, IncidentView::ownerTeamKey)),
          Map.entry(
              "informed_teams", new Field(ValueType.SET, null, IncidentView::informedTeamKeys)),
          Map.entry(
              "hypothesis_component",
              new Field(ValueType.KEY_OR_ENUM, null, IncidentView::hypothesisComponentKey)),
          Map.entry(
              "hypothesis_component_kind",
              new Field(
                  ValueType.KEY_OR_ENUM,
                  List.copyOf(KINDS),
                  i -> i.hypothesis() == null ? null : i.hypothesis().kind())),
          Map.entry(
              "hypothesis_criticality",
              new Field(
                  ValueType.ORDERED,
                  CRITICALITIES,
                  i -> i.hypothesis() == null ? null : i.hypothesis().criticality())),
          Map.entry(
              "affected_services",
              new Field(
                  ValueType.SET,
                  null,
                  i ->
                      i.affectedServices().stream().map(AffectedServiceCopy::serviceKey).toList())),
          Map.entry(
              "affected_service_count",
              new Field(ValueType.INTEGER, null, i -> (long) i.affectedServices().size())),
          Map.entry(
              "highest_service_criticality",
              new Field(ValueType.ORDERED, CRITICALITIES, Conditions::highestCriticality)),
          Map.entry("alert_count", new Field(ValueType.INTEGER, null, IncidentView::alertCount)),
          Map.entry("alert_types", new Field(ValueType.SET, null, IncidentView::alertTypes)),
          Map.entry(
              "alert_sources",
              new Field(ValueType.SET, List.copyOf(SOURCES), IncidentView::alertSources)),
          Map.entry(
              "acknowledged", new Field(ValueType.BOOLEAN, null, i -> i.acknowledgedAt() != null)),
          Map.entry(
              "escalation_level",
              new Field(ValueType.INTEGER, null, IncidentView::escalationLevel)),
          Map.entry(
              "ai_investigation_available",
              new Field(ValueType.BOOLEAN, null, i -> i.aiStatus() == AiStatus.AVAILABLE)),
          Map.entry("reopen_count", new Field(ValueType.INTEGER, null, IncidentView::reopenCount)));

  private Conditions() {}

  /** The named output of a Condition step with these parameters for the incident. */
  static String output(IncidentView incident, JsonNode parameters) {
    for (JsonNode branch : parameters.path("branches")) {
      List<Boolean> results = new ArrayList<>();
      for (JsonNode comparison : branch.path("comparisons")) {
        results.add(compare(incident, comparison));
      }
      if (!results.contains(false) && branch.path("label").isString()) {
        return branch.get("label").stringValue();
      }
    }
    return OTHERWISE;
  }

  /** One comparison {@code {"field", "operator", "value"}}; false when it cannot be made. */
  static boolean compare(IncidentView incident, JsonNode comparison) {
    Field field = FIELDS.get(comparison.path("field").asString(""));
    String operator = comparison.path("operator").asString("");
    JsonNode value = comparison.path("value");
    if (field == null) {
      return false;
    }
    Object actual = field.read().apply(incident);
    if (actual == null) {
      return false;
    }
    return switch (field.type()) {
      case ORDERED -> ordered(field, (String) actual, operator, value);
      case KEY_OR_ENUM -> keyOrEnum(field, (String) actual, operator, value);
      case SET -> set(field, (List<?>) actual, operator, value);
      case INTEGER -> integer((Long) actual, operator, value);
      case BOOLEAN ->
          operator.equals("is") && value.isBoolean() && value.booleanValue() == (Boolean) actual;
    };
  }

  private static boolean ordered(Field field, String actual, String operator, JsonNode value) {
    return switch (operator) {
      case "at_least" ->
          valid(field, value)
              && field.values().indexOf(actual) <= field.values().indexOf(value.stringValue());
      case "at_most" ->
          valid(field, value)
              && field.values().indexOf(actual) >= field.values().indexOf(value.stringValue());
      default -> keyOrEnum(field, actual, operator, value);
    };
  }

  private static boolean keyOrEnum(Field field, String actual, String operator, JsonNode value) {
    return switch (operator) {
      case "equals" -> valid(field, value) && actual.equals(value.stringValue());
      case "not_equals" -> valid(field, value) && !actual.equals(value.stringValue());
      case "in" -> validList(field, value) && list(value).contains(actual);
      case "not_in" -> validList(field, value) && !list(value).contains(actual);
      default -> false;
    };
  }

  private static boolean set(Field field, List<?> actual, String operator, JsonNode value) {
    return switch (operator) {
      case "contains" -> valid(field, value) && actual.contains(value.stringValue());
      case "not_contains" -> valid(field, value) && !actual.contains(value.stringValue());
      default -> false;
    };
  }

  private static boolean integer(long actual, String operator, JsonNode value) {
    if (!value.isIntegralNumber() || !value.canConvertToLong()) {
      return false;
    }
    long expected = value.longValue();
    return switch (operator) {
      case "equals" -> actual == expected;
      case "not_equals" -> actual != expected;
      case "greater_than" -> actual > expected;
      case "greater_or_equal" -> actual >= expected;
      case "less_than" -> actual < expected;
      case "less_or_equal" -> actual <= expected;
      default -> false;
    };
  }

  /** A single value of the field's form: a key, or one of its allowed values. */
  private static boolean valid(Field field, JsonNode value) {
    if (!value.isString()) {
      return false;
    }
    String text = value.stringValue();
    return field.values() == null ? KEY.matcher(text).matches() : field.values().contains(text);
  }

  private static boolean validList(Field field, JsonNode value) {
    if (!value.isArray()) {
      return false;
    }
    for (JsonNode element : value) {
      if (!valid(field, element)) {
        return false;
      }
    }
    return true;
  }

  private static List<String> list(JsonNode value) {
    List<String> values = new ArrayList<>();
    value.forEach(element -> values.add(element.stringValue()));
    return values;
  }

  /** The highest criticality among the affected services; {@code null} when none has one. */
  private static String highestCriticality(IncidentView incident) {
    return incident.affectedServices().stream()
        .map(AffectedServiceCopy::criticality)
        .filter(criticality -> criticality != null)
        .min((a, b) -> CRITICALITIES.indexOf(a) - CRITICALITIES.indexOf(b))
        .orElse(null);
  }
}
