package vigil.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vigil.incidents.api.Severity;
import vigil.routing.api.OwnerMode;
import vigil.routing.api.RoutingCondition;
import vigil.routing.api.RoutingField;
import vigil.routing.api.RoutingOperator;
import vigil.routing.api.RoutingRule;

class ChangeSummaryTest {

  private static final List<RoutingRule> DEMO = RoutingFixture.demoRules();
  private static final RoutingRule CRITICAL = DEMO.get(0);
  private static final RoutingRule HIGH = DEMO.get(1);
  private static final RoutingRule DEFAULT = DEMO.get(2);

  private static RoutingRule with(
      RoutingRule rule,
      String name,
      List<RoutingCondition> conditions,
      Severity severity,
      OwnerMode ownerMode,
      List<String> informed,
      boolean inform) {
    return new RoutingRule(
        rule.ruleKey(),
        name,
        conditions,
        severity,
        ownerMode,
        rule.ownerTeamKey(),
        informed,
        inform);
  }

  private static RoutingRule renamed(RoutingRule rule, String name) {
    return with(
        rule,
        name,
        rule.conditions(),
        rule.severity(),
        rule.ownerMode(),
        rule.informedTeamKeys(),
        rule.informAffectedServiceOwners());
  }

  private static RoutingRule informing(RoutingRule rule, List<String> informed) {
    return with(
        rule,
        rule.name(),
        rule.conditions(),
        rule.severity(),
        rule.ownerMode(),
        informed,
        rule.informAffectedServiceOwners());
  }

  private static RoutingRule inCondition(String key, String... values) {
    return new RoutingRule(
        key,
        "Kinds",
        List.of(
            new RoutingCondition(
                RoutingField.HYPOTHESIS_COMPONENT_KIND, RoutingOperator.IN, List.of(values))),
        Severity.P3,
        OwnerMode.FIXED_TEAM,
        "noc-l1",
        List.of(),
        false);
  }

  @Test
  @DisplayName("DC-TX-10 the change summary and the configuration_changed summary")
  void summaries() {
    assertThat(ChangeSummary.of(null, DEMO))
        .isEqualTo(
            "added rule 'Critical service affected'; added rule 'High-criticality service"
                + " affected'; added rule 'Default'");
    List<RoutingRule> changed =
        List.of(informing(CRITICAL, List.of("customer-care")), HIGH, DEFAULT);
    assertThat(ChangeSummary.of(DEMO, changed))
        .isEqualTo("changed rule 'Critical service affected' (informed teams)");
    RoutingRule added = renamed(inCondition("new", "server"), "New rule");
    assertThat(ChangeSummary.of(DEMO, List.of(CRITICAL, HIGH, added, DEFAULT)))
        .isEqualTo("added rule 'New rule'");
    assertThat(ChangeSummary.of(DEMO, List.of(HIGH, CRITICAL, DEFAULT)))
        .isEqualTo(
            "changed rule 'High-criticality service affected' (position); changed rule 'Critical"
                + " service affected' (position)");
    assertThat(ChangeSummary.of(DEMO, List.of(renamed(CRITICAL, "Critical"), DEFAULT)))
        .isEqualTo(
            "changed rule 'Critical' (name); removed rule 'High-criticality service affected'");
    RoutingRule everything =
        new RoutingRule(
            CRITICAL.ruleKey(),
            "Critical",
            List.of(
                new RoutingCondition(
                    RoutingField.ALERT_COUNT, RoutingOperator.GREATER_THAN, List.of("5"))),
            Severity.P2,
            OwnerMode.FIXED_TEAM,
            CRITICAL.ownerTeamKey(),
            List.of("it-apps"),
            false);
    assertThat(ChangeSummary.of(DEMO, List.of(HIGH, everything, DEFAULT)))
        .isEqualTo(
            "changed rule 'High-criticality service affected' (position); changed rule 'Critical'"
                + " (name, conditions, severity, owner, informed teams, inform owners of affected"
                + " services, position)");
    RoutingRule owner =
        with(
            CRITICAL,
            CRITICAL.name(),
            CRITICAL.conditions(),
            CRITICAL.severity(),
            OwnerMode.FIXED_TEAM,
            CRITICAL.informedTeamKeys(),
            true);
    assertThat(ChangeSummary.of(DEMO, List.of(owner, HIGH, DEFAULT)))
        .isEqualTo("changed rule 'Critical service affected' (owner)");
    assertThat(
            ChangeSummary.of(
                List.of(inCondition("kinds", "server", "queue"), DEFAULT),
                List.of(inCondition("kinds", "queue", "server"), DEFAULT)))
        .isEqualTo("no rules changed");
    assertThat(ChangeSummary.audit(2, "changed rule 'Critical service affected' (informed teams)"))
        .isEqualTo(
            "Routing rules saved as revision 2: changed rule 'Critical service affected'"
                + " (informed teams).");
    List<RoutingRule> twelve = new ArrayList<>();
    StringBuilder all = new StringBuilder();
    for (int i = 0; i < 12; i++) {
      String name = ("Rule number " + i + " ").repeat(5).substring(0, 40);
      twelve.add(renamed(inCondition("k" + i, "server"), name));
      all.append(i == 0 ? "" : "; ").append("added rule '").append(name).append("'");
    }
    String summary = ChangeSummary.of(null, twelve);
    assertThat(all.length()).isGreaterThan(300);
    assertThat(summary).isEqualTo(all.substring(0, 300));
    assertThat(ChangeSummary.audit(1, summary))
        .isEqualTo(("Routing rules saved as revision 1: " + summary + ".").substring(0, 300));
  }

  @Test
  @DisplayName("BP-A-28 identical rules: no rules changed")
  void noRulesChanged() {
    String summary = ChangeSummary.of(DEMO, RoutingFixture.demoRules());
    assertThat(summary).isEqualTo("no rules changed");
    assertThat(ChangeSummary.audit(3, summary))
        .isEqualTo("Routing rules saved as revision 3: no rules changed.");
  }
}
