package vigil.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vigil.correlation.api.CorrelationService;
import vigil.correlation.api.HypothesisComputationView;
import vigil.engine.EngineFixture;
import vigil.engine.TestContents;
import vigil.engine.api.RunState;
import vigil.engine.api.StepExecutionState;
import vigil.engine.api.StepExecutionView;
import vigil.incidents.api.AffectedServiceCopy;
import vigil.incidents.api.EvidenceKind;
import vigil.incidents.api.IncidentService;
import vigil.incidents.api.IncidentView;
import vigil.incidents.api.MembershipView;
import vigil.incidents.api.TimelineView;
import vigil.intake.api.AlertView;
import vigil.intake.api.IntakeService;
import vigil.routing.api.RoutingService;
import vigil.servicemap.api.ServiceMapService;
import vigil.shared.StepCall.StepType;
import vigil.shared.Waiting;
import vigil.testsupport.ApiClient.Response;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WorkspaceIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String INCIDENTS = "/api/v1/incidents/";
  private static final List<String> SECTIONS =
      List.of("", "/alerts", "/evidence", "/run", "/timeline", "/notified-teams");

  private ApiFixture fixture;
  private IncidentService incidents;
  private IntakeService intake;
  private CorrelationService correlation;
  private ApiFixture.Storm storm;
  private UUID incident;
  private UUID run;
  private AlertView resolvedAlert;

  @BeforeAll
  void start() {
    fixture = new ApiFixture();
    incidents = fixture.app.context().getBean(IncidentService.class);
    intake = fixture.app.context().getBean(IntakeService.class);
    correlation = fixture.app.context().getBean(CorrelationService.class);
    storm = fixture.storm();
    incident = storm.storm();
    fixture.acknowledge(incident, "dbops-engineer");
    fixture.escalate(incident);
    fixture.ticketLink(incident);
    fixture.diagnostic(incident);
    MembershipView member = incidents.members(fixture.tenant, incident).getFirst();
    resolvedAlert = intake.alert(fixture.tenant, member.alertId()).orElseThrow();
    fixture.resolveAtSource(resolvedAlert);
    fixture.handlers.answer(
        StepType.DIAGNOSTIC,
        c -> new Waiting(Set.of(), fixture.clock.instant().plusSeconds(600), "{}"));
    run = fixture.liveRun(incident, TestContents.version1());
    awaitWaiting(run);
    fixture.engine.awaitPublications();
  }

  @AfterAll
  void stop() {
    fixture.close();
  }

  /** {@code GET path} as {@code presenter}; the answer MUST be 200; its body. */
  private JsonNode get(String path) {
    Response response = fixture.as("presenter", fixture.client.get(path)).send();
    assertThat(response.status()).as(response.text()).isEqualTo(200);
    return response.json();
  }

  private void awaitWaiting(UUID runId) {
    EngineFixture.await(
        () ->
            fixture.engine.run(runId).state() == RunState.WAITING
                && !fixture.engine.engine.pendingTimers(fixture.tenant, runId).isEmpty());
  }

  private JsonNode get(UUID id, String section) {
    return get(INCIDENTS + id + section);
  }

  private IncidentView view(UUID id) {
    return incidents.incident(fixture.tenant, id).orElseThrow();
  }

  private static List<String> texts(JsonNode array, String field) {
    List<String> values = new ArrayList<>();
    array.forEach(item -> values.add(item.get(field).stringValue()));
    return values;
  }

  private void notFound(String path) {
    Response response = fixture.as("presenter", fixture.client.get(path)).send();
    assertThat(response.status()).as(path).isEqualTo(404);
    assertThat(response.json().get("error").get("code").stringValue()).isEqualTo("not_found");
  }

  @Test
  @Order(1)
  @DisplayName("AR-I-22 the header, hypothesis and impact of the storm incident")
  void header() {
    IncidentView view = view(incident);
    JsonNode header = get(incident, "");
    assertThat(header.size()).isEqualTo(18);
    assertThat(header.get("incident_id").stringValue()).isEqualTo(incident.toString());
    assertThat(header.get("number").longValue()).isEqualTo(view.number());
    assertThat(header.get("title").stringValue()).isEqualTo(view.title());
    assertThat(header.get("state").stringValue()).isEqualTo("open");
    assertThat(header.get("stage").stringValue()).isEqualTo("investigation");
    assertThat(header.get("severity").stringValue()).isEqualTo("P1");
    assertThat(header.get("owner"))
        .isEqualTo(JSON.readTree("{\"team_key\":\"db-ops\",\"name\":\"Database Operations\"}"));
    assertThat(header.get("informed"))
        .isEqualTo(
            JSON.readTree(
                "[{\"team_key\":\"vas-service-owners\",\"name\":\"VAS Service Owners\"}]"));
    String rule =
        fixture
            .app
            .context()
            .getBean(RoutingService.class)
            .evaluation(fixture.tenant, view.assignmentEvaluationId())
            .orElseThrow()
            .matchedRuleName();
    assertThat(rule).isNotNull();
    assertThat(header.get("rule_name").stringValue()).isEqualTo(rule);
    JsonNode ack = header.get("acknowledged");
    assertThat(ack.get("user").get("user_id").stringValue())
        .isEqualTo(view.acknowledgedBy().toString());
    assertThat(ack.get("user").get("display_name").stringValue()).isEqualTo("Usman Tariq (DB Ops)");
    assertThat(ack.get("at").stringValue()).isEqualTo(ApiTimes.format(view.acknowledgedAt()));
    assertThat(header.get("escalation"))
        .isEqualTo(
            JSON.readTree(
                "{\"level\":1,\"teams\":[{\"team_key\":\"noc-shift-lead\","
                    + "\"name\":\"NOC Shift Lead\"}]}"));
    assertThat(header.get("first_alert_fired_at").stringValue())
        .isEqualTo(ApiTimes.format(view.firstAlertFiredAt()));
    assertThat(header.get("resolved_at").isNull()).isTrue();
    assertThat(header.get("ticket").get("key").stringValue()).isEqualTo("NOC-1");
    assertThat(header.get("ticket").get("url").stringValue())
        .isEqualTo("http://127.0.0.1:" + fixture.wiremock.port() + "/browse/NOC-1");
    assertThat(header.get("reopen_count").longValue()).isZero();
    assertThat(header.get("alert_count").longValue()).isEqualTo(view.alertCount());

    HypothesisComputationView computation =
        correlation.latestHypothesisComputation(fixture.tenant, incident).orElseThrow();
    JsonNode hypothesis = header.get("hypothesis");
    assertThat(hypothesis.get("component_key").stringValue()).isEqualTo("srv-db-01");
    assertThat(hypothesis.get("name").stringValue()).isEqualTo(view.hypothesis().name());
    assertThat(hypothesis.get("kind").stringValue()).isEqualTo(view.hypothesis().kind());
    assertThat(
            hypothesis.get("criticality").isNull()
                ? null
                : hypothesis.get("criticality").stringValue())
        .isEqualTo(view.hypothesis().criticality());
    assertThat(hypothesis.get("other_affected_count").longValue()).isEqualTo(6);
    assertThat(hypothesis.get("dependent_count").longValue()).isEqualTo(6);
    assertThat(hypothesis.get("tie_break").stringValue()).isEqualTo(computation.tieBreak().key());

    List<AffectedServiceCopy> services = view.affectedServices();
    JsonNode affected = header.get("affected_services");
    assertThat(texts(affected, "service_key"))
        .containsExactly("svc-balance-check", "svc-bundle-purchase", "svc-sms-notifications");
    for (int i = 0; i < services.size(); i++) {
      AffectedServiceCopy service = services.get(i);
      JsonNode answer = affected.get(i);
      assertThat(answer.get("name").stringValue()).isEqualTo(service.name());
      assertThat(answer.get("via")).hasSize(service.viaComponentKeys().size());
      assertThat(texts(answer.get("via"), "component_key"))
          .containsExactlyInAnyOrderElementsOf(service.viaComponentKeys());
      List<String> names = texts(answer.get("via"), "name");
      assertThat(names).isSorted();
      for (JsonNode via : answer.get("via")) {
        assertThat(via.get("name").stringValue())
            .isEqualTo(componentName(via.get("component_key").stringValue()));
      }
    }

    UUID merged = storm.merged().getFirst();
    assertThat(get(merged, ""))
        .isEqualTo(
            JSON.readTree(
                "{\"merged_into_incident_id\":\"" + view(merged).mergedIntoIncidentId() + "\"}"));
    notFound(INCIDENTS + fixture.formingIncident());
    notFound(INCIDENTS + UUID.randomUUID());
    notFound(INCIDENTS + "x");
  }

  private String componentName(String key) {
    return fixture
        .app
        .context()
        .getBean(ServiceMapService.class)
        .component(fixture.tenant, key)
        .orElseThrow()
        .name();
  }

  @Test
  @Order(2)
  @DisplayName("AR-I-23 the member alerts by type, in fired order, with status and final reason")
  void alerts() {
    JsonNode answer = get(incident, "/alerts");
    List<MembershipView> members = incidents.members(fixture.tenant, incident);
    assertThat(answer.get("firing").longValue()).isEqualTo(members.size() - 1L);
    assertThat(answer.get("resolved").longValue()).isEqualTo(1);
    assertThat(answer.get("grouping_window_seconds").longValue()).isEqualTo(300);

    Map<String, List<MembershipView>> byName = new TreeMap<>();
    members.forEach(
        m -> byName.computeIfAbsent(m.alert().alertTypeName(), n -> new ArrayList<>()).add(m));
    JsonNode groups = answer.get("groups");
    assertThat(texts(groups, "alert_type_name")).containsExactlyElementsOf(byName.keySet());
    Map<UUID, MembershipView> byAlert = new java.util.HashMap<>();
    members.forEach(m -> byAlert.put(m.alertId(), m));
    int checkedMoved = 0;
    int checkedRelated = 0;
    for (JsonNode group : groups) {
      List<MembershipView> expected =
          byName.get(group.get("alert_type_name").stringValue()).stream()
              .sorted(
                  Comparator.comparing((MembershipView m) -> m.alert().firedAt())
                      .thenComparing(m -> m.alert().sourceKind())
                      .thenComparing(m -> m.alert().alertKey()))
              .toList();
      assertThat(group.get("count").longValue()).isEqualTo(expected.size());
      assertThat(group.get("alert_type").stringValue())
          .isEqualTo(expected.getFirst().alert().alertType());
      assertThat(texts(group.get("alerts"), "alert_id"))
          .containsExactlyElementsOf(expected.stream().map(m -> m.alertId().toString()).toList());
      for (JsonNode alert : group.get("alerts")) {
        MembershipView member = byAlert.get(UUID.fromString(alert.get("alert_id").stringValue()));
        assertThat(alert.get("fired_at").stringValue())
            .isEqualTo(ApiTimes.format(member.alert().firedAt()));
        assertThat(alert.get("source_kind").stringValue()).isEqualTo(member.alert().sourceKind());
        assertThat(alert.get("title").stringValue()).isEqualTo(member.alert().title());
        assertThat(member.alert().componentKey()).isNotNull();
        assertThat(alert.get("component_name").stringValue())
            .isEqualTo(componentName(member.alert().componentKey()));
        assertThat(alert.get("status").stringValue())
            .isEqualTo(member.alertId().equals(resolvedAlert.id()) ? "resolved" : "firing");
        JsonNode stored = JSON.readTree(member.finalReasonJson());
        JsonNode reason = alert.get("reason");
        assertThat(reason.get("relation").stringValue())
            .isEqualTo(stored.get("relation").stringValue());
        assertThat(reason.get("reopened").booleanValue()).isFalse();
        assertThat(reason.get("identity").isNull()).isTrue();
        if (stored.get("relation").stringValue().equals("only_alert")) {
          assertThat(reason.get("related").isNull()).isTrue();
          continue;
        }
        AlertView related =
            intake
                .alert(
                    fixture.tenant, UUID.fromString(stored.get("related_alert_id").stringValue()))
                .orElseThrow();
        assertThat(reason.get("related").get("title").stringValue()).isEqualTo(related.title());
        assertThat(reason.get("related").get("source_kind").stringValue())
            .isEqualTo(related.sourceKind().key());
        assertThat(texts(reason.get("path"), "component_key"))
            .containsExactlyElementsOf(texts(stored.get("relation_path")));
        for (JsonNode step : reason.get("path")) {
          assertThat(step.get("name").stringValue())
              .isEqualTo(componentName(step.get("component_key").stringValue()));
        }
        assertThat(reason.get("time_delta_seconds").longValue())
            .isEqualTo(stored.get("time_delta").longValue());
        checkedRelated++;
        JsonNode decision = JSON.readTree(member.reasonJson()).get("decision");
        if (decision != null && decision.stringValue().equals("moved_by_merge")) {
          checkedMoved++;
        }
      }
    }
    assertThat(checkedRelated).isGreaterThan(100);
    assertThat(checkedMoved).as("members moved in by a merge").isPositive();
  }

  private static List<String> texts(JsonNode array) {
    List<String> values = new ArrayList<>();
    array.forEach(item -> values.add(item.stringValue()));
    return values;
  }

  @Test
  @Order(3)
  @DisplayName("AR-I-24 the evidence in collected order with the diagnostic's output values")
  void evidence() {
    fixture.clock.advance(Duration.ofSeconds(1));
    fixture.evidence(incident, EvidenceKind.RECOVERY_VERIFICATION, "vigil-recovery");
    JsonNode entries = get(incident, "/evidence").get("entries");
    var stored = incidents.evidence(fixture.tenant, incident);
    assertThat(entries).hasSize(2);
    assertThat(texts(entries, "evidence_entry_id"))
        .containsExactly(stored.get(0).id().toString(), stored.get(1).id().toString());
    JsonNode diagnostic = entries.get(0);
    assertThat(diagnostic.get("kind").stringValue()).isEqualTo("diagnostic_result");
    assertThat(diagnostic.get("summary").stringValue()).isEqualTo(stored.get(0).summary());
    assertThat(diagnostic.get("source_system").stringValue())
        .isEqualTo(stored.get(0).sourceSystem());
    assertThat(diagnostic.get("collected_at").stringValue())
        .isEqualTo(ApiTimes.format(stored.get(0).collectedAt()));
    assertThat(diagnostic.get("output_values"))
        .isEqualTo(
            JSON.readTree(
                "[{\"name\":\"use_pct\",\"value\":\"100\"},{\"name\":\"free_gib\",\"value\":\"0.1\"}]"));
    JsonNode recovery = entries.get(1);
    assertThat(recovery.get("kind").stringValue()).isEqualTo("recovery_verification");
    assertThat(recovery.get("summary").stringValue()).isEqualTo("Entry of vigil-recovery");
    assertThat(recovery.get("collected_at").stringValue())
        .isEqualTo(ApiTimes.format(stored.get(1).collectedAt()));
    assertThat(stored.get(1).collectedAt()).isEqualTo(stored.get(0).collectedAt().plusSeconds(1));
    assertThat(recovery.get("output_values").isNull()).isTrue();
  }

  @Test
  @Order(4)
  @DisplayName("AR-I-29 the latest live run with its step executions, timers, teams and number")
  void latestRun() {
    assertThat(get(storm.report(), "/run")).isEqualTo(JSON.readTree("{\"run\":null}"));
    fixture.testRun(incident);
    JsonNode answer = get(incident, "/run").get("run");
    assertThat(answer.size()).isEqualTo(8);
    assertThat(answer.get("run_id").stringValue()).isEqualTo(run.toString());
    assertThat(answer.get("state").stringValue()).isEqualTo("waiting");
    assertThat(answer.get("run_number").longValue()).isEqualTo(1);
    assertThat(answer.get("run_count").longValue()).isEqualTo(1);
    assertThat(answer.get("content")).isEqualTo(JSON.readTree(TestContents.version1()));
    assertThat(answer.get("team_names"))
        .isEqualTo(
            JSON.readTree(
                "[{\"team_key\":\"db-ops\",\"name\":\"Database Operations\"},"
                    + "{\"team_key\":\"noc-l1\",\"name\":\"NOC L1\"}]"));
    List<StepExecutionView> executions = fixture.engine.executions(run);
    JsonNode steps = answer.get("step_executions");
    assertThat(texts(steps, "step_id")).containsExactly("s-trigger", "s-severity", "s-diagnose");
    StepExecutionView waiting = executions.getLast();
    assertThat(waiting.state()).isEqualTo(StepExecutionState.WAITING);
    assertThat(answer.get("current_step_execution_id").stringValue())
        .isEqualTo(waiting.id().toString());
    for (int i = 0; i < executions.size(); i++) {
      StepExecutionView execution = executions.get(i);
      JsonNode step = steps.get(i);
      assertThat(step.get("step_execution_id").stringValue()).isEqualTo(execution.id().toString());
      assertThat(step.get("step_type").stringValue()).isEqualTo(execution.stepType().key());
      assertThat(step.get("visit").longValue()).isEqualTo(execution.visit());
      assertThat(step.get("state").stringValue()).isEqualTo(execution.state().key());
      assertThat(step.get("started_at").stringValue())
          .isEqualTo(ApiTimes.format(execution.startedAt()));
      if (execution.namedOutput() == null) {
        assertThat(step.get("named_output").isNull()).isTrue();
      } else {
        assertThat(step.get("named_output").stringValue()).isEqualTo(execution.namedOutput());
      }
    }
    assertThat(steps.get(0).get("timer_due_at").isNull()).isTrue();
    assertThat(steps.get(1).get("named_output").stringValue()).isEqualTo("p1");
    assertThat(steps.get(2).get("timer_due_at").stringValue())
        .isEqualTo(
            ApiTimes.format(
                fixture.engine.engine.pendingTimers(fixture.tenant, run).getFirst().dueAt()));

    UUID second = fixture.reopenRun(incident, TestContents.version1());
    awaitWaiting(second);
    JsonNode reopened = get(incident, "/run").get("run");
    assertThat(reopened.get("run_id").stringValue()).isEqualTo(second.toString());
    assertThat(reopened.get("run_number").longValue()).isEqualTo(2);
    assertThat(reopened.get("run_count").longValue()).isEqualTo(2);
  }

  @Test
  @Order(5)
  @DisplayName("AR-I-30 the timeline newest first with actors and the stable grouping")
  void timeline() {
    IncidentView view = view(incident);
    JsonNode answer = get(incident, "/timeline");
    List<TimelineView> stored = new ArrayList<>(incidents.timeline(fixture.tenant, incident));
    java.util.Collections.reverse(stored);
    JsonNode entries = answer.get("entries");
    assertThat(texts(entries, "timeline_entry_id"))
        .containsExactlyElementsOf(stored.stream().map(e -> e.id().toString()).toList());
    boolean user = false;
    boolean module = false;
    for (int i = 0; i < stored.size(); i++) {
      TimelineView entry = stored.get(i);
      JsonNode item = entries.get(i);
      assertThat(item.get("at").stringValue()).isEqualTo(ApiTimes.format(entry.at()));
      assertThat(item.get("entry_type").stringValue()).isEqualTo(entry.entryType().key());
      assertThat(item.get("text").stringValue()).isEqualTo(entry.text());
      JsonNode actor = item.get("actor");
      if (entry.actor().userId() != null) {
        assertThat(actor)
            .isEqualTo(
                JSON.readTree(
                    "{\"kind\":\"user\",\"user_id\":\""
                        + entry.actor().userId()
                        + "\",\"display_name\":\"Usman Tariq (DB Ops)\"}"));
        user = true;
      } else {
        assertThat(actor)
            .isEqualTo(
                JSON.readTree("{\"kind\":\"module\",\"user_id\":null,\"display_name\":null}"));
        module = true;
      }
    }
    assertThat(user).isTrue();
    assertThat(module).isTrue();
    assertThat(stored.getFirst().at()).isAfterOrEqualTo(stored.getLast().at());

    JsonNode stable = answer.get("stable_grouping");
    assertThat(view.stableAt()).isNotNull();
    assertThat(stable.get("stable_at").stringValue()).isEqualTo(ApiTimes.format(view.stableAt()));
    long memberships =
        fixture
            .postgres
            .sql(
                "SELECT count(*) FROM incidents.incident_alert_membership WHERE tenant_id = :t"
                    + " AND incident_id = :i AND joined_at <= :s")
            .param("t", fixture.tenant.value())
            .param("i", incident)
            .param("s", java.sql.Timestamp.from(view.stableAt()))
            .query(Long.class)
            .single();
    long merges =
        fixture
            .postgres
            .sql(
                "SELECT count(*) FROM incidents.timeline_entry WHERE tenant_id = :t"
                    + " AND incident_id = :i AND entry_type = 'merged_in' AND at <= :s")
            .param("t", fixture.tenant.value())
            .param("i", incident)
            .param("s", java.sql.Timestamp.from(view.stableAt()))
            .query(Long.class)
            .single();
    assertThat(merges).isPositive();
    assertThat(stable.get("alert_count").longValue()).isEqualTo(memberships);
    assertThat(stable.get("merged_group_count").longValue()).isEqualTo(merges);
    assertThat(stable.get("hypothesis_name").stringValue()).isEqualTo("Subscriber DB server");
  }

  @Test
  @Order(6)
  @DisplayName("AR-I-31 the notified teams in time order, then name order, with purpose")
  void notifiedTeams() {
    fixture.notified(incident, List.of("noc-l1"));
    fixture.clock.advance(Duration.ofSeconds(1));
    fixture.notified(incident, List.of("vas-service-owners", "customer-care"));
    JsonNode teams = get(incident, "/notified-teams").get("teams");
    assertThat(texts(teams, "team_key"))
        .containsExactly("db-ops", "noc-l1", "customer-care", "vas-service-owners");
    assertThat(texts(teams, "name"))
        .containsExactly("Database Operations", "NOC L1", "Customer Care", "VAS Service Owners");
    assertThat(texts(teams, "purpose")).containsOnly("incident_notification");
    assertThat(teams.get(2).get("first_notified_at"))
        .isEqualTo(teams.get(3).get("first_notified_at"));
    assertThat(teams.get(2).get("first_notified_at").stringValue())
        .isEqualTo(ApiTimes.format(fixture.clock.instant()));
  }

  @Test
  @Order(7)
  @DisplayName("AR-I-07 every operation needs view: 403 without it, 401 without a session")
  void permission() {
    List<String> paths = new ArrayList<>(List.of("/api/v1/alerts/strip", "/api/v1/incidents"));
    SECTIONS.forEach(section -> paths.add(INCIDENTS + incident + section));
    assertThat(paths).hasSize(8);
    for (String path : paths) {
      Response forbidden = fixture.as("noc-l1", fixture.client.get(path)).send();
      assertThat(forbidden.status()).as(path).isEqualTo(403);
      assertThat(forbidden.json())
          .isEqualTo(
              JSON.readTree(
                  "{\"error\":{\"code\":\"forbidden\","
                      + "\"message\":\"You do not have permission to do this.\"}}"));
      Response anonymous = fixture.client.get(path).host(ApiFixture.HOST).send();
      assertThat(anonymous.status()).as(path).isEqualTo(401);
    }
  }
}
