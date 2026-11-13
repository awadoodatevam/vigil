package vigil.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vigil.actions.ActionsFixture;
import vigil.actions.api.ActionExecutionView;
import vigil.actions.api.ApprovalRequestView;
import vigil.actions.api.ExecutionState;
import vigil.engine.api.StepExecutionState;
import vigil.incidents.api.EvidenceKind;
import vigil.incidents.api.EvidenceView;
import vigil.intake.IntakeFixture;
import vigil.knowledge.api.PastIncidentView;
import vigil.knowledge.api.SimilarityResultView;
import vigil.runner.LabTarget;
import vigil.shared.StepCall.StepType;
import vigil.testsupport.ApiClient.Response;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ResponseApiIT {

  private static final Duration WAIT = Duration.ofSeconds(60);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private ApprovalApiFixture api;
  private ActionsFixture f;

  @BeforeAll
  void start() {
    api = new ApprovalApiFixture();
    f = api.fixture;
  }

  @AfterAll
  void stop() {
    api.close();
  }

  private static void await(BooleanSupplier condition) {
    long deadline = System.nanoTime() + WAIT.toNanos();
    while (!condition.getAsBoolean()) {
      assertThat(System.nanoTime()).as("condition met within %s", WAIT).isLessThan(deadline);
      IntakeFixture.sleep(Duration.ofMillis(100));
    }
  }

  private Response get(UUID incident) {
    return api.as(f, "presenter", api.client.get("/api/v1/incidents/" + incident + "/response"))
        .send();
  }

  private JsonNode read(UUID incident) {
    Response response = get(incident);
    assertThat(response.status()).isEqualTo(200);
    return response.json();
  }

  private List<ApprovalRequestView> requests(UUID run) {
    return api.actions.requests(f.tenant, run);
  }

  private boolean aiTaskCompleted(UUID run) {
    return api.engine.stepExecutions(f.tenant, run).stream()
        .anyMatch(
            e -> e.stepType() == StepType.AI_TASK && e.state() == StepExecutionState.COMPLETED);
  }

  private static List<String> names(JsonNode parameters) {
    return parameters.valueStream().map(p -> p.get("name").stringValue()).toList();
  }

  @Test
  @DisplayName("AR-I-27 the Response section: similar incident, proposal, approvals, executions")
  void response() {
    LabTarget lab = LabTarget.shared();
    lab.inject();
    try {
      api.handlers.block(StepType.AI_TASK);
      ApprovalApiFixture.ResponseRun r = api.responseRun(f, true, "done");
      api.handlers.awaitBlocked(StepType.AI_TASK, r.run());
      JsonNode before = read(r.incident());
      assertThat(before.get("similar").isNull()).isTrue();
      assertThat(before.get("proposal").isNull()).isTrue();
      assertThat(before.get("no_proposal").booleanValue()).isFalse();
      assertThat(before.get("approvals")).isEmpty();
      assertThat(before.get("executions")).isEmpty();
      api.handlers.release(StepType.AI_TASK);
      await(
          () ->
              !requests(r.run()).isEmpty()
                  && requests(r.run()).getFirst().state().key().equals("pending"));

      SimilarityResultView search =
          f.knowledge.latestSearchResult(f.tenant, r.incident()).orElseThrow();
      PastIncidentView past = f.knowledge.pastIncident(f.tenant, "PI-1001").orElseThrow();
      ApprovalRequestView request = requests(r.run()).getFirst();
      JsonNode body = read(r.incident());
      JsonNode similar = body.get("similar");
      assertThat(similar.get("state").stringValue()).isEqualTo("done");
      assertThat(similar.get("outcome").stringValue()).isEqualTo("match");
      assertThat(similar.get("past_incident").get("past_incident_key").stringValue())
          .isEqualTo("PI-1001");
      assertThat(similar.get("past_incident").get("title").stringValue()).isEqualTo(past.title());
      assertThat(similar.get("past_incident").get("record_label").stringValue())
          .isEqualTo("Fictional record");
      assertThat(similar.get("past_incident").get("fix_description").stringValue())
          .isEqualTo(past.fixDescription());
      assertThat(similar.get("score").stringValue()).isEqualTo("1.00");
      assertThat(similar.get("success_count").longValue()).isEqualTo(4);
      assertThat(similar.get("min_similarity").stringValue())
          .isEqualTo(SectionsController.decimal(search.minSimilarity()));
      JsonNode proposal = body.get("proposal");
      assertThat(proposal.get("action_name").stringValue())
          .isEqualTo("Purge archived transaction logs");
      assertThat(names(proposal.get("parameters")))
          .containsExactly("path", "older_than_minutes", "max_bytes");
      assertThat(proposal.get("parameters").get(0).get("value").stringValue())
          .isEqualTo("/data/txlog_archive");
      assertThat(proposal.get("parameters").get(1).get("value").longValue()).isEqualTo(1440);
      assertThat(proposal.get("target").get("target_key").stringValue()).isEqualTo("lab-db-01");
      assertThat(proposal.get("target").get("name").stringValue())
          .isEqualTo("Subscriber DB server");
      assertThat(proposal.get("policy_outcome").stringValue()).isEqualTo("approval_required");
      assertThat(body.get("no_proposal").booleanValue()).isFalse();
      JsonNode approval = body.get("approvals").get(0);
      assertThat(body.get("approvals")).hasSize(1);
      assertThat(approval.get("approval_request_id").stringValue())
          .isEqualTo(request.id().toString());
      assertThat(approval.get("sequence_in_run").longValue()).isEqualTo(1);
      assertThat(approval.get("eligible_teams")).hasSize(1);
      assertThat(approval.get("eligible_teams").get(0).get("name").stringValue())
          .isEqualTo("Database Operations");
      assertThat(approval.get("state").stringValue()).isEqualTo("pending");
      assertThat(approval.get("refusal_reason").isNull()).isTrue();
      assertThat(approval.get("decided").isNull()).isTrue();
      assertThat(approval.get("expires_at").stringValue())
          .isEqualTo(ApiTimes.format(request.expiresAt()));
      assertThat(body.get("executions")).isEmpty();

      Response decided =
          api.as(
                  f,
                  "dbops-engineer",
                  api.client
                      .post("/api/v1/approval-requests/" + request.id() + "/decisions")
                      .json(
                          "{\"decision\":\"approve\",\"snapshot_hash_shown\":\""
                              + request.snapshotHash()
                              + "\"}"))
              .send();
      assertThat(decided.status()).isEqualTo(201);
      await(
          () ->
              api.actions.executions(f.tenant, r.run()).stream()
                      .anyMatch(e -> e.state() == ExecutionState.SUCCEEDED)
                  && f.evidenceOf(r.incident(), EvidenceKind.ACTION_EXECUTION).size() == 1);
      ActionExecutionView execution = api.actions.executions(f.tenant, r.run()).getFirst();
      EvidenceView result = f.evidenceOf(r.incident(), EvidenceKind.ACTION_EXECUTION).getFirst();
      JsonNode after = read(r.incident());
      assertThat(after.get("approvals").get(0).get("state").stringValue()).isEqualTo("approved");
      assertThat(
              after
                  .get("approvals")
                  .get(0)
                  .get("decided")
                  .get("user")
                  .get("display_name")
                  .stringValue())
          .isEqualTo("Usman Tariq (DB Ops)");
      assertThat(after.get("executions")).hasSize(1);
      JsonNode block = after.get("executions").get(0);
      assertThat(block.get("action_execution_id").stringValue())
          .isEqualTo(execution.id().toString());
      assertThat(block.get("attempt_number").longValue()).isEqualTo(1);
      assertThat(block.get("attempt_limit").longValue()).isEqualTo(1);
      assertThat(block.get("state").stringValue()).isEqualTo("succeeded");
      assertThat(block.get("recheck").valueStream().map(c -> c.get("check").stringValue()))
          .containsExactly("approval_valid", "unchanged", "incident_open", "precondition");
      assertThat(block.get("recheck").valueStream().map(c -> c.get("passed").booleanValue()))
          .containsOnly(true);
      assertThat(block.get("recheck").get(3).get("detail").stringValue())
          .startsWith("Used percentage of one filesystem (mount=/data): ");
      assertThat(block.get("result_summary").stringValue()).isEqualTo(result.summary());
      assertThat(block.get("started_at").stringValue())
          .isEqualTo(ApiTimes.format(execution.startedAt()));
      assertThat(block.get("finished_at").stringValue())
          .isEqualTo(ApiTimes.format(execution.finishedAt()));
    } finally {
      api.handlers.release(StepType.AI_TASK);
      lab.clear();
    }

    ApprovalApiFixture.ResponseRun unavailable = api.responseRun(f, true, "unavailable");
    await(() -> aiTaskCompleted(unavailable.run()));
    JsonNode none = read(unavailable.incident()).get("similar");
    assertThat(none.get("state").stringValue()).isEqualTo("unavailable");
    for (String field :
        List.of("outcome", "past_incident", "score", "success_count", "min_similarity")) {
      assertThat(none.get(field).isNull()).as(field).isTrue();
    }

    ApprovalApiFixture.ResponseRun noMatch = api.responseRun(f, false, "done");
    await(() -> api.actions.withoutProposal(f.tenant, noMatch.run()));
    SimilarityResultView search =
        f.knowledge.latestSearchResult(f.tenant, noMatch.incident()).orElseThrow();
    JsonNode body = read(noMatch.incident());
    JsonNode similar = body.get("similar");
    assertThat(similar.get("state").stringValue()).isEqualTo("done");
    assertThat(similar.get("outcome").stringValue()).isEqualTo("no_match");
    assertThat(similar.get("past_incident").isNull()).isTrue();
    assertThat(similar.get("score").isNull()).isEqualTo(search.score() == null);
    if (search.score() != null) {
      assertThat(similar.get("score").stringValue())
          .isEqualTo(SectionsController.decimal(search.score()));
    }
    assertThat(similar.get("success_count").isNull()).isTrue();
    assertThat(similar.get("min_similarity").stringValue())
        .isEqualTo(SectionsController.decimal(search.minSimilarity()));
    assertThat(body.get("proposal").isNull()).isTrue();
    assertThat(body.get("no_proposal").booleanValue()).isTrue();

    UUID quiet = f.openIncident();
    JsonNode empty = read(quiet);
    assertThat(empty.get("similar").isNull()).isTrue();
    assertThat(empty.get("proposal").isNull()).isTrue();
    assertThat(empty.get("no_proposal").booleanValue()).isFalse();
    assertThat(empty.get("approvals")).isEmpty();
    assertThat(empty.get("executions")).isEmpty();

    Response merged = get(api.mergedIncident(f));
    assertThat(merged.status()).isEqualTo(404);
    assertThat(merged.json().get("error").get("code").stringValue()).isEqualTo("not_found");
  }

  @Test
  @DisplayName("AR-I-27 a run of two approval requests")
  void twoRequests() {
    ApprovalApiFixture.ResponseRun r = api.responseRun(f, true, "done", 2);
    await(
        () ->
            !requests(r.run()).isEmpty()
                && requests(r.run()).getFirst().state().key().equals("pending"));
    ApprovalRequestView first = requests(r.run()).getFirst();
    Response decided =
        api.as(
                f,
                "dbops-engineer",
                api.client
                    .post("/api/v1/approval-requests/" + first.id() + "/decisions")
                    .json(
                        "{\"decision\":\"approve\",\"snapshot_hash_shown\":\""
                            + first.snapshotHash()
                            + "\"}"))
            .send();
    assertThat(decided.status()).isEqualTo(201);
    await(
        () ->
            requests(r.run()).size() == 2
                && requests(r.run()).stream()
                    .anyMatch(q -> q.sequenceInRun() == 2 && q.state().key().equals("pending")));
    ApprovalRequestView second =
        requests(r.run()).stream().filter(q -> q.sequenceInRun() == 2).findFirst().orElseThrow();

    JsonNode body = read(r.incident());
    JsonNode approvals = body.get("approvals");
    assertThat(approvals).hasSize(2);
    assertThat(approvals.valueStream().map(a -> a.get("sequence_in_run").longValue()))
        .containsExactly(1L, 2L);
    assertThat(approvals.valueStream().map(a -> a.get("approval_request_id").stringValue()))
        .containsExactly(first.id().toString(), second.id().toString());
    assertThat(approvals.valueStream().map(a -> a.get("state").stringValue()))
        .containsExactly("approved", "pending");
    JsonNode proposal = body.get("proposal");
    assertThat(proposal.get("policy_outcome").stringValue())
        .isEqualTo(second.policyOutcome().key());
    assertThat(proposal.get("target").get("name").stringValue())
        .isEqualTo(JSON.readTree(first.snapshotJson()).get("target_name").stringValue());
  }
}
