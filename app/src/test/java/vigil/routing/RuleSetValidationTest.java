package vigil.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vigil.incidents.api.Severity;
import vigil.routing.api.OwnerMode;
import vigil.routing.api.RoutingCondition;
import vigil.routing.api.RoutingField;
import vigil.routing.api.RoutingOperator;
import vigil.routing.api.RoutingRule;
import vigil.routing.api.ValueType;

class RuleSetValidationTest {

  private static final Set<String> ACTIVE =
      Set.of(
          "db-ops",
          "noc-l1",
          "it-apps",
          "vas-service-owners",
          "customer-care",
          "noc-shift-lead",
          "noc-management");
  private static final String LAST = "The last rule must be the only rule without conditions.";

  private static List<String> texts(List<RoutingRule> rules) {
    return RuleSetValidation.problems(rules, ACTIVE::contains).stream()
        .map(RuleSetValidation.Problem::text)
        .toList();
  }

  private static RoutingCondition condition(
      RoutingField field, RoutingOperator operator, String... values) {
    return new RoutingCondition(field, operator, values == null ? null : Arrays.asList(values));
  }

  private static RoutingRule rule(
      String name, String owner, List<String> informed, RoutingCondition... conditions) {
    return new RoutingRule(
        null, name, List.of(conditions), Severity.P3, OwnerMode.FIXED_TEAM, owner, informed, false);
  }

  private static RoutingRule rule(String name, RoutingCondition... conditions) {
    return rule(name, "noc-l1", List.of(), conditions);
  }

  private static RoutingRule last() {
    return rule("Last");
  }

  private static RoutingCondition alerts(String value) {
    return condition(RoutingField.ALERT_COUNT, RoutingOperator.EQUALS, value);
  }

  /** The demo rules with rule {@code index} replaced. */
  private static List<RoutingRule> demoWith(int index, RoutingRule replacement) {
    List<RoutingRule> rules = new ArrayList<>(RoutingFixture.demoRules());
    rules.set(index, replacement);
    return rules;
  }

  private static RoutingRule withOwner(RoutingRule rule, String owner, List<String> informed) {
    return new RoutingRule(
        rule.ruleKey(),
        rule.name(),
        rule.conditions(),
        rule.severity(),
        rule.ownerMode(),
        owner,
        informed,
        rule.informAffectedServiceOwners());
  }

  @Test
  @DisplayName("DM-E41-R1 the demo rules pass; inactive and unknown teams are refused")
  void refusals() {
    assertThat(texts(RoutingFixture.demoRules())).isEmpty();
    RoutingRule defaultRule = RoutingFixture.demoRules().get(2);
    assertThat(texts(demoWith(2, withOwner(defaultRule, "old-team", List.of()))))
        .containsExactly("Rule 'Default': 'old-team' is not an active team.");
    RoutingRule critical = RoutingFixture.demoRules().get(0);
    assertThat(texts(demoWith(0, withOwner(critical, "noc-l1", List.of("nobody")))))
        .containsExactly("Rule 'Critical service affected': 'nobody' is not an active team.");
  }

  @Test
  @DisplayName("PM-M10-03 a rule set that does not end with a default rule is refused")
  void defaultRuleLast() {
    List<RoutingRule> demo = RoutingFixture.demoRules();
    assertThat(texts(List.of(demo.get(2), demo.get(0), demo.get(1))))
        .containsExactly("Rule 'Default': add at least one condition.", LAST);
    assertThat(texts(List.of())).containsExactly(LAST);
  }

  @Test
  @DisplayName("DC-TX-11 the routing rules refusals and incomplete-field texts")
  void texts() {
    assertThat(texts(List.of(rule(" ", alerts("1")), last())))
        .containsExactly("Rule '1': a name is needed.");
    assertThat(
            texts(
                List.of(
                    rule(
                        "A",
                        condition(RoutingField.ALERT_COUNT, null, "1"),
                        condition(RoutingField.ALERT_COUNT, RoutingOperator.EQUALS),
                        condition(RoutingField.ALERT_COUNT, RoutingOperator.EQUALS, " ")),
                    last())))
        .containsExactly("Rule 'A': complete or remove the empty condition.");
    assertThat(texts(List.of(rule("A", alerts("x")), last())))
        .containsExactly("Rule 'A': 'x' is not valid for Number of alerts.");
    assertThat(texts(List.of(rule("A"), last())))
        .containsExactly("Rule 'A': add at least one condition.");
    assertThat(texts(List.of(rule("A", alerts("1"))))).containsExactly(LAST);
    assertThat(texts(List.of(rule("A", "ghost", List.of(), alerts("1")), last())))
        .containsExactly("Rule 'A': 'ghost' is not an active team.");
    assertThat(texts(List.of(rule("A", null, List.of(), alerts("1")), rule("B", " ", List.of()))))
        .containsExactly("Rule 'A': choose a team.", "Rule 'B': choose a team.");
  }

