package vigil.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vigil.incidents.api.Severity;
import vigil.routing.api.EvaluationView;
import vigil.routing.api.OwnerMode;
import vigil.routing.api.RequestedBy;
import vigil.routing.api.RoutingCondition;
import vigil.routing.api.RoutingField;
import vigil.routing.api.RoutingOperator;
import vigil.routing.api.RoutingRule;
import vigil.routing.api.RoutingService;
import vigil.routing.api.RuleSetView;
import vigil.shared.Actor;
import vigil.shared.VigilError;
import vigil.testsupport.MutableClock;
import vigil.testsupport.VigilDatabase;
import vigil.testsupport.VigilTestApp;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RoutingRulesIT {

  private static final MutableClock CLOCK = new MutableClock(Instant.parse("2026-10-22T13:00:00Z"));
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String ROUTING_EVENTS = "details ->> 'area' = 'routing_rules'";

  private VigilTestApp app;
  private JdbcClient postgres;
  private RoutingService routing;
  private RoutingFixture f;
  private UUID admin;
  private RuleSetView second;

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
    routing = app.context().getBean(RoutingService.class);
    f = RoutingFixture.create(app, database, "Routing rules");
    admin = f.userId("admin");
  }

  @AfterAll
  void stop() {
    app.close();
  }

  private long routingEvents() {
    return RoutingFixture.auditEvents(postgres, f.tenant, "configuration_changed", ROUTING_EVENTS);
  }

  private long revisions() {
    return postgres
        .sql("SELECT count(*) FROM routing.routing_rule_set_revision WHERE tenant_id = :t")
        .param("t", f.tenant.value())
        .query(Long.class)
        .single();
  }

  private JsonNode storedRules(long revision) {
    return JSON.readTree(
        postgres
            .sql(
                "SELECT rules::text FROM routing.routing_rule_set_revision"
                    + " WHERE tenant_id = :t AND revision = :r")
            .param("t", f.tenant.value())
            .param("r", revision)
            .query(String.class)
            .single());
  }

  private RuleSetView save(List<RoutingRule> rules) {
    long current = routing.ruleSet(f.tenant).orElseThrow().revision();
    return routing.saveRuleSet(f.tenant, admin, current, rules);
  }

  private static RoutingRule informing(RoutingRule rule, List<String> informed) {
    return new RoutingRule(
        rule.ruleKey(),
        rule.name(),
        rule.conditions(),
        rule.severity(),
        rule.ownerMode(),
        rule.ownerTeamKey(),
        informed,
        rule.informAffectedServiceOwners());
  }

  private static RoutingRule newRule(
      String name, RoutingCondition condition, Severity severity, String owner) {
    return new RoutingRule(
        null, name, List.of(condition), severity, OwnerMode.FIXED_TEAM, owner, List.of(), false);
  }

  /** The current rules with {@code rule} inserted above the default rule. */
  private List<RoutingRule> aboveDefault(RoutingRule rule) {
    List<RoutingRule> rules = new ArrayList<>(f.rules());
    rules.add(rules.size() - 1, rule);
    return rules;
  }

  /** The save is refused with {@code type}; nothing is stored and no audit event written. */
  private <T extends Throwable> T refused(Class<T> type, Runnable save) {
    long revisions = revisions();
    long events = routingEvents();
    T thrown = catchThrowableOfType(type, save::run);
    assertThat(thrown).isNotNull();
    assertThat(revisions()).isEqualTo(revisions);
    assertThat(routingEvents()).isEqualTo(events);
    return thrown;
  }

  @Test
  @Order(1)
  @DisplayName("PM-M10-01 a save is a new revision with user, time, before and after")
  void save() {
    List<RoutingRule> rules = new ArrayList<>(RoutingFixture.demoRules());
    rules.set(0, informing(rules.get(0), List.of("customer-care")));
    second = routing.saveRuleSet(f.tenant, admin, 1, rules);
    assertThat(second.revision()).isEqualTo(2);
    assertThat(second.savedBy()).isEqualTo(Actor.user(admin));
    assertThat(second.savedAt()).isEqualTo(CLOCK.instant());
    assertThat(second.changeSummary())
        .isEqualTo("changed rule 'Critical service affected' (informed teams)");
    assertThat(routing.ruleSet(f.tenant)).contains(second);
    assertThat(routing.ruleSetRevision(f.tenant, 1).orElseThrow().rules())
        .isEqualTo(RoutingFixture.demoRules());
  }

  @Test
  @Order(2)
  @DisplayName("DM-V-01 saved_by of a revision")
  void savedBy() {
    assertThat(routing.ruleSetRevision(f.tenant, 1).orElseThrow().savedBy())
        .isEqualTo(Actor.module("vigil-setup"));
    assertThat(routing.ruleSetRevision(f.tenant, 2).orElseThrow().savedBy())
        .isEqualTo(Actor.user(admin));
  }

  @Test
  @Order(3)
  @DisplayName("DM-C-07 one configuration_changed per save with area, actor, before and after")
  void auditEvent() {
    List<Map<String, Object>> events =
        postgres
            .sql(
                "SELECT actor_kind, actor_user_id, subject_type, subject_id, incident_id, summary,"
                    + " details::text AS details FROM audit.audit_event WHERE tenant_id = :t"
                    + " AND event_type = 'configuration_changed' AND "
                    + ROUTING_EVENTS)
            .param("t", f.tenant.value())
            .query()
            .listOfRows();
    assertThat(events).hasSize(1);
    Map<String, Object> event = events.getFirst();
    assertThat(event.get("actor_kind")).isEqualTo("user");
    assertThat(event.get("actor_user_id")).isEqualTo(admin);
    assertThat(event.get("subject_type")).isEqualTo("RoutingRuleSetRevision");
    assertThat(event.get("subject_id")).isEqualTo(second.id());
    assertThat(event.get("incident_id")).isNull();
    assertThat(event.get("summary"))
        .isEqualTo(
            "Routing rules saved as revision 2: changed rule 'Critical service affected'"
                + " (informed teams).");
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("area", "routing_rules");
    details.put("before", Map.of("revision", 1, "rules", storedRules(1)));
    details.put("after", Map.of("revision", 2, "rules", storedRules(2)));
    assertThat(JSON.readTree((String) event.get("details"))).isEqualTo(JSON.valueToTree(details));
    assertThat(
            RoutingFixture.auditEvents(
                postgres,
                f.tenant,
                "configuration_changed",
                "(actor_module = 'vigil-routing' OR subject_type = 'RoutingRuleSetRevision'"
                    + " AND subject_id <> '"
                    + second.id()
                    + "')"))
        .isZero();
  }

  @Test
  @Order(4)
  @DisplayName("DM-E08-R3 vigil-routing writes configuration_changed for its saves, once each")
  void oncePerSave() {
    assertThat(routingEvents()).isEqualTo(1);
    assertThat(
            RoutingFixture.auditEvents(
                postgres,
                f.tenant,
                "configuration_changed",
                ROUTING_EVENTS + " AND actor_user_id = '" + admin + "'"))
        .isEqualTo(1);
  }

  @Test
  @Order(5)
  @DisplayName("DM-E-41 new rules get keys rule-1, rule-2; the stored rules JSON")
  void ruleKeys() {
    RuleSetView third =
        save(
            aboveDefault(
                newRule(
                    "Low or high",
                    new RoutingCondition(
                        RoutingField.HYPOTHESIS_CRITICALITY,
                        RoutingOperator.IN,
                        List.of("low", "high", "low")),
                    Severity.P2,
                    "it-apps")));
    RoutingRule lowOrHigh = third.rules().get(2);
    assertThat(lowOrHigh.ruleKey()).isEqualTo("rule-1");
    assertThat(lowOrHigh.conditions().getFirst().values()).containsExactly("high", "low");
    RuleSetView fourth =
        save(
            aboveDefault(
                newRule(
                    "Many alerts",
                    new RoutingCondition(
                        RoutingField.ALERT_COUNT, RoutingOperator.GREATER_THAN, List.of("100")),
                    Severity.P3,
                    "noc-l1")));
    assertThat(fourth.rules().get(3).ruleKey()).isEqualTo("rule-2");
    assertThat(storedRules(fourth.revision()))
        .isEqualTo(
            JSON.readTree(
                """
                [{"rule_key": "critical-service", "name": "Critical service affected",
                  "conditions": [{"field": "highest_service_criticality", "operator": "at_least",
                                  "value": "critical"}],
                  "severity": "P1", "owner_mode": "hypothesis_component_owner",
                  "owner_team_key": "noc-l1", "informed_team_keys": ["customer-care"],
                  "inform_affected_service_owners": true},
                 {"rule_key": "high-service", "name": "High-criticality service affected",
                  "conditions": [{"field": "highest_service_criticality", "operator": "at_least",
                                  "value": "high"}],
                  "severity": "P2", "owner_mode": "hypothesis_component_owner",
                  "owner_team_key": "noc-l1", "informed_team_keys": [],
                  "inform_affected_service_owners": true},
                 {"rule_key": "rule-1", "name": "Low or high",
                  "conditions": [{"field": "hypothesis_criticality", "operator": "in",
                                  "value": ["high", "low"]}],
                  "severity": "P2", "owner_mode": "fixed_team", "owner_team_key": "it-apps",
                  "informed_team_keys": [], "inform_affected_service_owners": false},
                 {"rule_key": "rule-2", "name": "Many alerts",
                  "conditions": [{"field": "alert_count", "operator": "greater_than",
                                  "value": 100}],
                  "severity": "P3", "owner_mode": "fixed_team", "owner_team_key": "noc-l1",
                  "informed_team_keys": [], "inform_affected_service_owners": false},
                 {"rule_key": "default", "name": "Default", "conditions": [],
                  "severity": "P4", "owner_mode": "hypothesis_component_owner",
                  "owner_team_key": "noc-l1", "informed_team_keys": [],
                  "inform_affected_service_owners": false}]
                """));
    assertThat(routingEvents()).isEqualTo(3);
  }

  @Test
  @Order(6)
  @DisplayName("DM-R-27 a save creates the next revision, used by every later evaluation")
  void nextRevisionInForce() {
    List<RoutingRule> rules = new ArrayList<>(f.rules());
    RoutingRule last = rules.getLast();
    rules.set(
        rules.size() - 1,
        new RoutingRule(
            last.ruleKey(),
            last.name(),
            last.conditions(),
            Severity.P3,
            last.ownerMode(),
            last.ownerTeamKey(),
            last.informedTeamKeys(),
            last.informAffectedServiceOwners()));
    RuleSetView saved = save(rules);
    assertThat(saved.revision()).isEqualTo(5);
    EvaluationView view =
        routing.evaluate(
            f.tenant,
            RoutingFixture.CORRELATION,
            f.incident(null, List.of()),
            RequestedBy.CORRELATION);
    assertThat(view.result().severity()).isEqualTo(Severity.P3);
    assertThat(view.ruleSetRevision()).isEqualTo(saved.revision());
    assertThat(routingEvents()).isEqualTo(4);
  }

  @Test
  @Order(7)
  @DisplayName("PM-M10-03 a rule set without a default rule is refused, and the other refusals")
  void refusals() {
    List<RoutingRule> withoutDefault = new ArrayList<>(f.rules());
    withoutDefault.removeLast();
    VigilError invalid = refused(VigilError.class, () -> save(withoutDefault));
    assertThat(invalid.code()).isEqualTo(VigilError.Code.INVALID);
    assertThat(invalid.details().get("problems"))
        .isEqualTo(List.of("The last rule must be the only rule without conditions."));
    VigilError forbidden =
        refused(
            VigilError.class,
            () -> routing.saveRuleSet(f.tenant, f.userId("viewer"), 5, f.rules()));
    assertThat(forbidden.code()).isEqualTo(VigilError.Code.FORBIDDEN);
    VigilError conflict =
        refused(VigilError.class, () -> routing.saveRuleSet(f.tenant, admin, 1, f.rules()));
    assertThat(conflict.code()).isEqualTo(VigilError.Code.CONFLICT);
    refused(
        IllegalArgumentException.class,
        () ->
            save(
                aboveDefault(
                    newRule(
                        "Contains",
                        new RoutingCondition(
                            RoutingField.ALERT_COUNT, RoutingOperator.CONTAINS, List.of("3")),
                        Severity.P3,
                        "noc-l1"))));
    refused(
        IllegalArgumentException.class,
        () ->
            save(
                aboveDefault(
                    newRule(
                        "Two values",
                        new RoutingCondition(
                            RoutingField.ALERT_COUNT, RoutingOperator.EQUALS, List.of("3", "4")),
                        Severity.P3,
                        "noc-l1"))));
    List<RoutingRule> twice = new ArrayList<>(f.rules());
    twice.add(0, twice.get(1));
    refused(IllegalArgumentException.class, () -> save(twice));
    assertThat(routingEvents()).isEqualTo(4);
  }

  @Test
  @Order(8)
  @DisplayName("DM-E41-R1 the refusals of a revision with their texts and places")
  void invalidRevision() {
    RoutingRule unnamed =
        new RoutingRule(
            null,
            "",
            List.of(
                new RoutingCondition(
                    RoutingField.HIGHEST_SERVICE_CRITICALITY,
                    RoutingOperator.EQUALS,
                    List.of("extreme"))),
            Severity.P3,
            OwnerMode.FIXED_TEAM,
            "old-team",
            List.of(),
            false);
    assertThat(f.rules()).hasSize(5);
    VigilError invalid = refused(VigilError.class, () -> save(aboveDefault(unnamed)));
    assertThat(invalid.code()).isEqualTo(VigilError.Code.INVALID);
    assertThat(invalid.message()).isNull();
    assertThat(invalid.details().get("problems"))
        .isEqualTo(
            List.of(
                "Rule '5': a name is needed.",
                "Rule '5': 'extreme' is not valid for Highest service criticality.",
                "Rule '5': 'old-team' is not an active team."));
    JsonNode places = JSON.valueToTree(invalid.details().get("places"));
    assertThat(places)
        .isEqualTo(
            JSON.readTree(
                "[{\"rule\":4,\"field\":\"name\",\"condition\":null},"
                    + "{\"rule\":4,\"field\":\"conditions\",\"condition\":0},"
                    + "{\"rule\":4,\"field\":\"owner_team_key\",\"condition\":null}]"));
    assertThat(routingEvents()).isEqualTo(4);
  }

  @Test
  @Order(9)
  @DisplayName("BP-A-28 a save in which no rule changed: no rules changed")
  void noRulesChanged() {
    RuleSetView saved = save(f.rules());
    assertThat(saved.revision()).isEqualTo(6);
    assertThat(saved.changeSummary()).isEqualTo("no rules changed");
    assertThat(
            postgres
                .sql(
                    "SELECT summary FROM audit.audit_event WHERE tenant_id = :t"
                        + " AND subject_id = :id")
                .param("t", f.tenant.value())
                .param("id", saved.id())
                .query(String.class)
                .single())
        .isEqualTo("Routing rules saved as revision 6: no rules changed.");
    assertThat(routingEvents()).isEqualTo(5);
  }
}
