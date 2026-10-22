package vigil.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;
import vigil.routing.api.ConditionPart;
import vigil.routing.api.EscalationLevel;
import vigil.routing.api.EscalationPolicyView;
import vigil.routing.api.LevelPart;
import vigil.routing.api.RoutingImport;
import vigil.routing.api.RoutingPart;
import vigil.routing.api.RoutingService;
import vigil.routing.api.RulePart;
import vigil.routing.api.RuleSetView;
import vigil.shared.Actor;
import vigil.shared.TenantId;
import vigil.shared.VigilError;
import vigil.testsupport.MutableClock;
import vigil.testsupport.TestTenants;
import vigil.testsupport.VigilDatabase;
import vigil.testsupport.VigilTestApp;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RoutingImportIT {

  private static final MutableClock CLOCK = new MutableClock(Instant.parse("2026-10-22T14:00:00Z"));
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String RULES = "routing_rule_set_revision_1.rules";

  private VigilTestApp app;
  private JdbcClient postgres;
  private RoutingImport routingImport;
  private RoutingService routing;
  private TenantId tenant;
  private int tenants;

  /** The test clock. */
  static class TestBeans {

    @Bean
    @Primary
    Clock testClock() {
      return CLOCK;
    }
  }

  @BeforeAll
  void start() {
    VigilDatabase database = VigilDatabase.fresh();
    app = VigilTestApp.start(database, TestBeans.class);
    app.awaitStarted();
    postgres = database.postgres();
    routingImport = app.context().getBean(RoutingImport.class);
    routing = app.context().getBean(RoutingService.class);
    tenant = newTenant();
  }

  @AfterAll
  void stop() {
    app.close();
  }

  private TenantId newTenant() {
    tenants++;
    return RoutingFixture.teams(new TestTenants(app).tenant("Routing import " + tenants))
        .create()
        .id();
  }

  private void importPart(TenantId into, RoutingPart part) {
    routingImport.importPart(into, part, Instant.now(), Map.of());
  }

  private long rows(String table) {
    return postgres.sql("SELECT count(*) FROM routing." + table).query(Long.class).single();
  }

  /** The part is refused with exactly {@code problems}, and nothing is stored. */
  private void refused(TenantId into, RoutingPart part, String... problems) {
    long rules = rows("routing_rule_set_revision");
    long levels = rows("escalation_policy_revision");
    VigilError error = catchThrowableOfType(VigilError.class, () -> importPart(into, part));
    assertThat(error).isNotNull();
    assertThat(error.code()).isEqualTo(VigilError.Code.INVALID);
    assertThat(error.message()).isEqualTo("Part routing refused.");
    assertThat(error.details()).isEqualTo(Map.of("problems", List.of(problems)));
    assertThat(rows("routing_rule_set_revision")).isEqualTo(rules);
    assertThat(rows("escalation_policy_revision")).isEqualTo(levels);
  }

  /** The demo part with rule {@code index} changed, refused on a new tenant. */
  private void ruleRefused(int index, UnaryOperator<RulePart> change, String... problems) {
    List<RulePart> rules = new ArrayList<>(RoutingFixture.demoPart().rules());
    rules.set(index, change.apply(rules.get(index)));
    refused(newTenant(), new RoutingPart(rules, RoutingFixture.demoPart().levels()), problems);
  }

  private void levelsRefused(List<LevelPart> levels, String... problems) {
    refused(newTenant(), new RoutingPart(RoutingFixture.demoPart().rules(), levels), problems);
  }

  private static RulePart condition(
      RulePart rule, String field, String operator, List<String> values) {
    return new RulePart(
        rule.ruleKey(),
        rule.name(),
        List.of(new ConditionPart(field, operator, values)),
        rule.severity(),
        rule.ownerMode(),
        rule.ownerTeamKey(),
        rule.informedTeamKeys(),
        rule.informAffectedServiceOwners());
  }

  private static RulePart rule(
      RulePart rule,
      String key,
      String name,
      String severity,
      String ownerMode,
      String owner,
      List<String> informed,
      String inform) {
    return new RulePart(key, name, rule.conditions(), severity, ownerMode, owner, informed, inform);
  }

  private static RulePart withKey(RulePart r, String key) {
    return rule(
        r,
        key,
        r.name(),
        r.severity(),
        r.ownerMode(),
        r.ownerTeamKey(),
        r.informedTeamKeys(),
        r.informAffectedServiceOwners());
  }

  @Test
  @Order(1)
  @DisplayName("DM-E-42 the escalation policy revision and routing rule set revision 1")
  void imported() {
    importPart(tenant, RoutingFixture.demoPart());
    RuleSetView ruleSet = routing.ruleSet(tenant).orElseThrow();
    assertThat(ruleSet.revision()).isEqualTo(1);
    assertThat(ruleSet.rules()).isEqualTo(RoutingFixture.demoRules());
    assertThat(ruleSet.savedAt()).isEqualTo(CLOCK.instant());
    assertThat(ruleSet.changeSummary())
        .isEqualTo(
            "added rule 'Critical service affected'; added rule 'High-criticality service"
                + " affected'; added rule 'Default'");
    assertThat(routing.escalationPolicy(tenant).orElseThrow().revision()).isEqualTo(1);
  }

  @Test
  @Order(2)
  @DisplayName("PM-M10-04 escalation levels kept as configuration data")
  void levels() {
    EscalationPolicyView policy = routing.escalationPolicy(tenant).orElseThrow();
    assertThat(policy.levels())
        .containsExactly(
            new EscalationLevel(1, List.of("noc-shift-lead")),
            new EscalationLevel(2, List.of("noc-management")));
    assertThat(policy.savedBy()).isEqualTo(Actor.module("vigil-setup"));
    assertThat(policy.savedAt()).isEqualTo(CLOCK.instant());
  }

  @Test
  @Order(3)
  @DisplayName("SL-B-09 rules and escalation levels as configuration records")
  void records() {
    String rules =
        postgres
            .sql("SELECT rules::text FROM routing.routing_rule_set_revision WHERE tenant_id = :t")
            .param("t", tenant.value())
            .query(String.class)
            .single();
    assertThat(JSON.readTree(rules))
        .isEqualTo(
            JSON.readTree(
                """
                [{"rule_key": "critical-service", "name": "Critical service affected",
                  "conditions": [{"field": "highest_service_criticality", "operator": "at_least",
                                  "value": "critical"}],
                  "severity": "P1", "owner_mode": "hypothesis_component_owner",
                  "owner_team_key": "noc-l1", "informed_team_keys": [],
                  "inform_affected_service_owners": true},
                 {"rule_key": "high-service", "name": "High-criticality service affected",
                  "conditions": [{"field": "highest_service_criticality", "operator": "at_least",
                                  "value": "high"}],
                  "severity": "P2", "owner_mode": "hypothesis_component_owner",
                  "owner_team_key": "noc-l1", "informed_team_keys": [],
                  "inform_affected_service_owners": true},
                 {"rule_key": "default", "name": "Default", "conditions": [],
                  "severity": "P4", "owner_mode": "hypothesis_component_owner",
                  "owner_team_key": "noc-l1", "informed_team_keys": [],
                  "inform_affected_service_owners": false}]
                """));
    String levels =
        postgres
            .sql("SELECT levels::text FROM routing.escalation_policy_revision WHERE tenant_id = :t")
            .param("t", tenant.value())
            .query(String.class)
            .single();
    assertThat(JSON.readTree(levels))
        .isEqualTo(
            JSON.readTree(
                "[{\"level\": 1, \"team_keys\": [\"noc-shift-lead\"]},"
                    + " {\"level\": 2, \"team_keys\": [\"noc-management\"]}]"));
  }

  @Test
  @Order(4)
  @DisplayName("DM-R-57 part 11 checked and refused as a whole")
  void refusals() {
    refused(tenant, RoutingFixture.demoPart(), "routing rules already exist");
    String rule0 = RULES + "[0]";
    ruleRefused(0, r -> withKey(r, "bad key"), rule0 + ".rule_key: not a key");
    ruleRefused(
        1, r -> withKey(r, "critical-service"), RULES + "[1].rule_key: used more than once");
    ruleRefused(
        0,
        r -> condition(r, "severity", "equals", List.of("P1")),
        rule0 + ".conditions[0].field: not a routing field");
    ruleRefused(
        0,
        r -> condition(r, "highest_service_criticality", "contains", List.of("high")),
        rule0 + ".conditions[0].operator: not an operator of highest_service_criticality");
    ruleRefused(
        0,
        r -> condition(r, "highest_service_criticality", "in", null),
        rule0 + ".conditions[0].value: at least one value");
    ruleRefused(
        0,
        r -> condition(r, "highest_service_criticality", "at_least", List.of("high", "low")),
        rule0 + ".conditions[0].value: one value");
    ruleRefused(
        0,
        r ->
            rule(
                r, r.ruleKey(), r.name(), "P5", r.ownerMode(), r.ownerTeamKey(), List.of(), "true"),
        rule0 + ".severity: P1, P2, P3 or P4");
    ruleRefused(
        0,
        r -> rule(r, r.ruleKey(), r.name(), "P1", "team", r.ownerTeamKey(), List.of(), "true"),
        rule0 + ".owner_mode: fixed_team or hypothesis_component_owner");
    ruleRefused(
        0,
        r ->
            rule(r, r.ruleKey(), r.name(), "P1", r.ownerMode(), r.ownerTeamKey(), List.of(), "yes"),
        rule0 + ".inform_affected_service_owners: true or false");
    ruleRefused(
        0,
        r ->
            new RulePart(
                r.ruleKey(),
                r.name(),
                null,
                r.severity(),
                r.ownerMode(),
                r.ownerTeamKey(),
                r.informedTeamKeys(),
                r.informAffectedServiceOwners()),
        rule0 + ".conditions: missing");
    ruleRefused(
        0,
        r -> rule(r, r.ruleKey(), r.name(), "P1", r.ownerMode(), r.ownerTeamKey(), null, "true"),
        rule0 + ".informed_team_keys: missing");
    ruleRefused(
        0,
        r ->
            rule(
                r,
                r.ruleKey(),
                "n".repeat(101),
                "P1",
                r.ownerMode(),
                r.ownerTeamKey(),
                List.of(),
                "true"),
        rule0 + ".name: at most 100 characters");
    List<RulePart> demo = RoutingFixture.demoPart().rules();
    refused(
        newTenant(),
        new RoutingPart(
            List.of(demo.get(2), demo.get(0), demo.get(1)), RoutingFixture.demoPart().levels()),
        "routing_rule_set_revision_1: Rule 'Default': add at least one condition.",
        "routing_rule_set_revision_1: The last rule must be the only rule without conditions.");
    levelsRefused(
        List.of(
            new LevelPart("1", List.of("noc-shift-lead")),
            new LevelPart("3", List.of("noc-management"))),
        "escalation_policy_revision_1.levels[1].level: must be 2");
    levelsRefused(
        List.of(new LevelPart("1", List.of())),
        "escalation_policy_revision_1.levels[0].team_keys: at least one team");
    levelsRefused(List.of(), "escalation_policy_revision_1.levels: at least one level");
    levelsRefused(
        List.of(new LevelPart("1", List.of("bad key"))),
        "escalation_policy_revision_1.levels[0].team_keys[0]: not a key");
    ruleRefused(
        0,
        r -> rule(r, "bad key", r.name(), "P1", r.ownerMode(), "noc-l9", List.of(), "true"),
        rule0 + ".rule_key: not a key");
  }
}