  @Test
  @DisplayName("DC-TX-06 the field display names in the refusal texts")
  void displayNames() {
    assertThat(
            texts(
                List.of(
                    rule(
                        "A",
                        condition(
                            RoutingField.HYPOTHESIS_COMPONENT_KIND,
                            RoutingOperator.EQUALS,
                            "router"),
                        condition(
                            RoutingField.HIGHEST_SERVICE_CRITICALITY,
                            RoutingOperator.EQUALS,
                            "extreme"),
                        alerts("three"),
                        condition(
                            RoutingField.AFFECTED_SERVICES, RoutingOperator.CONTAINS, "svc pay"),
                        condition(RoutingField.ALERT_SOURCES, RoutingOperator.CONTAINS, "snmp")),
                    last())))
        .containsExactly(
            "Rule 'A': 'router' is not valid for Hypothesis component kind.",
            "Rule 'A': 'extreme' is not valid for Highest service criticality.",
            "Rule 'A': 'three' is not valid for Number of alerts.",
            "Rule 'A': 'svc pay' is not valid for Affected services.",
            "Rule 'A': 'snmp' is not valid for Alert sources.");
    assertThat(Arrays.stream(RoutingField.values()).map(RoutingField::displayName).toList())
        .containsExactly(
            "Hypothesis component",
            "Hypothesis component kind",
            "Hypothesis criticality",
            "Affected services",
            "Number of affected services",
            "Highest service criticality",
            "Number of alerts",
            "Alert types",
            "Alert sources");
  }

  @Test
  @DisplayName("DM-V-03 comparison: field, operator, one value or a list for in / not_in")
  void comparisons() {
    assertThat(
            texts(
                List.of(
                    rule(
                        "A",
                        condition(
                            RoutingField.HYPOTHESIS_CRITICALITY,
                            RoutingOperator.IN,
                            "high",
                            "urgent")),
                    last())))
        .containsExactly("Rule 'A': 'urgent' is not valid for Hypothesis criticality.");
    List<String> levels = List.of("critical", "high", "medium", "low");
    List<RoutingOperator> ordered =
        List.of(
            RoutingOperator.EQUALS,
            RoutingOperator.NOT_EQUALS,
            RoutingOperator.IN,
            RoutingOperator.NOT_IN,
            RoutingOperator.AT_LEAST,
            RoutingOperator.AT_MOST);
    List<RoutingOperator> keyOrEnum = ordered.subList(0, 4);
    List<RoutingOperator> set = List.of(RoutingOperator.CONTAINS, RoutingOperator.NOT_CONTAINS);
    List<RoutingOperator> integer =
        List.of(
            RoutingOperator.EQUALS,
            RoutingOperator.NOT_EQUALS,
            RoutingOperator.GREATER_THAN,
            RoutingOperator.GREATER_OR_EQUAL,
            RoutingOperator.LESS_THAN,
            RoutingOperator.LESS_OR_EQUAL);
    record Expected(
        RoutingField field, ValueType type, List<String> values, List<RoutingOperator> ops) {}
    for (Expected expected :
        List.of(
            new Expected(
                RoutingField.HYPOTHESIS_COMPONENT, ValueType.KEY_OR_ENUM, List.of(), keyOrEnum),
            new Expected(
                RoutingField.HYPOTHESIS_COMPONENT_KIND,
                ValueType.KEY_OR_ENUM,
                List.of("server", "application", "database", "queue", "check"),
                keyOrEnum),
            new Expected(
                RoutingField.HYPOTHESIS_CRITICALITY, ValueType.ORDERED_ENUM, levels, ordered),
            new Expected(RoutingField.AFFECTED_SERVICES, ValueType.SET, List.of(), set),
            new Expected(
                RoutingField.AFFECTED_SERVICE_COUNT, ValueType.INTEGER, List.of(), integer),
            new Expected(
                RoutingField.HIGHEST_SERVICE_CRITICALITY, ValueType.ORDERED_ENUM, levels, ordered),
            new Expected(RoutingField.ALERT_COUNT, ValueType.INTEGER, List.of(), integer),
            new Expected(RoutingField.ALERT_TYPES, ValueType.SET, List.of(), set),
            new Expected(
                RoutingField.ALERT_SOURCES,
                ValueType.SET,
                List.of("zabbix", "alertmanager"),
                set))) {
      assertThat(expected.field().valueType())
          .as("%s", expected.field())
          .isEqualTo(expected.type());
      assertThat(expected.field().allowedValues())
          .as("%s", expected.field())
          .isEqualTo(expected.values());
      assertThat(expected.field().operators()).as("%s", expected.field()).isEqualTo(expected.ops());
    }
  }

  @Test
  @DisplayName("BP-A-28 an unnamed rule is named by its position")
  void unnamedRules() {
    RoutingRule second = RoutingFixture.demoRules().get(1);
    RoutingRule unnamed =
        new RoutingRule(
            second.ruleKey(),
            "",
            second.conditions(),
            second.severity(),
            second.ownerMode(),
            "old-team",
            second.informedTeamKeys(),
            second.informAffectedServiceOwners());
    assertThat(texts(demoWith(1, unnamed)))
        .containsExactly(
            "Rule '2': a name is needed.", "Rule '2': 'old-team' is not an active team.");
    List<RoutingRule> rules =
        List.of(
            rule("", null, List.of(), condition(RoutingField.ALERT_COUNT, null, "1")),
            rule("B"),
            rule("C", alerts("x")));
    assertThat(texts(rules))
        .containsExactly(
            "Rule '1': a name is needed.",
            "Rule '1': complete or remove the empty condition.",
            "Rule '1': choose a team.",
            "Rule 'B': add at least one condition.",
            "Rule 'C': 'x' is not valid for Number of alerts.",
            LAST);
  }
}
