package vigil.correlation;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
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
import vigil.correlation.api.CorrelationService;
import vigil.correlation.api.Decision;
import vigil.correlation.api.GroupingDecisionView;
import vigil.correlation.api.GroupingRelation;
import vigil.correlation.api.HypothesisComputationView;
import vigil.correlation.api.TieBreak;
import vigil.incidents.api.AffectedServiceCopy;
import vigil.incidents.api.IncidentService;
import vigil.incidents.api.IncidentState;
import vigil.incidents.api.IncidentView;
import vigil.incidents.api.MembershipView;
import vigil.incidents.api.TransitionView;
import vigil.intake.IntakeFixture;
import vigil.intake.api.AlertView;
import vigil.routing.api.EvaluationView;
import vigil.routing.api.NotAppliedReason;
import vigil.routing.api.RoutingService;
import vigil.shared.Actor;
import vigil.shared.TenantId;
import vigil.testsupport.MutableClock;
import vigil.testsupport.TestSecrets;
import vigil.testsupport.VigilDatabase;
import vigil.testsupport.VigilTestApp;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GroupingIT {

  private static final Instant T0 = Instant.parse("2026-10-22T10:00:00Z");
  private static final MutableClock CLOCK = new MutableClock(T0);

  private WireMockServer wiremock;
  private VigilDatabase database;
  private JdbcClient postgres;
  private VigilTestApp app;
  private CorrelationFixture a;
  private CorrelationFixture b;
  private CorrelationService correlation;
  private IncidentService incidents;
  private RoutingService routing;

  private AlertView s;
  private AlertView u1;
  private AlertView bundle;
  private AlertView d;
  private AlertView u2;
  private AlertView u3;
  private AlertView disk;
  private AlertView r2;
  private AlertView balance1;
  private AlertView balance2;
  private UUID i1;
  private UUID i2;
  private UUID iu;
  private UUID iv;
  private final List<AlertView> posted = new ArrayList<>();

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
    wiremock = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort());
    wiremock.start();
    IntakeFixture.stub(wiremock);
    database = VigilDatabase.fresh();
    postgres = database.postgres();
    TestSecrets secrets = IntakeFixture.secrets(database, "", "-b");
    app = VigilTestApp.start(database, secrets.folder(), List.of(), TestBeans.class);
    app.awaitStarted();
    String address = "http://127.0.0.1:" + wiremock.port();
    a = CorrelationFixture.create(app, secrets, "Tenant A", "a.grouping.test", address, "");
    b = CorrelationFixture.create(app, secrets, "Tenant B", "b.grouping.test", address, "-b");
    correlation = app.context().getBean(CorrelationService.class);
    incidents = app.context().getBean(IncidentService.class);
    routing = app.context().getBean(RoutingService.class);
  }

  @AfterAll
  void stop() {
    app.close();
    wiremock.stop();
  }

  private AlertView zabbix(String component, String host, String eventName) {
    AlertView alert = a.postZabbix(component, host, eventName);
    posted.add(alert);
    a.awaitDecided();
    return alert;
  }

  private AlertView alertmanager(String component, String instance, String alertname) {
    AlertView alert = a.postAlertmanager(component, instance, alertname);
    posted.add(alert);
    a.awaitDecided();
    return alert;
  }

  private GroupingDecisionView decision(AlertView alert) {
    return correlation.decision(a.tenant, alert.id()).orElseThrow();
  }

  private IncidentView incident(UUID id) {
    return incidents.incident(a.tenant, id).orElseThrow();
  }

  private MembershipView member(UUID incidentId, AlertView alert) {
    return incidents.members(a.tenant, incidentId).stream()
        .filter(m -> m.alertId().equals(alert.id()))
        .findFirst()
        .orElseThrow();
  }

  /** A stored reason as {related alert, relation, path, time delta}. */
  private static List<Object> reason(String json) {
    JsonNode node = CorrelationFixture.JSON.readTree(json);
    if (node.get("relation").stringValue().equals("only_alert")) {
      return List.of("only_alert");
    }
    List<String> path = new ArrayList<>();
    node.get("relation_path").forEach(key -> path.add(key.stringValue()));
    return List.of(
        node.get("related_alert_id").stringValue(),
        node.get("relation").stringValue(),
        path,
        node.get("time_delta").longValue());
  }

  private static List<Object> reason(
      AlertView related, String relation, List<String> path, long delta) {
    return List.of(related.id().toString(), relation, path, delta);
  }

  private static void assertReason(
      GroupingDecisionView decision,
      AlertView related,
      GroupingRelation relation,
      List<String> path,
      long delta) {
    assertThat(decision.relatedAlertId()).isEqualTo(related.id());
    assertThat(decision.relation()).isEqualTo(relation);
    assertThat(decision.relationPath()).isEqualTo(path);
    assertThat(decision.timeDeltaSeconds()).isEqualTo(delta);
  }

  private long audit(TenantId tenant, String type) {
    return postgres
        .sql("SELECT count(*) FROM audit.audit_event WHERE tenant_id = :t AND event_type = :type")
        .param("t", tenant.value())
        .param("type", type)
        .query(Long.class)
        .single();
  }

  @Test
  @Order(1)
  @DisplayName("PM-M08-01 an alert related to no incident creates a new forming incident")
  void newIncident() {
    s = zabbix("sms-queue", "lab-vas-01", "SMS queue growing on route r1");
    GroupingDecisionView decision = decision(s);
    assertThat(decision.decision()).isEqualTo(Decision.CREATED_INCIDENT);
    i1 = decision.incidentId();
    assertThat(incident(i1).state()).isEqualTo(IncidentState.FORMING);
    u1 = alertmanager("nowhere-x", "i-1", "NodeFilesystemFull");
    assertThat(decision(u1).decision()).isEqualTo(Decision.CREATED_INCIDENT);
    iu = decision(u1).incidentId();
    assertThat(iu).isNotEqualTo(i1);
    assertThat(incident(iu).state()).isEqualTo(IncidentState.FORMING);
  }

  @Test
  @Order(2)
  @DisplayName("DM-E-29 the grouping decision record of a created incident")
  void decisionRecord() {
    GroupingDecisionView decision = decision(s);
    assertThat(decision.alertId()).isEqualTo(s.id());
    assertThat(decision.alertSequence()).isEqualTo(s.sequence());
    assertThat(decision.componentKey()).isEqualTo("sms-queue");
    assertThat(decision.generation()).isEqualTo(1);
    assertThat(decision.incidentId()).isEqualTo(i1);
    assertThat(decision.mergedIncidentIds()).isEmpty();
    assertThat(decision.relatedAlertId()).isNull();
    assertThat(decision.relation()).isNull();
    assertThat(decision.relationPath()).isNull();
    assertThat(decision.timeDeltaSeconds()).isNull();
    assertThat(decision.decidedAt()).isEqualTo(T0);
    assertThat(correlation.decisions(a.tenant, i1)).containsExactly(decision);
  }

  @Test
  @Order(3)
  @DisplayName("DM-R-05 the matched component by the identity pairs in order; unknown when none")
  void matchedComponent() {
    assertThat(s.identityPairs().getFirst().name()).isEqualTo("component");
    assertThat(decision(s).componentKey()).isEqualTo("sms-queue");
    assertThat(decision(u1).componentKey()).isNull();
    assertThat(member(i1, s).alert().componentKey()).isEqualTo("sms-queue");
  }

  @Test
  @Order(4)
  @DisplayName("PM-M08-02 the service-map relation and the time relation recorded with the alert")
  void relationRecorded() {
    CLOCK.advance(Duration.ofSeconds(10));
    bundle = alertmanager("api-bundle", "api-bundle-1", "BundleApiErrors");
    assertThat(decision(bundle).decision()).isEqualTo(Decision.CREATED_INCIDENT);
    i2 = decision(bundle).incidentId();
    assertThat(i2).isNotIn(i1, iu);
    CLOCK.advance(Duration.ofSeconds(5));
    d = zabbix("db-subscriber", "lab-db-01", "Database write errors on schema s1");
    GroupingDecisionView joined = decision(d);
    assertThat(joined.decision()).isEqualTo(Decision.JOINED);
    assertThat(joined.incidentId()).isEqualTo(i1);
    assertReason(
        joined, s, GroupingRelation.DEPENDED_ON_BY, List.of("db-subscriber", "sms-queue"), 15);
    JsonNode stored = CorrelationFixture.JSON.readTree(member(i1, d).reasonJson());
    assertThat(stored.get("decision").stringValue()).isEqualTo("joined");
    assertThat(reason(member(i1, d).reasonJson()))
        .isEqualTo(reason(s, "depended_on_by", List.of("db-subscriber", "sms-queue"), 15));
  }

  @Test
  @Order(5)
  @DisplayName("PM-M08-03 an alert related to no member of an incident goes to a separate one")
  void separateIncident() {
    u2 = alertmanager("nowhere-x", "i-2", "NodeFilesystemFull");
    GroupingDecisionView joined = decision(u2);
    assertThat(joined.decision()).isEqualTo(Decision.JOINED);
    assertThat(joined.incidentId()).isEqualTo(iu);
    assertThat(joined.relation()).isEqualTo(GroupingRelation.SAME_UNKNOWN_IDENTITY);
    assertThat(joined.relationPath()).isEmpty();
    assertThat(joined.relatedAlertId()).isEqualTo(u1.id());
    u3 = alertmanager("nowhere-y", "i-1", "NodeFilesystemFull");
    assertThat(decision(u3).decision()).isEqualTo(Decision.CREATED_INCIDENT);
    iv = decision(u3).incidentId();
    assertThat(iv).isNotIn(i1, i2, iu);
  }

  @Test
  @Order(6)
  @DisplayName("DM-R-10 case (c): the alert joins the lowest forming candidate, the others merge")
  void decisionCases() {
    assertThat(decision(d).mergedIncidentIds()).containsExactly(i2);
    assertThat(decision(d).incidentId()).isEqualTo(i1);
    assertThat(incident(i1).sequence()).isLessThan(incident(i2).sequence());
  }

  @Test
  @Order(7)
  @DisplayName("DM-R-12 the merge is requested from vigil-incidents")
  void mergeRequested() {
    IncidentView merged = incident(i2);
    assertThat(merged.state()).isEqualTo(IncidentState.MERGED);
    assertThat(merged.mergedIntoIncidentId()).isEqualTo(i1);
    assertThat(incidents.members(a.tenant, i2)).isEmpty();
  }

  @Test
  @Order(8)
  @DisplayName("PM-M08-09 forming incidents merged: the survivor holds every member")
  void formingMerged() {
    assertThat(incidents.members(a.tenant, i1).stream().map(MembershipView::alertId))
        .containsExactlyInAnyOrder(s.id(), bundle.id(), d.id());
  }

  @Test
  @Order(9)
  @DisplayName("DM-R-09 alerts decided one at a time in sequence order")
  void sequenceOrder() {
    List<Long> sequences = posted.stream().map(AlertView::sequence).toList();
    assertThat(sequences).isSorted();
    assertThat(posted.stream().map(alert -> decision(alert).alertSequence()).toList())
        .isEqualTo(sequences);
    assertThat(correlation.cursor(a.tenant)).isEqualTo(sequences.getLast());
  }

  @Test
  @Order(10)
  @DisplayName("PM-M08-07 routing evaluated on creation and on each change, before the next alert")
  void routingEvaluations() {
    assertThat(routing.evaluations(a.tenant, i1)).hasSize(2);
    assertThat(routing.evaluations(a.tenant, i2)).hasSize(1);
    assertThat(routing.evaluations(a.tenant, iu)).hasSize(1);
    assertThat(routing.evaluations(a.tenant, iv)).hasSize(1);
    assertThat(routing.evaluations(a.tenant, i1).getLast().at()).isEqualTo(decision(d).decidedAt());
  }

  @Test
  @Order(11)
  @DisplayName("DM-E-30 the hypothesis computations of an incident")
  void computations() {
    List<HypothesisComputationView> computed = correlation.hypothesisComputations(a.tenant, i1);
    assertThat(computed).hasSize(2);
    HypothesisComputationView first = computed.getFirst();
    assertThat(first.affectedComponentKeys()).containsExactly("sms-queue");
    assertThat(first.resultComponentKey()).isEqualTo("sms-queue");
    assertThat(first.tieBreak()).isEqualTo(TieBreak.NONE);
    assertThat(first.previousComponentKey()).isNull();
    assertThat(first.generation()).isEqualTo(1);
    HypothesisComputationView second = computed.get(1);
    assertThat(second.affectedComponentKeys())
        .containsExactly("api-bundle", "db-subscriber", "sms-queue");
    assertThat(second.dependentCounts())
        .containsExactly(
            Map.entry("api-bundle", 0L),
            Map.entry("db-subscriber", 2L),
            Map.entry("sms-queue", 0L));
    assertThat(second.resultComponentKey()).isEqualTo("db-subscriber");
    assertThat(second.tieBreak()).isEqualTo(TieBreak.NONE);
    assertThat(second.previousComponentKey()).isEqualTo("sms-queue");
    assertThat(correlation.latestHypothesisComputation(a.tenant, i1)).contains(second);
  }

  @Test
  @Order(12)
  @DisplayName("PM-M08-04 the hypothesis computed when the components change and set")
  void hypothesisSet() {
    IncidentView incident = incident(i1);
    assertThat(incident.hypothesisComponentKey()).isEqualTo("db-subscriber");
    assertThat(incident.hypothesis().name()).isEqualTo("Subscriber database");
    assertThat(incident.hypothesisComputationId())
        .isEqualTo(correlation.latestHypothesisComputation(a.tenant, i1).orElseThrow().id());
    assertThat(incident(iu).hypothesis()).isNull();
    assertThat(correlation.hypothesisComputations(a.tenant, iu))
        .isNotEmpty()
        .allSatisfy(computation -> assertThat(computation.resultComponentKey()).isNull());
  }

  private static final List<AffectedServiceCopy> I1_IMPACT =
      List.of(
          new AffectedServiceCopy(
              "svc-balance-check", "Balance Check (USSD)", "critical", List.of("db-subscriber")),
          new AffectedServiceCopy(
              "svc-bundle-purchase",
              "Bundle Purchase",
              "critical",
              List.of("api-bundle", "db-subscriber")),
          new AffectedServiceCopy(
              "svc-sms-notifications", "SMS Notifications", "high", List.of("sms-queue")));

  @Test
  @Order(13)
  @DisplayName("PM-M08-05 the customer impact computed and set on the incident")
  void impactSet() {
    assertThat(incident(i1).affectedServices()).isEqualTo(I1_IMPACT);
  }

  @Test
  @Order(14)
  @DisplayName("DM-R-19 the affected services with their via components, names and criticality")
  void impactRule() {
    assertThat(
            incident(i1).affectedServices().stream()
                .collect(
                    Collectors.toMap(
                        AffectedServiceCopy::serviceKey, AffectedServiceCopy::viaComponentKeys)))
        .isEqualTo(
            Map.of(
                "svc-balance-check", List.of("db-subscriber"),
                "svc-bundle-purchase", List.of("api-bundle", "db-subscriber"),
                "svc-sms-notifications", List.of("sms-queue")));
    assertThat(incident(iu).affectedServices()).isEmpty();
  }

  @Test
  @Order(15)
  @DisplayName("DM-R-15 stable incidents evaluated, then moved to open")
  void stability() {
    CLOCK.advance(Duration.ofSeconds(39));
    IntakeFixture.sleep(Duration.ofSeconds(3));
    for (UUID id : List.of(i1, iu, iv)) {
      assertThat(incident(id).state()).isEqualTo(IncidentState.FORMING);
    }
    CLOCK.advance(Duration.ofSeconds(1));
    for (UUID id : List.of(i1, iu, iv)) {
      a.awaitState(id, IncidentState.OPEN);
    }
    assertThat(incident(i1).number()).isEqualTo(1);
    for (UUID id : List.of(i1, iu, iv)) {
      TransitionView opened =
          incidents.transitions(a.tenant, id).stream()
              .filter(t -> t.transitionId().equals("DM-SM05-T2"))
              .findFirst()
              .orElseThrow();
      assertThat(opened.actor()).isEqualTo(Actor.module("vigil-correlation"));
      EvaluationView last = routing.evaluations(a.tenant, id).getLast();
      assertThat(last.at()).isEqualTo(opened.at());
      assertThat(last.applied()).isTrue();
    }
  }

  @Test
  @Order(16)
  @DisplayName("DM-R-58 the final grouping reasons passed at open")
  void finalReasons() {
    assertThat(reason(member(i1, s).finalReasonJson()))
        .isEqualTo(reason(d, "depends_on", List.of("sms-queue", "db-subscriber"), 15));
    assertThat(reason(member(i1, bundle).finalReasonJson()))
        .isEqualTo(reason(d, "depends_on", List.of("api-bundle", "db-subscriber"), 5));
    assertThat(reason(member(i1, d).finalReasonJson()))
        .isEqualTo(reason(s, "depended_on_by", List.of("db-subscriber", "sms-queue"), 15));
    assertThat(reason(member(iv, u3).finalReasonJson())).isEqualTo(List.of("only_alert"));
  }

  @Test
  @Order(17)
  @DisplayName("DM-R-17 after open: evaluations recorded, not applied; hypothesis still follows")
  void afterOpen() {
    int before = routing.evaluations(a.tenant, i1).size();
    disk = zabbix("srv-db-01", "lab-db-01", "Disk full: /data free space below 1 GiB");
    GroupingDecisionView joined = decision(disk);
    assertThat(joined.decision()).isEqualTo(Decision.JOINED);
    assertThat(joined.incidentId()).isEqualTo(i1);
    assertReason(
        joined,
        s,
        GroupingRelation.DEPENDED_ON_BY,
        List.of("srv-db-01", "db-subscriber", "sms-queue"),
        55);
    assertThat(reason(member(i1, disk).finalReasonJson()))
        .isEqualTo(
            reason(s, "depended_on_by", List.of("srv-db-01", "db-subscriber", "sms-queue"), 55));
    List<EvaluationView> evaluations = routing.evaluations(a.tenant, i1);
    assertThat(evaluations).hasSize(before + 1);
    assertThat(evaluations.getLast().applied()).isFalse();
    assertThat(evaluations.getLast().notAppliedReason())
        .isEqualTo(NotAppliedReason.ASSIGNMENT_FROZEN);
    assertThat(incident(i1).hypothesisComponentKey()).isEqualTo("srv-db-01");
    assertThat(
            correlation
                .latestHypothesisComputation(a.tenant, i1)
                .orElseThrow()
                .dependentCounts()
                .get("srv-db-01"))
        .isEqualTo(3);
    assertThat(incident(i1).affectedServices()).isEqualTo(I1_IMPACT);
  }

  @Test
  @Order(18)
  @DisplayName("DM-R-06 matching done once at the decision; later questions from the current map")
  void matchingOnce() {
    String generation2 =
        CorrelationFixture.serviceMap()
            .lines()
            .filter(line -> !line.contains("sms-queue"))
            .collect(Collectors.joining("\n", "", "\n"));
    CorrelationFixture.importServiceMap(app, a.tenant, generation2);
    assertThat(decision(s).componentKey()).isEqualTo("sms-queue");
    assertThat(member(i1, s).alert().componentKey()).isEqualTo("sms-queue");
    r2 = zabbix("sms-queue", "lab-vas-01", "SMS queue growing on route r2");
    GroupingDecisionView created = decision(r2);
    assertThat(created.componentKey()).isEqualTo("srv-vas-01");
    assertThat(created.generation()).isEqualTo(2);
    assertThat(created.decision()).isEqualTo(Decision.CREATED_INCIDENT);
    assertThat(created.incidentId()).isNotIn(i1, i2, iu, iv);
  }

  @Test
  @Order(19)
  @DisplayName("DM-R-21 a new alert related to a resolved incident within its window reopens it")
  void reopenRule() {
    a.resolve(i1);
    assertThat(incident(i1).state()).isEqualTo(IncidentState.RESOLVED);
    balance1 = alertmanager("api-balance", "api-balance-1", "BalanceApiErrors");
    GroupingDecisionView reopened = decision(balance1);
    assertThat(reopened.incidentId()).isEqualTo(i1);
    IncidentView incident = incident(i1);
    assertThat(incident.state()).isEqualTo(IncidentState.OPEN);
    assertThat(incident.reopenCount()).isEqualTo(1);
    assertReason(
        reopened, d, GroupingRelation.DEPENDS_ON, List.of("api-balance", "db-subscriber"), 40);
    assertThat(reason(member(i1, balance1).finalReasonJson()))
        .isEqualTo(reason(d, "depends_on", List.of("api-balance", "db-subscriber"), 40));
    a.resolve(i1);
    CLOCK.advance(Duration.ofSeconds(301));
    balance2 = alertmanager("api-balance", "api-balance-2", "BalanceApiErrors");
    GroupingDecisionView created = decision(balance2);
    assertThat(created.decision()).isEqualTo(Decision.CREATED_INCIDENT);
    assertThat(created.incidentId()).isNotEqualTo(i1);
  }

  @Test
  @Order(20)
  @DisplayName("PM-M08-08 the reopen transition requested by the reopen rule")
  void reopenRequested() {
    assertThat(decision(balance1).decision()).isEqualTo(Decision.REOPENED);
    assertThat(incidents.memberships(a.tenant, i1).stream().map(MembershipView::alertId))
        .contains(balance1.id());
  }

  @Test
  @Order(21)
  @DisplayName("SL-B-12 the reopen transition requested by vigil-correlation after resolution")
  void reopenTransition() {
    List<TransitionView> transitions = incidents.transitions(a.tenant, i1);
    int resolved =
        transitions.indexOf(
            transitions.stream()
                .filter(t -> t.transitionId().equals("DM-SM05-T4"))
                .findFirst()
                .orElseThrow());
    TransitionView reopen = transitions.get(resolved + 1);
    assertThat(reopen.transitionId()).isEqualTo("DM-SM05-T5");
    assertThat(reopen.actor()).isEqualTo(Actor.module("vigil-correlation"));
    assertThat(reopen.toState()).isEqualTo(IncidentState.OPEN);
  }

  @Test
  @Order(22)
  @DisplayName("DM-R-61 an alert read again is not decided again")
  void atMostOneDecision() {
    long last = correlation.cursor(a.tenant);
    postgres
        .sql("UPDATE correlation.cursor SET last_sequence = :s WHERE tenant_id = :t")
        .param("s", last - 1)
        .param("t", a.tenant.value())
        .update();
    assertThat(correlation.cursor(a.tenant)).isEqualTo(last - 1);
    CorrelationFixture.work(app).wake(a.tenant);
    CorrelationFixture.await(() -> correlation.cursor(a.tenant) == last, CorrelationFixture.WAIT);
    long decisions =
        postgres
            .sql(
                "SELECT count(*) FROM correlation.grouping_decision"
                    + " WHERE tenant_id = :t AND alert_id = :a")
            .param("t", a.tenant.value())
            .param("a", balance2.id())
            .query(Long.class)
            .single();
    assertThat(decisions).isEqualTo(1);
  }

  @Test
  @Order(23)
  @DisplayName("PM-M08-10 the alerts whose matched component is a given component")
  void alertsOfComponent() {
    assertThat(correlation.alertsOfComponent(a.tenant, "sms-queue")).containsExactly(s.id());
    assertThat(correlation.alertsOfComponent(b.tenant, "sms-queue")).isEmpty();
  }

  @Test
  @Order(24)
  @DisplayName("DM-E08-R3 alert_grouped once per decision and hypothesis_changed once per change")
  void auditEvents() {
    Map<UUID, String> expected = new HashMap<>();
    for (AlertView alert : posted) {
      GroupingDecisionView decision = decision(alert);
      expected.put(
          decision.id(),
          CorrelationTexts.alertGrouped(
              alert.title(),
              alert.sourceKind().key(),
              decision.decision(),
              decision.mergedIncidentIds().size()));
    }
    assertThat(expected.get(decision(d).id())).endsWith("joined the incident; 1 group merged.");
    Map<UUID, String> grouped = new HashMap<>();
    postgres
        .sql(
            "SELECT subject_id, summary, subject_type, actor_module FROM audit.audit_event"
                + " WHERE tenant_id = :t AND event_type = 'alert_grouped'")
        .param("t", a.tenant.value())
        .query()
        .listOfRows()
        .forEach(
            row -> {
              assertThat(row.get("subject_type")).isEqualTo("GroupingDecision");
              assertThat(row.get("actor_module")).isEqualTo("vigil-correlation");
              grouped.put((UUID) row.get("subject_id"), (String) row.get("summary"));
            });
    assertThat(audit(a.tenant, "alert_grouped")).isEqualTo(posted.size());
    assertThat(grouped).isEqualTo(expected);

    Map<UUID, List<String>> changes = new HashMap<>();
    postgres
        .sql(
            "SELECT incident_id, summary FROM audit.audit_event"
                + " WHERE tenant_id = :t AND event_type = 'hypothesis_changed'"
                + " ORDER BY sequence")
        .param("t", a.tenant.value())
        .query()
        .listOfRows()
        .forEach(
            row ->
                changes
                    .computeIfAbsent((UUID) row.get("incident_id"), id -> new ArrayList<>())
                    .add((String) row.get("summary")));
    assertThat(audit(a.tenant, "hypothesis_changed")).isEqualTo(6);
    assertThat(changes.get(i1))
        .containsExactly(
            "Hypothesis: none → SMS queue.",
            "Hypothesis: SMS queue → Subscriber database.",
            "Hypothesis: Subscriber database → Subscriber DB server.");
    assertThat(changes.get(i2)).containsExactly("Hypothesis: none → Bundle-purchase API.");
    assertThat(changes.get(decision(r2).incidentId()))
        .containsExactly("Hypothesis: none → VAS platform server.");
    assertThat(changes.get(decision(balance2).incidentId()))
        .containsExactly("Hypothesis: none → Balance-check API.");
    assertThat(audit(b.tenant, "alert_grouped")).isZero();
    assertThat(audit(b.tenant, "hypothesis_changed")).isZero();
  }
}
