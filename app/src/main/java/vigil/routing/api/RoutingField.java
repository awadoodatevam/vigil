package vigil.routing.api;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The fields marked "routing: yes" in DM-R-25 (complete list, in its order), with their display
 * names (DC-TX-06), value types and operators (DM-R-26).
 */
public enum RoutingField {
  HYPOTHESIS_COMPONENT(
      "hypothesis_component", "Hypothesis component", ValueType.KEY_OR_ENUM, List.of()),
  HYPOTHESIS_COMPONENT_KIND(
      "hypothesis_component_kind",
      "Hypothesis component kind",
      ValueType.KEY_OR_ENUM,
      List.of("server", "application", "database", "queue", "check")),
  HYPOTHESIS_CRITICALITY(
      "hypothesis_criticality",
      "Hypothesis criticality",
      ValueType.ORDERED_ENUM,
      List.of("critical", "high", "medium", "low")),
  AFFECTED_SERVICES("affected_services", "Affected services", ValueType.SET, List.of()),
  AFFECTED_SERVICE_COUNT(
      "affected_service_count", "Number of affected services", ValueType.INTEGER, List.of()),
  HIGHEST_SERVICE_CRITICALITY(
      "highest_service_criticality",
      "Highest service criticality",
      ValueType.ORDERED_ENUM,
      List.of("critical", "high", "medium", "low")),
  ALERT_COUNT("alert_count", "Number of alerts", ValueType.INTEGER, List.of()),
  ALERT_TYPES("alert_types", "Alert types", ValueType.SET, List.of()),
  ALERT_SOURCES("alert_sources", "Alert sources", ValueType.SET, List.of("zabbix", "alertmanager"));

  private final String key;
  private final String displayName;
  private final ValueType valueType;
  private final List<String> allowedValues;

  RoutingField(String key, String displayName, ValueType valueType, List<String> allowedValues) {
    this.key = key;
    this.displayName = displayName;
    this.valueType = valueType;
    this.allowedValues = allowedValues;
  }

  public String key() {
    return key;
  }

  public String displayName() {
    return displayName;
  }

  public ValueType valueType() {
    return valueType;
  }

  /**
   * The allowed values of an enum field and of {@code ALERT_SOURCES}, from highest for the ordered
   * ones; empty for the key and integer fields.
   */
  public List<String> allowedValues() {
    return allowedValues;
  }

  /** The operators of the field's value type, in DM-R-26's order. */
  public List<RoutingOperator> operators() {
    return switch (valueType) {
      case ORDERED_ENUM ->
          List.of(
              RoutingOperator.EQUALS,
              RoutingOperator.NOT_EQUALS,
              RoutingOperator.IN,
              RoutingOperator.NOT_IN,
              RoutingOperator.AT_LEAST,
              RoutingOperator.AT_MOST);
      case KEY_OR_ENUM ->
          List.of(
              RoutingOperator.EQUALS,
              RoutingOperator.NOT_EQUALS,
              RoutingOperator.IN,
              RoutingOperator.NOT_IN);
      case SET -> List.of(RoutingOperator.CONTAINS, RoutingOperator.NOT_CONTAINS);
      case INTEGER ->
          List.of(
              RoutingOperator.EQUALS,
              RoutingOperator.NOT_EQUALS,
              RoutingOperator.GREATER_THAN,
              RoutingOperator.GREATER_OR_EQUAL,
              RoutingOperator.LESS_THAN,
              RoutingOperator.LESS_OR_EQUAL);
    };
  }

  /** The value with exactly this key; empty for {@code null} or an unknown key. */
  public static Optional<RoutingField> fromKey(String key) {
    return Arrays.stream(values()).filter(value -> value.key.equals(key)).findFirst();
  }
}
