package vigil.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vigil.incidents.api.Severity;
import vigil.routing.api.OwnerMode;
import vigil.routing.api.RoutingCondition;
import vigil.routing.api.RoutingField;
import vigil.routing.api.RoutingOperator;
import vigil.routing.api.RoutingRule;

class RuleMatcherTest {

  private static final RoutingInputs INPUTS =
      new RoutingInputs(
          "srv-pay",
          "server",
          "high",
          List.of("svc-care", "svc-pay"),
          "high",
          3,
          List.of("disk_full"),
          List.of("zabbix"));
  private static final RoutingInputs EMPTY =
      new RoutingInputs(null, null, null, List.of(), null, 3, List.of(), List.of());

  private static boolean test(
      RoutingInputs inputs, RoutingField field, RoutingOperator operator, String... values) {
    return RuleMatcher.matches(new RoutingCondition(field, operator, List.of(values)), inputs);
  }

  /** The operator is true with {@code yes} and false with {@code no}. */
  private static void trueFalse(
      RoutingInputs inputs, RoutingField field, RoutingOperator operator, String yes, String no) {
    assertThat(test(inputs, field, operator, yes)).as("%s %s %s", field, operator, yes).isTrue();
    assertThat(test(inputs, field, operator, no)).as("%s %s %s", field, operator, no).isFalse();
  }

  private static RoutingRule rule(String key, RoutingCondition... conditions) {
    return new RoutingRule(
        key,
        key,
        List.of(conditions),
        Severity.P3,
        OwnerMode.FIXED_TEAM,
        "noc-l1",
        List.of(),
        false);
  }

  @Test
  @DisplayName("DM-R-26 each operator of each routing value type; no value is false for all")
  void operators() {
    // Inputs with the ordered values "high".
    for (RoutingField field :
        List.of(RoutingField.HYPOTHESIS_CRITICALITY, RoutingField.HIGHEST_SERVICE_CRITICALITY)) {
      trueFalse(INPUTS, field, RoutingOperator.EQUALS, "high", "critical");
      trueFalse(INPUTS, field, RoutingOperator.NOT_EQUALS, "critical", "high");
      assertThat(test(INPUTS, field, RoutingOperator.IN, "high", "low")).isTrue();
      assertThat(test(INPUTS, field, RoutingOperator.IN, "critical")).isFalse();
      assertThat(test(INPUTS, field, RoutingOperator.NOT_IN, "critical")).isTrue();
      assertThat(test(INPUTS, field, RoutingOperator.NOT_IN, "high")).isFalse();
      trueFalse(INPUTS, field, RoutingOperator.AT_LEAST, "medium", "critical");
      trueFalse(INPUTS, field, RoutingOperator.AT_MOST, "high", "medium");
    }
    RoutingField component = RoutingField.HYPOTHESIS_COMPONENT;
    trueFalse(INPUTS, component, RoutingOperator.EQUALS, "srv-pay", "srv-old");
    trueFalse(INPUTS, component, RoutingOperator.NOT_EQUALS, "srv-old", "srv-pay");
    assertThat(test(INPUTS, component, RoutingOperator.IN, "db-pay", "srv-pay")).isTrue();
    assertThat(test(INPUTS, component, RoutingOperator.IN, "db-pay")).isFalse();
    assertThat(test(INPUTS, component, RoutingOperator.NOT_IN, "db-pay")).isTrue();
    assertThat(test(INPUTS, component, RoutingOperator.NOT_IN, "srv-pay", "db-pay")).isFalse();
    RoutingField kind = RoutingField.HYPOTHESIS_COMPONENT_KIND;
    trueFalse(INPUTS, kind, RoutingOperator.EQUALS, "server", "database");
    trueFalse(INPUTS, kind, RoutingOperator.NOT_EQUALS, "database", "server");
    assertThat(test(INPUTS, kind, RoutingOperator.IN, "server", "queue")).isTrue();
    assertThat(test(INPUTS, kind, RoutingOperator.IN, "queue")).isFalse();
    assertThat(test(INPUTS, kind, RoutingOperator.NOT_IN, "queue")).isTrue();
    assertThat(test(INPUTS, kind, RoutingOperator.NOT_IN, "server")).isFalse();
    trueFalse(
        INPUTS, RoutingField.AFFECTED_SERVICES, RoutingOperator.CONTAINS, "svc-pay", "svc-rep");
    trueFalse(
        INPUTS,
        RoutingField.AFFECTED_SERVICES,
        RoutingOperator.NOT_CONTAINS,
        "svc-rep",
        "svc-care");
    trueFalse(INPUTS, RoutingField.ALERT_TYPES, RoutingOperator.CONTAINS, "disk_full", "cpu");
    trueFalse(INPUTS, RoutingField.ALERT_TYPES, RoutingOperator.NOT_CONTAINS, "cpu", "disk_full");
    trueFalse(
        INPUTS, RoutingField.ALERT_SOURCES, RoutingOperator.CONTAINS, "zabbix", "alertmanager");
    trueFalse(
        INPUTS, RoutingField.ALERT_SOURCES, RoutingOperator.NOT_CONTAINS, "alertmanager", "zabbix");
    assertThat(test(EMPTY, RoutingField.AFFECTED_SERVICES, RoutingOperator.CONTAINS, "svc-pay"))
        .isFalse();
    assertThat(test(EMPTY, RoutingField.AFFECTED_SERVICES, RoutingOperator.NOT_CONTAINS, "svc-pay"))
        .isTrue();
    // alert_count is 3 and so is affected_service_count (three services).
    RoutingInputs three =
        new RoutingInputs(
            null, null, null, List.of("a", "b", "c"), null, 3, List.of(), List.of("zabbix"));
    for (RoutingField field :
        List.of(RoutingField.ALERT_COUNT, RoutingField.AFFECTED_SERVICE_COUNT)) {
      trueFalse(three, field, RoutingOperator.EQUALS, "3", "4");
      trueFalse(three, field, RoutingOperator.NOT_EQUALS, "4", "3");
      trueFalse(three, field, RoutingOperator.GREATER_THAN, "2", "3");
      trueFalse(three, field, RoutingOperator.GREATER_OR_EQUAL, "3", "4");
      trueFalse(three, field, RoutingOperator.LESS_THAN, "4", "3");
      trueFalse(three, field, RoutingOperator.LESS_OR_EQUAL, "3", "2");
    }
    for (RoutingField field :
        List.of(
            RoutingField.HYPOTHESIS_COMPONENT,
            RoutingField.HYPOTHESIS_COMPONENT_KIND,
            RoutingField.HYPOTHESIS_CRITICALITY,
            RoutingField.HIGHEST_SERVICE_CRITICALITY)) {
      String value = field.allowedValues().isEmpty() ? "srv-pay" : field.allowedValues().get(1);
      for (RoutingOperator operator : field.operators()) {
        assertThat(test(EMPTY, field, operator, value)).as("%s %s", field, operator).isFalse();
      }
    }
  }

