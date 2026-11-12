package vigil.actions;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import vigil.actions.api.ActionExecutionView;
import vigil.actions.api.ActionsService;
import vigil.actions.api.ActionsStepHandler;
import vigil.actions.api.ApprovalRequestView;
import vigil.actions.api.Decision;
import vigil.actions.api.ExecutionState;
import vigil.actions.api.ProposalView;
import vigil.actions.api.RequestState;
import vigil.incidents.api.IncidentService;
import vigil.incidents.api.NotifiedPurpose;
import vigil.notifications.NotificationsApp;
import vigil.shared.Actor;
import vigil.shared.Ids;
import vigil.shared.Key;
import vigil.shared.StepCall;
import vigil.testsupport.MutableClock;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ActionReadsIT {

  static final Instant T0 = Instant.parse("2026-11-12T09:00:00Z");
  static final MutableClock CLOCK = new MutableClock(T0);

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
  private IncidentService incidents;
  private ActionsFixture f;

  @BeforeAll
  void start() {
    servers = NotificationsApp.start(TestBeans.class);
    step = servers.app.context().getBean(ActionsStepHandler.class);
    actions = servers.app.context().getBean(ActionsService.class);
    incidents = servers.app.context().getBean(IncidentService.class);
    f = ActionsFixture.create(servers, "Action reads");
  }

  @AfterAll
  void stop() {
    servers.close();
  }

  /** Two requests of one run (two Approval step executions) after a match. */
  private record Run(UUID run, UUID incident, List<StepCall> calls) {}

  private Run twoRequests() {
    UUID incident = f.openIncident();
    f.match(incident);
    UUID run = Ids.newId();
    List<StepCall> calls = new ArrayList<>();
    for (int i = 0; i < 2; i++) {
      StepCall call = f.approval(run, incident, ActionsFixture.APPROVAL, List.of());
      step.start(call);
      calls.add(call);
    }
    return new Run(run, incident, calls);
  }

  @Test
  @DisplayName(
      "DC-R-08 the display reads of vigil-actions: requests, proposal, no proposal, executions")
  void reads() {
    CLOCK.set(T0);
    Run r = twoRequests();
    List<Map<String, Object>> rows = f.rowsOfRun("approval_request", r.run());
    assertThat(rows).hasSize(2);

    List<ApprovalRequestView> requests = actions.requests(f.tenant, r.run());
    assertThat(requests).extracting(ApprovalRequestView::sequenceInRun).containsExactly(1L, 2L);
    assertThat(requests)
        .extracting(ApprovalRequestView::stepExecutionId)
        .containsExactly(r.calls().get(0).stepExecutionId(), r.calls().get(1).stepExecutionId());
    ApprovalRequestView first = requests.getFirst();
    assertThat(actions.request(f.tenant, first.id())).contains(first);
    assertThat(first.state()).isEqualTo(RequestState.PENDING);
    assertThat(first.expiresAt()).isEqualTo(T0.plusSeconds(900));

    ProposalView proposal = actions.proposal(f.tenant, r.run()).orElseThrow();
    Map<String, Object> proposalRow = f.rowsOfRun("action_proposal", r.run()).getFirst();
    assertThat(proposal.id()).isEqualTo(proposalRow.get("id")).isEqualTo(first.proposalId());
    assertThat(proposal.incidentId()).isEqualTo(r.incident());
    assertThat(proposal.runId()).isEqualTo(r.run());
    assertThat(proposal.actionKey()).isEqualTo("purge-archived-txlogs");
    assertThat(ActionsFixture.JSON.readTree(proposal.actionSnapshotJson()).get("name").asString())
        .isEqualTo("Purge archived transaction logs");
    assertThat(proposal.componentKey()).isEqualTo("srv-db-01");
    assertThat(proposal.targetKey()).isEqualTo("lab-db-01");
    assertThat(proposal.pastIncidentKey()).isEqualTo("PI-1001");
    assertThat(proposal.score()).isEqualByComparingTo("1.00");
    assertThat(proposal.successCount()).isEqualTo(4);
    assertThat(proposal.similarityResultId()).isEqualTo(proposalRow.get("similarity_result_id"));
    assertThat(proposal.createdAt()).isEqualTo(T0);
    assertThat(actions.withoutProposal(f.tenant, r.run())).isFalse();

    UUID incident = f.openIncident();
    f.noMatch(incident);
    StepCall none = f.approval(Ids.newId(), incident, ActionsFixture.APPROVAL, List.of());
    step.start(none);
    assertThat(actions.withoutProposal(f.tenant, none.runId())).isTrue();
    assertThat(actions.proposal(f.tenant, none.runId())).isEmpty();
    assertThat(actions.requests(f.tenant, none.runId())).isEmpty();

    UUID stepTwo = Ids.newId();
    UUID stepOne = Ids.newId();
    UUID second =
        f.insertExecution(
            r.run(),
            r.incident(),
            stepTwo,
            proposal.id(),
            List.of(first.id()),
            "succeeded",
            2,
            1,
            T0);
    UUID firstExecution =
        f.insertExecution(
            r.run(), r.incident(), stepOne, proposal.id(), List.of(), "recorded", 1, 0, null);
    List<ActionExecutionView> executions = actions.executions(f.tenant, r.run());
    assertThat(executions)
        .extracting(ActionExecutionView::id)
        .containsExactly(firstExecution, second);
    ActionExecutionView done = executions.get(1);
    assertThat(done.incidentId()).isEqualTo(r.incident());
    assertThat(done.runId()).isEqualTo(r.run());
    assertThat(done.stepExecutionId()).isEqualTo(stepTwo);
    assertThat(done.proposalId()).isEqualTo(proposal.id());
    assertThat(done.actionKey()).isEqualTo("purge-archived-txlogs");
    assertThat(done.approvalRequestIds()).containsExactly(first.id());
    assertThat(done.attemptNumber()).isEqualTo(2);
    assertThat(done.state()).isEqualTo(ExecutionState.SUCCEEDED);
    assertThat(done.recheckJson()).isNull();
    assertThat(done.operationRunId()).isNull();
    assertThat(done.startedAt()).isNotNull();
    assertThat(done.finishedAt()).isNull();
    assertThat(executions.getFirst().state()).isEqualTo(ExecutionState.RECORDED);
    assertThat(ExecutionState.fromKey("not_executed")).contains(ExecutionState.NOT_EXECUTED);
    assertThat(ExecutionState.fromKey("nope")).isEmpty();

    UUID unknown = Ids.newId();
    assertThat(actions.request(f.tenant, unknown)).isEmpty();
    assertThat(actions.requests(f.tenant, unknown)).isEmpty();
    assertThat(actions.proposal(f.tenant, unknown)).isEmpty();
    assertThat(actions.withoutProposal(f.tenant, unknown)).isFalse();
    assertThat(actions.executions(f.tenant, unknown)).isEmpty();
    assertThat(actions.decisionRefusal(f.tenant, unknown, f.userId("dbops-engineer"))).isEmpty();
  }

  /**
   * Every row of the tenant in the schemas {@code actions}, {@code incidents} and {@code audit}.
   */
  private List<String> rows() {
    List<String> tables =
        f.postgres()
            .sql(
                "SELECT t.table_schema || '.' || t.table_name FROM information_schema.tables t"
                    + " JOIN information_schema.columns c ON c.table_schema = t.table_schema"
                    + " AND c.table_name = t.table_name AND c.column_name = 'tenant_id'"
                    + " WHERE t.table_schema IN ('actions', 'incidents', 'audit')"
                    + " AND t.table_type = 'BASE TABLE' ORDER BY 1")
            .query(String.class)
            .list();
    List<String> rows = new ArrayList<>();
    for (String table : tables) {
      rows.add(table);
      rows.addAll(
          f.postgres()
              .sql(
                  "SELECT t::text FROM "
                      + table
                      + " t WHERE t.tenant_id = :t ORDER BY t::text COLLATE \"C\"")
              .param("t", f.tenant.value())
              .query(String.class)
              .list());
    }
    return rows;
  }

  @Test
  @DisplayName("PM-R-10 the refusal reads of vigil-actions and vigil-incidents write nothing")
  void refusalReadsWriteNothing() {
    CLOCK.set(T0);
    Run r = twoRequests();
    ApprovalRequestView first = actions.requests(f.tenant, r.run()).getFirst();
    ApprovalRequestView second = actions.requests(f.tenant, r.run()).get(1);
    actions.decide(
        f.tenant, second.id(), f.userId("dbops-engineer"), Decision.APPROVE, second.snapshotHash());
    UUID open = f.openIncident();
    UUID acknowledged = f.openIncident();
    incidents.recordNotifiedTeam(
        f.tenant,
        Actor.module("vigil-notifications"),
        acknowledged,
        new Key("noc-l1"),
        NotifiedPurpose.INCIDENT_NOTIFICATION);
    incidents.acknowledge(f.tenant, acknowledged, f.userId("noc-l1"));
    CLOCK.advance(Duration.ofSeconds(1));

    List<String> before = rows();
    for (String user : List.of("dbops-engineer", "dbops-lead", "noc-l1", "presenter")) {
      UUID id = f.userId(user);
      actions.decisionRefusal(f.tenant, first.id(), id);
      actions.decisionRefusal(f.tenant, second.id(), id);
      incidents.acknowledgeRefusal(f.tenant, open, id);
      incidents.acknowledgeRefusal(f.tenant, acknowledged, id);
    }
    assertThat(actions.decisionRefusal(f.tenant, second.id(), f.userId("dbops-lead")))
        .hasValueSatisfying(refusal -> assertThat(refusal.key()).isEqualTo("already_decided"));
    assertThat(incidents.acknowledgeRefusal(f.tenant, acknowledged, f.userId("noc-l1")))
        .hasValueSatisfying(refusal -> assertThat(refusal.key()).isEqualTo("already_acknowledged"));
    assertThat(incidents.acknowledgeRefusal(f.tenant, Ids.newId(), f.userId("noc-l1"))).isEmpty();
    assertThat(rows()).isEqualTo(before);
  }
}
