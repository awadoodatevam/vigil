package vigil.actions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.JsonNode;
import vigil.actions.api.ActionsService;
import vigil.actions.api.ActionsStepHandler;
import vigil.actions.api.Decision;
import vigil.audit.api.AuditEventType;
import vigil.audit.api.AuditEventView;
import vigil.incidents.api.EvidenceKind;
import vigil.incidents.api.EvidenceView;
import vigil.incidents.api.NotifiedPurpose;
import vigil.notifications.NotificationsApp;
import vigil.notifications.NotificationsFixture;
import vigil.notifications.api.NotificationRecordView;
import vigil.shared.Actor;
import vigil.shared.Completed;
import vigil.shared.Completed.ResultRef;
import vigil.shared.Ids;
import vigil.shared.Key;
import vigil.shared.StepAnswer;
import vigil.shared.StepCall;
import vigil.shared.Waiting;
import vigil.testsupport.MutableClock;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApprovalStepIT {

  static final Instant T0 = Instant.parse("2026-11-12T09:00:00Z");
  static final MutableClock CLOCK = new MutableClock(T0);
  static final String APPROVAL = ActionsFixture.APPROVAL;
  static final String ACTION_NAME = "Purge archived transaction logs";
  static final String ON = ACTION_NAME + " on Subscriber DB server.";
  static final Actor ACTIONS = Actor.module("vigil-actions");

  /** The test clock. */
  static class TestBeans {
    @Bean
    @Primary
    Clock testClock() {
      return CLOCK;
    }
  }

  private NotificationsApp servers;
  private ActionsStepHandler step;
  private ActionsService actions;
  private ActionsFixture demo;
  private ActionsFixture preauthorized;
  private ActionsFixture blocked;
  private ActionsFixture limitTwo;
  private ActionsFixture mixed;

  @BeforeAll
  void start() {
    servers = NotificationsApp.start(TestBeans.class);
    step = servers.app.context().getBean(ActionsStepHandler.class);
    actions = servers.app.context().getBean(ActionsService.class);
    demo = ActionsFixture.create(servers, "Approve");
    preauthorized =
        ActionsFixture.create(
            servers,
            "Preauthorized",
            ActionsFixture.part(1, ActionsFixture.policy(null, "preauthorized")));
    blocked =
        ActionsFixture.create(
            servers,
            "Blocked",
            ActionsFixture.part(
                1,
                ActionsFixture.policy(null, "approval_required"),
                ActionsFixture.policy("svc-balance-check", "blocked")));
    limitTwo = ActionsFixture.create(servers, "Limit two", ActionsFixture.part(2));
    mixed =
        ActionsFixture.create(
            servers,
            "Mixed",
            ActionsFixture.part(
                1,
                ActionsFixture.policy(null, "preauthorized"),
                ActionsFixture.policy("svc-balance-check", "approval_required")));
  }

  @AfterAll
  void stop() {
    servers.close();
  }

  @BeforeEach
  void reset() {
    servers.reset();
    CLOCK.set(T0);
  }

  /** An open incident with the PI-1001 result, a run and the first Approval step call. */
  record Case(ActionsFixture f, UUID incident, UUID run, StepCall call) {}

  private Case story(ActionsFixture f) {
    UUID incident = f.openIncident();
    f.match(incident);
    UUID run = Ids.newId();
    return new Case(f, incident, run, f.approval(run, incident, APPROVAL, List.of()));
  }

  private StepAnswer again(Case c) {
    return again(c, c.call());
  }

  private StepAnswer again(Case c, StepCall call) {
    return step.evaluate(
        c.f()
            .approval(
                c.run(),
                c.incident(),
                call.stepExecutionId(),
                call.parameters(),
                call.earlierApprovers(),
                "event:approval_decided"));
  }

  private Map<String, Object> request(Case c) {
    List<Map<String, Object>> rows = c.f().rowsOfRun("approval_request", c.run());
    assertThat(rows).hasSize(1);
    return rows.getFirst();
  }

  private Map<String, Object> request(Case c, StepCall call) {
    return c.f().rowsOfRun("approval_request", c.run()).stream()
        .filter(r -> r.get("step_execution_id").equals(call.stepExecutionId()))
        .findFirst()
        .orElseThrow();
  }

  private UUID id(Map<String, Object> row) {
    return (UUID) row.get("id");
  }

  private List<NotificationRecordView> messages(Case c) {
    return servers.records(c.f().notifications, c.incident()).stream()
        .filter(r -> r.purpose() == NotifiedPurpose.APPROVAL_REQUEST)
        .toList();
  }

  private void decide(Case c, String user, Decision decision) {
    Map<String, Object> request = request(c);
    actions.decide(
        c.f().tenant,
        id(request),
        c.f().userId(user),
        decision,
        (String) request.get("snapshot_hash"));
  }

  private static Completed completed(StepAnswer answer, String output) {
    assertThat(answer).isInstanceOf(Completed.class);
    Completed completed = (Completed) answer;
    assertThat(completed.namedOutput()).isEqualTo(new Key(output));
    return completed;
  }

  private static JsonNode json(Object value) {
    return ActionsFixture.JSON.readTree(value.toString());
  }

  private void assertWaiting(StepAnswer answer) {
    assertThat(answer).isInstanceOf(Waiting.class);
    Waiting waiting = (Waiting) answer;
    assertThat(waiting.wakeEvents()).containsExactly(Waiting.WakeEvent.APPROVAL_DECIDED);
    assertThat(waiting.timerDueAt()).isEqualTo(T0.plusSeconds(900));
  }

  @Test
  @DisplayName("SL-A1-06 proposal, approval request with snapshot and messages, approval")
  void storyline() {
    Case c = story(demo);
    assertWaiting(step.start(c.call()));
    Map<String, Object> proposal = demo.rowsOfRun("action_proposal", c.run()).getFirst();
    assertThat(proposal.get("action_key")).isEqualTo("purge-archived-txlogs");
    assertThat(proposal.get("component_key")).isEqualTo("srv-db-01");
    assertThat(proposal.get("target_key")).isEqualTo("lab-db-01");
    assertThat(proposal.get("past_incident_key")).isEqualTo("PI-1001");
    assertThat((BigDecimal) proposal.get("score")).isEqualByComparingTo("1.00");
    assertThat(proposal.get("success_count")).isEqualTo(4L);
    Map<String, Object> request = request(c);
    assertThat(request.get("state")).isEqualTo("pending");
    assertThat(request.get("sequence_in_run")).isEqualTo(1L);
    assertThat(request.get("policy_outcome")).isEqualTo("approval_required");
    assertThat((String) request.get("snapshot_hash")).hasSize(64);
    List<NotificationRecordView> messages = messages(c);
    assertThat(messages)
        .extracting(r -> r.recipientKey() + " " + r.channel().key() + " " + r.viaTeamKey())
        .containsExactlyInAnyOrder(
            "dbops-engineer teams db-ops",
            "dbops-engineer email db-ops",
            "dbops-lead teams db-ops",
            "dbops-lead email db-ops");
    assertThat(messages).allMatch(r -> r.originId().equals(id(request)));
    String[] lines = messages.getFirst().content().split("\n");
    assertThat(lines[0]).contains("Approval needed: " + ACTION_NAME + " on Subscriber DB server");
    assertThat(lines[2]).isEqualTo("Expires: 2026-11-12 09:15:00 UTC");
    decide(c, "dbops-engineer", Decision.APPROVE);
    assertThat(request(c).get("state")).isEqualTo("approved");
    Completed done = completed(again(c), "approved");
    assertThat(done.resultRef())
        .isEqualTo(new ResultRef("vigil-actions", "ApprovalRequest", id(request)));
  }

  @Test
  @DisplayName("DM-E-58 the approval request row")
  void requestRow() {
    Case c = story(demo);
    step.start(c.call());
    Map<String, Object> r = request(c);
    UUID proposal = (UUID) demo.rowsOfRun("action_proposal", c.run()).getFirst().get("id");
    assertThat(r.get("incident_id")).isEqualTo(c.incident());
    assertThat(r.get("run_id")).isEqualTo(c.run());
    assertThat(r.get("step_execution_id")).isEqualTo(c.call().stepExecutionId());
    assertThat(r.get("proposal_id")).isEqualTo(proposal);
    assertThat(r.get("sequence_in_run")).isEqualTo(1L);
    assertThat(r.get("policy_outcome")).isEqualTo("approval_required");
    assertThat(json(r.get("eligible_team_keys")).toString()).isEqualTo("[\"db-ops\"]");
    assertThat(json(r.get("excluded_user_ids")).toString()).isEqualTo("[]");
    assertThat(json(r.get("snapshot")).get("action_key").asString())
        .isEqualTo("purge-archived-txlogs");
    assertThat(r.get("snapshot_hash"))
        .isEqualTo(vigil.shared.CanonicalJson.sha256HexOfCanonical(r.get("snapshot").toString()));
    assertThat(((java.sql.Timestamp) r.get("expires_at")).toInstant())
        .isEqualTo(T0.plusSeconds(900));
    assertThat(r.get("state")).isEqualTo("pending");
    assertThat(r.get("refusal_reason")).isNull();
    assertThat(r.get("decided_at")).isNull();
    assertThat(r.get("decided_by")).isNull();
  }

  @Test
  @DisplayName("DM-E-57 the action proposal row")
  void proposalRow() {
    Case c = story(demo);
    step.start(c.call());
    List<Map<String, Object>> rows = demo.rowsOfRun("action_proposal", c.run());
    assertThat(rows).hasSize(1);
    Map<String, Object> p = rows.getFirst();
    assertThat(p.get("incident_id")).isEqualTo(c.incident());
    assertThat(p.get("action_key")).isEqualTo("purge-archived-txlogs");
    assertThat(p.get("component_key")).isEqualTo("srv-db-01");
    assertThat(p.get("target_key")).isEqualTo("lab-db-01");
    assertThat(p.get("similarity_result_id"))
        .isEqualTo(demo.knowledge.latestSearchResult(demo.tenant, c.incident()).orElseThrow().id());
    assertThat(p.get("past_incident_key")).isEqualTo("PI-1001");
    assertThat((BigDecimal) p.get("score")).isEqualByComparingTo("1.00");
    assertThat(p.get("success_count")).isEqualTo(4L);
    JsonNode snapshot = json(p.get("action_snapshot"));
    assertThat(snapshot.propertyNames())
        .containsExactlyInAnyOrder(
            "action_key",
            "name",
            "operation_key",
            "parameter_values",
            "limits",
            "attempt_limit",
            "precondition",
            "recovery_check_key");
    assertThat(snapshot.get("name").asString()).isEqualTo(ACTION_NAME);
    assertThat(snapshot.get("operation_key").asString()).isEqualTo("purge_old_archives");
    assertThat(snapshot.get("parameter_values").get("max_bytes").asLong()).isEqualTo(12884901888L);
    assertThat(snapshot.get("limits")).hasSize(4);
    assertThat(snapshot.get("attempt_limit").asLong()).isEqualTo(1);
    assertThat(snapshot.get("precondition"))
        .isEqualTo(
            json(
                "{\"operation_key\":\"fs_used_pct\",\"parameters\":{\"mount\":\"/data\"},"
                    + "\"result_field\":\"value\",\"operator\":\"greater_or_equal\",\"value\":\"90\"}"));
    assertThat(snapshot.get("recovery_check_key").asString()).isEqualTo("rc-subscriber-db");
  }

  @Test
  @DisplayName("PM-M14-06 the snapshot holds the seven parts of SL-A1-06")
  void snapshotParts() {
    Case c = story(demo);
    step.start(c.call());
    JsonNode s = json(request(c).get("snapshot"));
    assertThat(s.get("action_name").asString()).isEqualTo(ACTION_NAME);
    assertThat(s.get("parameters"))
        .isEqualTo(
            json(
                "{\"path\":\"/data/txlog_archive\",\"older_than_minutes\":1440,"
                    + "\"max_bytes\":12884901888}"));
    assertThat(s.get("target_key").asString()).isEqualTo("lab-db-01");
    assertThat(s.get("target_name").asString()).isEqualTo("Subscriber DB server");
    assertThat(s.get("limits")).hasSize(4);
    assertThat(s.get("limits").get(0).asString())
        .isEqualTo("Deletes only *.arc files directly under /data/txlog_archive");
    assertThat(s.get("attempt_number").asLong()).isEqualTo(1);
    assertThat(s.get("attempt_limit").asLong()).isEqualTo(1);
    assertThat(s.get("expires_at").asString()).isEqualTo(T0.plusSeconds(900).toString());
    assertThat(s.get("evidence")).hasSize(1);
    assertThat(s.get("evidence").get(0).get("kind").asString()).isEqualTo("diagnostic_result");
    JsonNode basedOn = s.get("based_on");
    assertThat(basedOn.get("past_incident_key").asString()).isEqualTo("PI-1001");
    assertThat(basedOn.get("past_incident_title").asString())
        .isEqualTo("Subscriber DB /data full after archive catch-up run");
    assertThat(basedOn.get("record_label").asString()).isEqualTo("Fictional record");
    assertThat(basedOn.get("score").asString()).isEqualTo("1.00");
    assertThat(basedOn.get("success_count").asLong()).isEqualTo(4);
    JsonNode checks = s.get("recovery_checks");
    assertThat(checks.get("recovery_check_key").asString()).isEqualTo("rc-subscriber-db");
    assertThat(checks.get("name").asString()).isEqualTo("Subscriber DB services recovered");
    assertThat(checks.get("conditions")).hasSize(2);
    assertThat(checks.get("conditions").get(0).asString())
        .isEqualTo("Every alert of the incident is resolved at its source");
    assertThat(checks.get("conditions").get(1).asString())
        .isEqualTo("No alert of Synthetic USSD sessions is firing");
    assertThat(checks.get("observation_window").asLong()).isEqualTo(60);
  }

  @Test
  @DisplayName("PM-M14-04 messages to every eligible approver; none when no user qualifies")
  void recipients() {
    Case c = story(demo);
    step.start(c.call());
    assertThat(messages(c))
        .extracting(r -> r.recipientKey() + " " + r.channel().key())
        .containsExactlyInAnyOrder(
            "dbops-engineer teams", "dbops-engineer email", "dbops-lead teams", "dbops-lead email");
    assertThat(messages(c)).noneMatch(r -> r.recipientKey().equals("noc-l1"));
    assertThat(servers.teamsKeys()).containsExactlyInAnyOrder("dbops-engineer", "dbops-lead");

    servers.reset();
    UUID incident = demo.openIncident();
    demo.match(incident);
    UUID run = Ids.newId();
    StepCall call =
        demo.approval(
            run,
            incident,
            "{\"eligible_teams\": [\"noc-shift-lead\"], \"expiry\": 900}",
            List.of());
    assertWaiting(step.start(call));
    assertThat(servers.teamsPosts()).isEmpty();
    assertThat(servers.emails()).isEmpty();
    assertThat(demo.rowsOfRun("approval_request", run).getFirst().get("state"))
        .isEqualTo("pending");
  }

  @Test
  @DisplayName("PM-S-05 the outputs approved, rejected and expired")
  void outputs() {
    Case approved = story(demo);
    step.start(approved.call());
    decide(approved, "dbops-engineer", Decision.APPROVE);
    completed(again(approved), "approved");

    Case rejected = story(demo);
    step.start(rejected.call());
    decide(rejected, "dbops-lead", Decision.REJECT);
    completed(again(rejected), "rejected");
    assertThat(request(rejected).get("state")).isEqualTo("rejected");

    Case expired = story(demo);
    step.start(expired.call());
    CLOCK.set(T0.plusSeconds(900));
    completed(again(expired), "expired");
    assertThat(request(expired).get("state")).isEqualTo("expired");
  }

  @Test
  @DisplayName("DM-S-05 eligible_teams and expiry; a pending request waits for the event and timer")
  void parameters() {
    Case c = story(demo);
    for (String parameters :
        List.of(
            "{\"eligible_teams\": [], \"expiry\": 900}",
            "{\"eligible_teams\": [\"db-ops\"], \"expiry\": 59}",
            "{\"eligible_teams\": [\"db-ops\"], \"expiry\": \"900\"}",
            "{\"eligible_teams\": [\"db-ops\", \"db-ops\"], \"expiry\": 900}",
            "{\"expiry\": 900}")) {
      assertThatThrownBy(
              () -> step.start(demo.approval(c.run(), c.incident(), parameters, List.of())))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("approval step parameters: eligible_teams and expiry");
    }
    StepCall other =
        new StepCall(
            demo.tenant,
            c.run(),
            c.incident(),
            Ids.newId(),
            new Key("s-x"),
            StepCall.StepType.DIAGNOSTIC,
            APPROVAL,
            1,
            null,
            new StepCall.WakeReason("start"),
            null,
            null);
    assertThatThrownBy(() -> step.start(other)).isInstanceOf(IllegalArgumentException.class);
    assertThat(demo.rowsOfRun("approval_request", c.run())).isEmpty();
    assertWaiting(step.start(c.call()));
    CLOCK.advance(Duration.ofSeconds(899));
    assertWaiting(again(c));
  }

  private void assertNoProposal(ActionsFixture f, UUID incident) {
    UUID run = Ids.newId();
    StepCall call = f.approval(run, incident, APPROVAL, List.of());
    assertThat(completed(step.start(call), "rejected").resultRef()).isNull();
    assertThat(completed(step.evaluate(call), "rejected").resultRef()).isNull();
    StepCall second = f.approval(run, incident, APPROVAL, List.of());
    assertThat(completed(step.start(second), "rejected").resultRef()).isNull();
    assertThat(f.rowsOfRun("action_proposal", run)).isEmpty();
    assertThat(f.rowsOfRun("approval_request", run)).isEmpty();
    assertThat(f.rowsOfRun("step_without_proposal", run)).hasSize(2);
    List<AuditEventView> audits = f.audits(incident, AuditEventType.APPROVAL_DECIDED);
    assertThat(audits).hasSize(2);
    assertThat(audits)
        .allSatisfy(
            a -> {
              assertThat(a.summary()).isEqualTo("No action was proposed.");
              assertThat(a.subjectType()).isEqualTo("StepExecution");
              assertThat(a.actor()).isEqualTo(ACTIONS);
              assertThat(json(a.detailsJson()).get("decision").asString()).isEqualTo("no_proposal");
            });
    assertThat(audits.getFirst().subjectId()).isEqualTo(call.stepExecutionId());
    assertThat(f.evidenceOf(incident, EvidenceKind.APPROVAL)).isEmpty();
  }

  @Test
  @DisplayName("DM-R-29 every no-proposal case; a later Approval step of the run has none either")
  void noProposal() {
    assertNoProposal(demo, demo.openIncident());

    UUID noMatch = demo.openIncident();
    demo.noMatch(noMatch);
    assertNoProposal(demo, noMatch);

    UUID noFix = demo.openIncident();
    demo.match(noFix);
    demo.postgres()
        .sql(
            "UPDATE knowledge.similarity_search_result SET fix_action_key = NULL"
                + " WHERE tenant_id = :t AND incident_id = :i")
        .param("t", demo.tenant.value())
        .param("i", noFix)
        .update();
    assertNoProposal(demo, noFix);

    UUID inactive = demo.openIncident();
    demo.match(inactive);
    active(demo, false);
    try {
      assertNoProposal(demo, inactive);
    } finally {
      active(demo, true);
    }

    UUID noHypothesis = demo.incidentWithoutHypothesis();
    demo.match(noHypothesis);
    assertNoProposal(demo, noHypothesis);

    UUID noTarget =
        demo.notifications.ticketing.investigation.openIncident(
            NotificationsFixture.ALERTS, "srv-api-01");
    demo.match(noTarget);
    assertNoProposal(demo, noTarget);
  }

  private static void active(ActionsFixture f, boolean active) {
    f.postgres()
        .sql("UPDATE actions.registered_action SET active = :a WHERE tenant_id = :t")
        .param("a", active)
        .param("t", f.tenant.value())
        .update();
  }

  @Test
  @DisplayName("PM-M14-02 no proposal: no policy check event and no message")
  void noProposalNothingElse() {
    UUID incident = demo.openIncident();
    demo.noMatch(incident);
    StepCall call = demo.approval(Ids.newId(), incident, APPROVAL, List.of());
    completed(step.start(call), "rejected");
    assertThat(
            demo.audits(
                incident, AuditEventType.POLICY_CHECK_RESULT, AuditEventType.APPROVAL_REQUESTED))
        .isEmpty();
    assertThat(servers.records(demo.notifications, incident)).isEmpty();
    assertThat(servers.teamsPosts()).isEmpty();
  }

  @Test
  @DisplayName("SL-B-04 preauthorized leaves by approved, blocked by rejected, without messages")
  void preauthorizedAndBlocked() {
    Case p = story(preauthorized);
    Completed approved = completed(step.start(p.call()), "approved");
    assertThat(approved.resultRef().id()).isEqualTo(id(request(p)));
    assertThat(request(p).get("state")).isEqualTo("preauthorized");
    assertThat(request(p).get("decided_at")).isNotNull();
    assertThat(preauthorized.evidenceOf(p.incident(), EvidenceKind.APPROVAL))
        .extracting(EvidenceView::summary)
        .containsExactly("Preauthorized by policy: " + ON);
    assertThat(preauthorized.audits(p.incident(), AuditEventType.POLICY_CHECK_RESULT))
        .extracting(AuditEventView::summary)
        .containsExactly("Policy for " + ACTION_NAME + ": Preauthorized.");
    assertThat(preauthorized.audits(p.incident(), AuditEventType.APPROVAL_DECIDED))
        .extracting(AuditEventView::summary)
        .containsExactly("Approval 1: Preauthorized by policy — " + ON);
    assertThat(messages(p)).isEmpty();

    Case b = story(blocked);
    completed(step.start(b.call()), "rejected");
    assertThat(request(b).get("state")).isEqualTo("blocked");
    assertThat(blocked.evidenceOf(b.incident(), EvidenceKind.APPROVAL))
        .extracting(EvidenceView::summary)
        .containsExactly("Blocked by policy: " + ON);
    assertThat(messages(b)).isEmpty();
    assertThat(servers.teamsPosts()).isEmpty();
  }

  @Test
  @DisplayName("PM-M14-03 the three policy outcomes, each from its policy")
  void threeOutcomes() {
    Case required = story(demo);
    step.start(required.call());
    assertThat(request(required).get("policy_outcome")).isEqualTo("approval_required");
    assertThat(request(required).get("state")).isEqualTo("pending");
    Case pre = story(preauthorized);
    step.start(pre.call());
    assertThat(request(pre).get("policy_outcome")).isEqualTo("preauthorized");
    Case blk = story(blocked);
    step.start(blk.call());
    assertThat(request(blk).get("policy_outcome")).isEqualTo("blocked");
  }

  @Test
  @DisplayName("DM-E56-R2 the most restrictive policy over the affected services; default without")
  void mostRestrictive() {
    Case services = story(mixed);
    step.start(services.call());
    assertThat(request(services).get("policy_outcome")).isEqualTo("approval_required");

    UUID incident = mixed.incidentWithoutServices();
    assertThat(mixed.incidents.incident(mixed.tenant, incident).orElseThrow().affectedServices())
        .isEmpty();
    mixed.match(incident);
    UUID run = Ids.newId();
    completed(step.start(mixed.approval(run, incident, APPROVAL, List.of())), "approved");
    assertThat(mixed.rowsOfRun("approval_request", run).getFirst().get("policy_outcome"))
        .isEqualTo("preauthorized");
  }

  @Test
  @DisplayName("DM-R-30 the attempt limit, then an unresolved execution, refuse before the policy")
  void orderOfChecks() {
    Case limit = story(demo);
    demo.insertExecution(Ids.newId(), limit.incident(), null, "failed", 1, T0.minusSeconds(60));
    completed(step.start(limit.call()), "rejected");
    assertThat(request(limit).get("state")).isEqualTo("refused");
    assertThat(request(limit).get("refusal_reason")).isEqualTo("attempt_limit_reached");
    assertThat(request(limit).get("policy_outcome")).isNull();
    assertThat(demo.audits(limit.incident(), AuditEventType.POLICY_CHECK_RESULT)).isEmpty();
    assertThat(json(request(limit).get("snapshot")).get("attempt_number").asLong()).isEqualTo(2);

    Case unresolved = story(limitTwo);
    limitTwo.insertExecution(
        Ids.newId(), unresolved.incident(), null, "unresolved", 1, T0.minusSeconds(60));
    completed(step.start(unresolved.call()), "rejected");
    assertThat(request(unresolved).get("state")).isEqualTo("refused");
    assertThat(request(unresolved).get("refusal_reason")).isEqualTo("unresolved_execution");
    assertThat(limitTwo.audits(unresolved.incident(), AuditEventType.POLICY_CHECK_RESULT))
        .isEmpty();
    assertThat(limitTwo.evidenceOf(unresolved.incident(), EvidenceKind.APPROVAL))
        .extracting(EvidenceView::summary)
        .containsExactly("Approval refused (an earlier execution has an unknown result): " + ON);
  }

  @Test
  @DisplayName("SL-A2-04 after an execution of the run, a second Approval step is refused")
  void secondApprovalRefused() {
    Case c = story(demo);
    step.start(c.call());
    decide(c, "dbops-engineer", Decision.APPROVE);
    completed(again(c), "approved");
    UUID proposal = (UUID) request(c).get("proposal_id");
    demo.insertExecution(c.run(), c.incident(), proposal, "succeeded", 1, T0.plusSeconds(10));
    StepCall second =
        demo.approval(c.run(), c.incident(), APPROVAL, List.of(demo.userId("dbops-engineer")));
    completed(step.start(second), "rejected");
    Map<String, Object> refused = request(c, second);
    assertThat(refused.get("state")).isEqualTo("refused");
    assertThat(refused.get("refusal_reason")).isEqualTo("attempt_limit_reached");
    assertThat(refused.get("sequence_in_run")).isEqualTo(2L);
    assertThat(demo.evidenceOf(c.incident(), EvidenceKind.APPROVAL))
        .extracting(EvidenceView::summary)
        .contains("Approval refused (attempt limit reached): " + ON);
  }

  @Test
  @DisplayName("DM-SM-11 T1 to T4, T7 and T8")
  void stateMachine() {
    Case t1 = story(demo);
    demo.insertExecution(Ids.newId(), t1.incident(), null, "succeeded", 1, T0);
    step.start(t1.call());
    assertThat(request(t1).get("state")).isEqualTo("refused");
    Case t2 = story(blocked);
    step.start(t2.call());
    assertThat(request(t2).get("state")).isEqualTo("blocked");
    Case t3 = story(preauthorized);
    step.start(t3.call());
    assertThat(request(t3).get("state")).isEqualTo("preauthorized");
    Case t4 = story(demo);
    step.start(t4.call());
    assertThat(request(t4).get("state")).isEqualTo("pending");
    CLOCK.set(T0.plusSeconds(900));
    completed(again(t4), "expired");
    assertThat(request(t4).get("state")).isEqualTo("expired");
    assertThat(request(t4).get("decided_at")).isNotNull();
    assertThat(demo.evidenceOf(t4.incident(), EvidenceKind.APPROVAL))
        .extracting(EvidenceView::summary)
        .containsExactly("Approval expired with no decision: " + ON);

    CLOCK.set(T0);
    Case t8 = story(demo);
    step.start(t8.call());
    step.cancel(demo.cancel(t8.call()));
    Map<String, Object> cancelled = request(t8);
    assertThat(cancelled.get("state")).isEqualTo("cancelled");
    assertThat(cancelled.get("decided_at")).isNotNull();
    step.cancel(demo.cancel(t8.call()));
    assertThat(request(t8)).isEqualTo(cancelled);
    assertThat(demo.evidenceOf(t8.incident(), EvidenceKind.APPROVAL)).isEmpty();
    assertThat(demo.audits(t8.incident(), AuditEventType.APPROVAL_DECIDED)).isEmpty();
    assertThatThrownBy(() -> again(t8))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("approval request " + id(cancelled) + " is cancelled");
  }

  @Test
  @DisplayName("SL-B-03 a second Approval step uses the proposal and excludes earlier approvers")
  void twoApprovals() {
    Case c = story(demo);
    step.start(c.call());
    decide(c, "dbops-engineer", Decision.APPROVE);
    completed(again(c), "approved");
    servers.reset();
    UUID engineer = demo.userId("dbops-engineer");
    StepCall second = demo.approval(c.run(), c.incident(), APPROVAL, List.of(engineer));
    assertWaiting(step.start(second));
    Map<String, Object> r2 = request(c, second);
    assertThat(r2.get("sequence_in_run")).isEqualTo(2L);
    assertThat(r2.get("proposal_id")).isEqualTo(request(c, c.call()).get("proposal_id"));
    assertThat(demo.rowsOfRun("action_proposal", c.run())).hasSize(1);
    assertThat(json(r2.get("excluded_user_ids")).get(0).asString()).isEqualTo(engineer.toString());
    assertThat(messages(c))
        .filteredOn(r -> r.originId().equals(id(r2)))
        .extracting(r -> r.recipientKey() + " " + r.channel().key())
        .containsExactlyInAnyOrder("dbops-lead teams", "dbops-lead email");
    assertThat(servers.teamsKeys()).containsExactly("dbops-lead");
  }

  @Test
  @DisplayName("DM-R-64 one request per step execution: no second message, the same answer")
  void evaluatedAgain() {
    Case c = story(demo);
    step.start(c.call());
    int sent = messages(c).size();
    assertWaiting(again(c));
    assertWaiting(again(c));
    assertThat(demo.rowsOfRun("approval_request", c.run())).hasSize(1);
    assertThat(messages(c)).hasSize(sent);
    assertThat(servers.teamsPosts()).hasSize(2);
    decide(c, "dbops-lead", Decision.APPROVE);
    Completed first = completed(again(c), "approved");
    assertThat(again(c)).isEqualTo(first);
    assertThat(demo.rowsOfRun("approval_request", c.run())).hasSize(1);
  }

  @Test
  @DisplayName("DM-E08-R3 the events of vigil-actions for each request outcome, written once")
  void auditEvents() {
    Case story = story(demo);
    step.start(story.call());
    decide(story, "dbops-engineer", Decision.APPROVE);
    again(story);
    List<AuditEventView> events =
        demo.audits(
            story.incident(),
            AuditEventType.POLICY_CHECK_RESULT,
            AuditEventType.APPROVAL_REQUESTED,
            AuditEventType.APPROVAL_DECIDED);
    assertThat(events)
        .extracting(AuditEventView::eventType)
        .containsExactly(
            AuditEventType.POLICY_CHECK_RESULT,
            AuditEventType.APPROVAL_REQUESTED,
            AuditEventType.APPROVAL_DECIDED);
    assertThat(events)
        .allMatch(e -> e.subjectType().equals("ApprovalRequest"))
        .allMatch(e -> e.subjectId().equals(id(request(story))))
        .allMatch(e -> e.incidentId().equals(story.incident()));
    assertThat(events.get(0).actor()).isEqualTo(ACTIONS);
    assertThat(events.get(1).actor()).isEqualTo(ACTIONS);
    assertThat(events.get(1).summary())
        .isEqualTo(
            "Approval 1 requested for "
                + ACTION_NAME
                + " on Subscriber DB server from Database"
                + " Operations.");
    assertThat(events.get(2).actor()).isEqualTo(Actor.user(demo.userId("dbops-engineer")));
    assertThat(events.get(2).summary())
        .isEqualTo("Approval 1: Approved by Usman Tariq (DB Ops) — " + ON);

    Case pre = story(preauthorized);
    step.start(pre.call());
    assertThat(
            preauthorized.audits(
                pre.incident(),
                AuditEventType.POLICY_CHECK_RESULT,
                AuditEventType.APPROVAL_REQUESTED,
                AuditEventType.APPROVAL_DECIDED))
        .extracting(AuditEventView::eventType)
        .containsExactly(AuditEventType.POLICY_CHECK_RESULT, AuditEventType.APPROVAL_DECIDED);

    Case blk = story(blocked);
    step.start(blk.call());
    assertThat(
            blocked.audits(
                blk.incident(),
                AuditEventType.POLICY_CHECK_RESULT,
                AuditEventType.APPROVAL_REQUESTED,
                AuditEventType.APPROVAL_DECIDED))
        .extracting(AuditEventView::summary)
        .containsExactly(
            "Policy for " + ACTION_NAME + ": Blocked.", "Approval 1: Blocked by policy — " + ON);

    Case refused = story(demo);
    demo.insertExecution(Ids.newId(), refused.incident(), null, "succeeded", 1, T0);
    step.start(refused.call());
    step.evaluate(refused.call());
    assertThat(
            demo.audits(
                refused.incident(),
                AuditEventType.POLICY_CHECK_RESULT,
                AuditEventType.APPROVAL_REQUESTED,
                AuditEventType.APPROVAL_DECIDED))
        .extracting(AuditEventView::summary)
        .containsExactly("Approval 1: Refused: attempt limit reached — " + ON);

    Case expired = story(demo);
    step.start(expired.call());
    CLOCK.set(T0.plusSeconds(900));
    again(expired);
    again(expired);
    List<AuditEventView> decided = demo.audits(expired.incident(), AuditEventType.APPROVAL_DECIDED);
    assertThat(decided)
        .extracting(AuditEventView::summary)
        .containsExactly("Approval 1: Expired — " + ON);
    assertThat(decided.getFirst().actor()).isEqualTo(ACTIONS);
    assertThat(json(decided.getFirst().detailsJson()).get("decision").asString())
        .isEqualTo("expired");

    UUID none = demo.openIncident();
    step.start(demo.approval(Ids.newId(), none, APPROVAL, List.of()));
    assertThat(demo.audits(none, AuditEventType.APPROVAL_DECIDED))
        .extracting(AuditEventView::subjectType, AuditEventView::incidentId)
        .containsExactly(org.assertj.core.groups.Tuple.tuple("StepExecution", none));
  }
}