  @Test
  @DisplayName("DM-E43-R1 the first rule whose conditions are all true; deterministic")
  void firstMatchingRule() {
    RoutingCondition high =
        new RoutingCondition(
            RoutingField.HIGHEST_SERVICE_CRITICALITY, RoutingOperator.AT_LEAST, List.of("high"));
    RoutingCondition database =
        new RoutingCondition(
            RoutingField.HYPOTHESIS_COMPONENT_KIND, RoutingOperator.EQUALS, List.of("database"));
    RoutingCondition zabbix =
        new RoutingCondition(
            RoutingField.ALERT_SOURCES, RoutingOperator.CONTAINS, List.of("zabbix"));
    assertThat(RuleMatcher.matches(rule("and", high, zabbix), INPUTS)).isTrue();
    assertThat(RuleMatcher.matches(rule("and", high, database), INPUTS)).isFalse();
    List<RoutingRule> rules =
        List.of(
            rule("one", high, database), rule("two", high), rule("three", zabbix), rule("last"));
    assertThat(RuleMatcher.first(rules, INPUTS).orElseThrow().ruleKey()).isEqualTo("two");
    assertThat(RuleMatcher.first(rules, EMPTY).orElseThrow().ruleKey()).isEqualTo("last");
    assertThat(RuleMatcher.matches(rule("last"), EMPTY)).isTrue();
    assertThat(RuleMatcher.first(rules, INPUTS)).isEqualTo(RuleMatcher.first(rules, INPUTS));
    assertThat(RuleMatcher.first(RoutingFixture.demoRules(), INPUTS).orElseThrow().ruleKey())
        .isEqualTo("high-service")
        .isEqualTo(RuleMatcher.first(RoutingFixture.demoRules(), INPUTS).orElseThrow().ruleKey());
  }
}
