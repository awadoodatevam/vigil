package vigil.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
import tools.jackson.databind.json.JsonMapper;
import vigil.incidents.api.AlertCopy;
import vigil.incidents.api.GroupingReason;
import vigil.incidents.api.HypothesisCopy;
import vigil.incidents.api.IdentityPairCopy;
import vigil.incidents.api.IncidentService;
import vigil.incidents.api.IncidentView;
import vigil.incidents.api.Severity;
import vigil.incidents.api.TimelineEntryType;
import vigil.incidents.api.TimelineView;
import vigil.routing.api.EvaluationView;
import vigil.routing.api.NotAppliedReason;
import vigil.routing.api.OwnerMode;
import vigil.routing.api.RequestedBy;
import vigil.routing.api.RoutingResult;
import vigil.routing.api.RoutingRule;
import vigil.routing.api.RoutingService;
import vigil.shared.Actor;
import vigil.shared.Ids;
import vigil.shared.TenantId;
import vigil.shared.VigilError;
import vigil.testsupport.MutableClock;
import vigil.testsupport.TestTenants;
import vigil.testsupport.VigilDatabase;
import vigil.testsupport.VigilTestApp;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RoutingEvaluationIT {

  private static final MutableClock CLOCK = new MutableClock(Instant.parse("2026-10-22T12:00:00Z"));
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private VigilTestApp app;
  private JdbcClient postgres;
  private RoutingService routing;
  private IncidentService incidents;
  private RoutingFixture a;
  private RoutingFixture b;

  private UUID p;
  private UUID n;
  private UUID r;
  private EvaluationView pView;
  private EvaluationView nView;
  private EvaluationView rView;
  private EvaluationView hView;

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
    incidents = app.context().getBean(IncidentService.class);
    a = RoutingFixture.create(app, database, "Routing A");
    b = RoutingFixture.create(app, database, "Routing B");
  }

  @AfterAll
  void stop() {
    app.close();
  }

  private EvaluationView correlation(UUID incidentId) {
    return routing.evaluate(
        a.tenant, RoutingFixture.CORRELATION, incidentId, RequestedBy.CORRELATION);
  }

  private EvaluationView workflows(UUID incidentId, RequestedBy requestedBy) {
    return routing.evaluate(a.tenant, RoutingFixture.WORKFLOWS, incidentId, requestedBy);
  }

  private IncidentView incident(UUID incidentId) {
    return incidents.incident(a.tenant, incidentId).orElseThrow();
  }

  private long evaluationRows() {
    return postgres
        .sql("SELECT count(*) FROM routing.routing_evaluation")
        .query(Long.class)
        .single();
  }

  private static void result(
      EvaluationView view, String rule, Severity severity, String owner, String... informed) {
    assertThat(view.matchedRuleKey()).isEqualTo(rule);
    assertThat(view.result()).isEqualTo(new RoutingResult(severity, owner, List.of(informed)));
  }

  @Test
  @Order(1)
  @DisplayName("DM-R-25 the nine routing fields, their values recorded as inputs")
  void inputs() {
    CLOCK.set(Instant.parse("2026-10-22T12:00:00Z"));
    p = a.incident("srv-pay", List.of("svc-pay", "svc-care"));
    n = a.incident(null, List.of());
    r = a.incident("app-rep", List.of("svc-rep"));
    pView = correlation(p);
    nView = correlation(n);
    rView = correlation(r);
    assertThat(JSON.readTree(pView.inputsJson()))
        .isEqualTo(
            JSON.readTree(
                "{\"hypothesis_component\":\"srv-pay\",\"hypothesis_component_kind\":\"server\","
                    + "\"hypothesis_criticality\":\"critical\",\"affected_services\":[\"svc-care\","
                    + "\"svc-pay\"],\"affected_service_count\":2,\"highest_service_criticality\":"
                    + "\"critical\",\"alert_count\":1,\"alert_types\":[\"disk_full\"],"
                    + "\"alert_sources\":[\"zabbix\"]}"));
    assertThat(JSON.readTree(nView.inputsJson()))
        .isEqualTo(
            JSON.readTree(
                "{\"hypothesis_component\":null,\"hypothesis_component_kind\":null,"
                    + "\"hypothesis_criticality\":null,\"affected_services\":[],"
                    + "\"affected_service_count\":0,\"highest_service_criticality\":null,"
                    + "\"alert_count\":1,\"alert_types\":[\"disk_full\"],"
                    + "\"alert_sources\":[\"zabbix\"]}"));
    assertThat(JSON.readTree(rView.inputsJson()).get("highest_service_criticality").isNull())
        .isTrue();
    assertThat(JSON.readTree(rView.inputsJson()).get("affected_service_count").asInt())
        .isEqualTo(1);
  }

  @Test
  @Order(2)
  @DisplayName(
      "PM-M10-02 evaluation on request of correlation or workflows, applied to the incident")
  void evaluation() {
    result(pView, "critical-service", Severity.P1, "db-ops", "customer-care", "vas-service-owners");
    assertThat(pView.applied()).isTrue();
    result(rView, "default", Severity.P4, "it-apps");
    result(nView, "default", Severity.P4, "noc-l1");
    long rows = evaluationRows();
    record Refused(Actor caller, RequestedBy requestedBy, String message) {}
    for (Refused refused :
        List.of(
            new Refused(
                RoutingFixture.WORKFLOWS,
                RequestedBy.CORRELATION,
                "evaluate with correlation is accepted only from vigil-correlation"),
            new Refused(
                RoutingFixture.CORRELATION,
                RequestedBy.WORKFLOWS,
                "evaluate with workflows is accepted only from vigil-workflows"),
            new Refused(
                Actor.user(a.userId("admin")),
                RequestedBy.TEST_REPLAY,
                "evaluate with test_replay is accepted only from vigil-workflows"))) {
      assertThatThrownBy(
              () -> routing.evaluate(a.tenant, refused.caller(), p, refused.requestedBy()))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage(refused.message());
    }
    assertThat(evaluationRows()).isEqualTo(rows);
    VigilError unknown =
        catchThrowableOfType(
            VigilError.class,
            () ->
                routing.evaluate(
                    a.tenant, RoutingFixture.CORRELATION, Ids.newId(), RequestedBy.CORRELATION));
    assertThat(unknown.code()).isEqualTo(VigilError.Code.NOT_FOUND);
    TenantId bare = new TestTenants(app).tenant("No routing").create().id();
    UUID incident =
        incidents.create(
            bare,
            RoutingFixture.CORRELATION,
            Ids.newId(),
            new AlertCopy(
                Ids.newId(),
                "zabbix",
                "k-bare",
                "disk_full",
                "Disk full",
                "Disk full on srv-lone",
                "srv-lone",
                new IdentityPairCopy("component", "srv-lone"),
                CLOCK.instant()),
            new GroupingReason("created_incident", null, null, null, null));
    assertThatThrownBy(
            () ->
                routing.evaluate(
                    bare, RoutingFixture.CORRELATION, incident, RequestedBy.CORRELATION))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("the tenant has no routing rules");
    assertThat(evaluationRows()).isEqualTo(rows);
  }

  @Test
  @Order(3)
  @DisplayName("DM-E-43 the routing evaluation record")
  void record() {
    Map<String, Object> row =
        postgres
            .sql(
                "SELECT tenant_id, incident_id, requested_by, rule_set_revision, matched_rule_key,"
                    + " result_severity, result_owner_team_key,"
                    + " result_informed_team_keys::text AS informed, applied, not_applied_reason,"
                    + " at, created_at FROM routing.routing_evaluation WHERE id = :id")
            .param("id", pView.id())
            .query()
            .singleRow();
    assertThat(row.get("tenant_id")).isEqualTo(a.tenant.value());
    assertThat(row.get("incident_id")).isEqualTo(p);
    assertThat(row.get("requested_by")).isEqualTo("correlation");
    assertThat(row.get("rule_set_revision")).isEqualTo(1L);
    assertThat(row.get("matched_rule_key")).isEqualTo("critical-service");
    assertThat(row.get("result_severity")).isEqualTo("P1");
    assertThat(row.get("result_owner_team_key")).isEqualTo("db-ops");
    assertThat(JSON.readTree((String) row.get("informed")))
        .isEqualTo(JSON.readTree("[\"customer-care\",\"vas-service-owners\"]"));
    assertThat(row.get("applied")).isEqualTo(true);
    assertThat(row.get("not_applied_reason")).isNull();
    assertThat(((Timestamp) row.get("at")).toInstant()).isEqualTo(CLOCK.instant());
    List<EvaluationView> stored = routing.evaluations(a.tenant, p);
    assertThat(stored).hasSize(1);
    EvaluationView view = stored.getFirst();
    assertThat(view.id()).isEqualTo(pView.id());
    assertThat(view.incidentId()).isEqualTo(p);
    assertThat(view.requestedBy()).isEqualTo(RequestedBy.CORRELATION);
    assertThat(view.ruleSetRevision()).isEqualTo(1);
    assertThat(JSON.readTree(view.inputsJson())).isEqualTo(JSON.readTree(pView.inputsJson()));
    assertThat(view.matchedRuleKey()).isEqualTo("critical-service");
    assertThat(view.matchedRuleName()).isEqualTo("Critical service affected");
    assertThat(view.result()).isEqualTo(pView.result());
    assertThat(view.applied()).isTrue();
    assertThat(view.notAppliedReason()).isNull();
    assertThat(view.at()).isEqualTo(CLOCK.instant());
    assertThat(routing.evaluation(a.tenant, pView.id())).contains(view);
    assertThat(routing.evaluations(b.tenant, p)).isEmpty();
    assertThat(routing.evaluation(b.tenant, pView.id())).isEmpty();
  }

  @Test
  @Order(4)
  @DisplayName("DM-V-09 the routing result applied through vigil-incidents")
  void routingResult() {
    IncidentView incident = incident(p);
    assertThat(incident.severity()).isEqualTo(Severity.P1);
    assertThat(incident.ownerTeamKey()).isEqualTo("db-ops");
    assertThat(incident.informedTeamKeys()).containsExactly("customer-care", "vas-service-owners");
    assertThat(incident.assignmentEvaluationId()).isEqualTo(pView.id());
    List<TimelineView> assigned =
        incidents.timeline(a.tenant, p).stream()
            .filter(entry -> entry.entryType() == TimelineEntryType.ASSIGNMENT_CHANGED)
            .toList();
    assertThat(assigned).hasSize(1);
    assertThat(assigned.getFirst().text())
        .isEqualTo(
            "Assigned P1, owner Database Operations, informed Customer Care, VAS Service Owners"
                + " (rule 'Critical service affected').");
    assertThat(assigned.getFirst().actor()).isEqualTo(Actor.module("vigil-routing"));
  }

  @Test
  @Order(5)
  @DisplayName("SL-A1-04 the assignment part: severity, owner and informed teams set by the rules")
  void assignment() {
    hView = correlation(a.incident("app-care", List.of("svc-care")));
    result(hView, "high-service", Severity.P2, "customer-care");
  }

  @Test
  @Order(6)
  @DisplayName("DM-E43-R1 the same inputs give the same rule and result")
  void deterministic() {
    CLOCK.advance(Duration.ofSeconds(1));
    EvaluationView again = correlation(p);
    assertThat(again.id()).isNotEqualTo(pView.id());
    EvaluationView stored = routing.evaluation(a.tenant, again.id()).orElseThrow();
    assertThat(JSON.readTree(stored.inputsJson())).isEqualTo(JSON.readTree(pView.inputsJson()));
    assertThat(stored.matchedRuleKey()).isEqualTo(pView.matchedRuleKey());
    assertThat(stored.result()).isEqualTo(pView.result());
    assertThat(stored.applied()).isTrue();
    assertThat(incident(p).assignmentEvaluationId()).isEqualTo(again.id());
    assertThat(routing.evaluations(a.tenant, p)).hasSize(2);
  }

  @Test
  @Order(7)
  @DisplayName("BP-A-28 empty or inactive owner teams are left out of the owner and informed")
  void missingOwners() {
    EvaluationView l =
        correlation(a.incident("srv-lone", List.of("svc-old", "svc-none", "svc-ghost")));
    result(l, "critical-service", Severity.P1, "noc-l1");
    EvaluationView o = correlation(a.incident("srv-old", List.of()));
    assertThat(o.result().ownerTeamKey()).isEqualTo("noc-l1");
    EvaluationView g =
        correlation(
            a.incidentWith(
                new HypothesisCopy(1, "srv-gone", "Gone server", "server", "critical"), List.of()));
    assertThat(g.result().ownerTeamKey()).isEqualTo("noc-l1");
  }

  @Test
  @Order(8)
  @DisplayName("DM-E41-R2 the owner team is removed from the informed parties")
  void ownerNotInformed() {
    assertThat(hView.result().informedTeamKeys()).isEmpty();
    List<RoutingRule> rules = new ArrayList<>(a.rules());
    RoutingRule last = rules.getLast();
    rules.set(
        rules.size() - 1,
        new RoutingRule(
            last.ruleKey(),
            last.name(),
            last.conditions(),
            last.severity(),
            OwnerMode.FIXED_TEAM,
            "noc-l1",
            List.of("noc-l1", "it-apps"),
            last.informAffectedServiceOwners()));
    assertThat(routing.saveRuleSet(a.tenant, a.userId("admin"), 1, rules).revision()).isEqualTo(2);
    EvaluationView view = correlation(a.incident(null, List.of()));
    result(view, "default", Severity.P4, "noc-l1", "it-apps");
  }

  @Test
  @Order(9)
  @DisplayName("DM-R-17 applied only while forming; after open recorded with assignment_frozen")
  void frozen() {
    a.open(p);
    IncidentView opened = incident(p);
    List<TimelineView> timeline = incidents.timeline(a.tenant, p);
    for (EvaluationView view : List.of(correlation(p), workflows(p, RequestedBy.WORKFLOWS))) {
      assertThat(view.applied()).isFalse();
      assertThat(view.notAppliedReason()).isEqualTo(NotAppliedReason.ASSIGNMENT_FROZEN);
      assertThat(routing.evaluation(a.tenant, view.id())).isPresent();
    }
    IncidentView after = incident(p);
    assertThat(after.severity()).isEqualTo(opened.severity());
    assertThat(after.ownerTeamKey()).isEqualTo(opened.ownerTeamKey());
    assertThat(after.informedTeamKeys()).isEqualTo(opened.informedTeamKeys());
    assertThat(after.assignmentEvaluationId()).isEqualTo(opened.assignmentEvaluationId());
    assertThat(incidents.timeline(a.tenant, p)).isEqualTo(timeline);
  }

  private UUID merged;

  @Test
  @Order(10)
  @DisplayName("DM-R-16 never applied to an incident that is not forming or open")
  void notActive() {
    UUID survivor = a.incident(null, List.of());
    merged = a.incident(null, List.of());
    incidents.merge(a.tenant, RoutingFixture.CORRELATION, survivor, List.of(merged));
    IncidentView before = incident(merged);
    EvaluationView view = correlation(merged);
    assertThat(view.applied()).isFalse();
    assertThat(view.notAppliedReason()).isEqualTo(NotAppliedReason.INCIDENT_NOT_ACTIVE);
    assertThat(incident(merged)).isEqualTo(before);
  }

  @Test
  @Order(11)
  @DisplayName("DM-R-28 a test replay evaluates as a dry run with the current revision")
  void dryRun() {
    UUID forming = a.incident("srv-pay", List.of("svc-pay"));
    EvaluationView view = workflows(forming, RequestedBy.TEST_REPLAY);
    assertThat(view.applied()).isFalse();
    assertThat(view.notAppliedReason()).isEqualTo(NotAppliedReason.DRY_RUN);
    assertThat(view.ruleSetRevision()).isEqualTo(2);
    assertThat(incident(forming).severity()).isNull();
    assertThat(incident(forming).assignmentEvaluationId()).isNull();
    assertThat(workflows(merged, RequestedBy.TEST_REPLAY).notAppliedReason())
        .isEqualTo(NotAppliedReason.DRY_RUN);
  }
}
