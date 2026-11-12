package vigil.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vigil.actions.ActionsFixture;
import vigil.actions.api.ActionsStepHandler;
import vigil.actions.api.ApprovalRequestView;
import vigil.actions.api.Decision;
import vigil.actions.api.DecisionRefusal;
import vigil.incidents.api.IncidentView;
import vigil.shared.Ids;
import vigil.shared.StepCall;
import vigil.shared.VigilError;
import vigil.testsupport.ApiClient.Request;
import vigil.testsupport.ApiClient.Response;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApprovalApiIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private ApprovalApiFixture api;
  private ActionsFixture f;
  private ActionsFixture preauthorized;
  private ActionsStepHandler step;

  @BeforeAll
  void start() {
    api = new ApprovalApiFixture();
    f = api.fixture;
    preauthorized =
        ActionsFixture.create(
            api.servers,
            "Approval API preauthorized",
            ActionsFixture.part(1, ActionsFixture.policy(null, "preauthorized")));
    step = api.servers.app.context().getBean(ActionsStepHandler.class);
  }

  @AfterAll
  void stop() {
    api.close();
  }

  private Response send(String username, Request request) {
    return api.as(f, username, request).send();
  }

  private ApprovalApiFixture.PendingRequest pending() {
    return api.request(f, f.openIncident());
  }

  private static String page(UUID request) {
    return "/api/v1/approval-requests/" + request;
  }

  private static String decisions(UUID request) {
    return page(request) + "/decisions";
  }

  private static String body(String decision, String hash) {
    return "{\"decision\":\"" + decision + "\",\"snapshot_hash_shown\":\"" + hash + "\"}";
  }

  private Response decide(String username, UUID request, String decision, String hash) {
    return send(username, api.client.post(decisions(request)).json(body(decision, hash)));
  }

  @Test
  @DisplayName("AR-I-34 the Approval page from the stored snapshot with may_act and the refusal")
  void approvalPage() {
    ApprovalApiFixture.PendingRequest r = pending();
    ApprovalRequestView request = r.view();
    JsonNode snapshot = JSON.readTree(request.snapshotJson());
    IncidentView incident = f.incidents.incident(f.tenant, request.incidentId()).orElseThrow();

    JsonNode body = send("dbops-engineer", api.client.get(page(request.id()))).json();
    assertThat(body.get("approval_request_id").stringValue()).isEqualTo(request.id().toString());
    assertThat(body.get("sequence_in_run").longValue()).isEqualTo(1);
    assertThat(body.get("incident").get("incident_id").stringValue())
        .isEqualTo(incident.id().toString());
    assertThat(body.get("incident").get("number").longValue()).isEqualTo(incident.number());
    assertThat(body.get("incident").get("title").stringValue()).isEqualTo(incident.title());
    assertThat(body.get("state").stringValue()).isEqualTo("pending");
    assertThat(body.get("refusal_reason").isNull()).isTrue();
    assertThat(body.get("snapshot_hash").stringValue()).isEqualTo(request.snapshotHash());
    assertThat(body.get("action_name").stringValue())
        .isEqualTo(snapshot.get("action_name").stringValue())
        .isEqualTo("Purge archived transaction logs");
    List<String> names = new ArrayList<>();
    for (JsonNode parameter : body.get("parameters")) {
      names.add(parameter.get("name").stringValue());
      assertThat(parameter.get("value"))
          .isEqualTo(snapshot.get("parameters").get(parameter.get("name").stringValue()));
    }
    assertThat(names).containsExactly("path", "older_than_minutes", "max_bytes");
    assertThat(body.get("target").get("target_key").stringValue()).isEqualTo("lab-db-01");
    assertThat(body.get("target").get("name").stringValue())
        .isEqualTo(snapshot.get("target_name").stringValue())
        .isEqualTo("Subscriber DB server");
    assertThat(body.get("limits")).isEqualTo(snapshot.get("limits"));
    assertThat(body.get("limits")).hasSize(4);
    assertThat(body.get("attempt_number").longValue()).isEqualTo(1);
    assertThat(body.get("attempt_limit").longValue()).isEqualTo(1);
    assertThat(body.get("expires_at").stringValue())
        .isEqualTo(ApiTimes.format(Instant.parse(snapshot.get("expires_at").stringValue())))
        .isEqualTo(ApiTimes.format(request.expiresAt()));
    JsonNode basedOn = body.get("based_on");
    assertThat(basedOn.get("past_incident_key").stringValue()).isEqualTo("PI-1001");
    assertThat(basedOn.get("past_incident_title").stringValue())
        .isEqualTo(snapshot.get("based_on").get("past_incident_title").stringValue());
    assertThat(basedOn.get("record_label").stringValue()).isEqualTo("Fictional record");
    assertThat(basedOn.get("score").stringValue()).isEqualTo("1.00");
    assertThat(basedOn.get("success_count").longValue()).isEqualTo(4);
    JsonNode evidence = body.get("evidence");
    assertThat(evidence).hasSameSizeAs(snapshot.get("evidence")).isNotEmpty();
    for (int i = 0; i < evidence.size(); i++) {
      JsonNode stored = snapshot.get("evidence").get(i);
      assertThat(evidence.get(i).get("evidence_entry_id"))
          .isEqualTo(stored.get("evidence_entry_id"));
      assertThat(evidence.get(i).get("kind")).isEqualTo(stored.get("kind"));
      assertThat(evidence.get(i).get("summary")).isEqualTo(stored.get("summary"));
      assertThat(evidence.get(i).get("collected_at").stringValue())
          .isEqualTo(ApiTimes.format(Instant.parse(stored.get("collected_at").stringValue())));
    }
    JsonNode checks = body.get("recovery_checks");
    assertThat(checks.get("name").stringValue()).isEqualTo("Subscriber DB services recovered");
    assertThat(checks.get("conditions"))
        .isEqualTo(snapshot.get("recovery_checks").get("conditions"));
    assertThat(checks.get("observation_window_seconds").longValue()).isEqualTo(60);
    assertThat(body.get("decided").isNull()).isTrue();
    assertThat(body.get("may_act").booleanValue()).isTrue();
    assertThat(body.get("refusal").isNull()).isTrue();

    JsonNode viewer = send("presenter", api.client.get(page(request.id()))).json();
    assertThat(viewer.get("may_act").booleanValue()).isFalse();
    assertThat(viewer.get("refusal").isNull()).isTrue();

    JsonNode other = send("noc-l1", api.client.get(page(request.id()))).json();
    assertThat(other.get("may_act").booleanValue()).isTrue();
    assertThat(other.get("refusal").get("key").stringValue()).isEqualTo("not_eligible");
    assertThat(other.get("refusal").get("message").stringValue())
        .isEqualTo("You are not in a team that can approve this action (Database Operations).");

    for (String unknown : List.of(page(UUID.randomUUID()), "/api/v1/approval-requests/x")) {
      Response response = send("presenter", api.client.get(unknown));
      assertThat(response.status()).isEqualTo(404);
      assertThat(response.json().get("error").get("code").stringValue()).isEqualTo("not_found");
    }
  }

  @Test
  @DisplayName("AR-I-35 the decision: 201 with the new state, its refusals, 400 and 403")
  void decision() {
    ApprovalApiFixture.PendingRequest r = pending();
    UUID engineer = f.userId("dbops-engineer");
    Response approved = decide("dbops-engineer", r.view().id(), "approve", r.view().snapshotHash());
    assertThat(approved.status()).isEqualTo(201);
    JsonNode answer = approved.json();
    assertThat(answer.get("state").stringValue()).isEqualTo("approved");
    assertThat(answer.get("user").get("user_id").stringValue()).isEqualTo(engineer.toString());
    assertThat(answer.get("user").get("display_name").stringValue())
        .isEqualTo("Usman Tariq (DB Ops)");
    assertThat(answer.get("at").stringValue()).isEqualTo(ApiTimes.format(api.clock.instant()));

    JsonNode after = send("dbops-lead", api.client.get(page(r.view().id()))).json();
    assertThat(after.get("state").stringValue()).isEqualTo("approved");
    assertThat(after.get("decided").get("user").get("display_name").stringValue())
        .isEqualTo("Usman Tariq (DB Ops)");
    assertThat(after.get("decided").get("at").stringValue())
        .isEqualTo(ApiTimes.format(api.clock.instant()));
    assertThat(after.get("refusal").get("key").stringValue()).isEqualTo("already_decided");

    ApprovalApiFixture.PendingRequest other = pending();
    Response rejected =
        decide("dbops-lead", other.view().id(), "reject", other.view().snapshotHash());
    assertThat(rejected.status()).isEqualTo(201);
    assertThat(rejected.json().get("state").stringValue()).isEqualTo("rejected");
    assertThat(rejected.json().get("user").get("display_name").stringValue())
        .isEqualTo("Farah Naqvi (DB Ops Lead)");

    ApprovalApiFixture.PendingRequest third = pending();
    Response changed = decide("dbops-engineer", third.view().id(), "approve", "wrong");
    assertThat(changed.status()).isEqualTo(422);
    assertThat(changed.json().get("error").get("message").stringValue())
        .isEqualTo(
            "This request changed after you opened it. Reload the page to see the current request.");
    assertThat(changed.json().get("error").get("details").get("refusal").stringValue())
        .isEqualTo("snapshot_changed");

    String hash = third.view().snapshotHash();
    for (String bad :
        List.of(
            "{\"decision\":\"maybe\",\"snapshot_hash_shown\":\"x\"}",
            "{\"decision\":\"approve\"}",
            "{\"decision\":\"approve\",\"snapshot_hash_shown\":\"" + hash + "\",\"note\":\"x\"}",
            "[]")) {
      Response response =
          send("dbops-engineer", api.client.post(decisions(third.view().id())).json(bad));
      assertThat(response.status()).as(bad).isEqualTo(400);
      assertThat(response.json().get("error").get("code").stringValue()).isEqualTo("bad_request");
    }
    assertThat(api.actions.request(f.tenant, third.view().id()).orElseThrow().state().key())
        .isEqualTo("pending");
    assertThat(decide("presenter", third.view().id(), "approve", hash).status()).isEqualTo(403);
  }

  @Test
  @DisplayName("AR-I-05 the Approval read shows the expiry that publishes no event")
  void followsTheExpiry() {
    ApprovalApiFixture.PendingRequest r = pending();
    JsonNode before = send("dbops-engineer", api.client.get(page(r.view().id()))).json();
    assertThat(before.get("refusal").isNull()).isTrue();
    api.clock.advance(Duration.ofSeconds(901));
    JsonNode after = send("dbops-engineer", api.client.get(page(r.view().id()))).json();
    assertThat(after.get("state").stringValue()).isEqualTo("pending");
    assertThat(after.get("refusal").get("key").stringValue()).isEqualTo("expired");
    assertThat(after.get("refusal").get("message").stringValue())
        .isEqualTo(
            "This request expired at " + ApprovalApiFixture.outbound(r.view().expiresAt()) + ".");
  }

  /** The refusal read now, then the decision's refusal: the same key, text and details. */
  private void sameRefusal(ActionsFixture tenant, UUID request, String username, String key) {
    UUID user = tenant.userId(username);
    Optional<DecisionRefusal> read = api.actions.decisionRefusal(tenant.tenant, request, user);
    assertThat(read).as("refusal %s", key).isPresent();
    assertThat(read.get().key()).isEqualTo(key);
    assertThat(read.get().details()).containsEntry("refusal", key);
    String hash = api.actions.request(tenant.tenant, request).orElseThrow().snapshotHash();
    assertThatThrownBy(
            () -> api.actions.decide(tenant.tenant, request, user, Decision.APPROVE, hash))
        .isInstanceOfSatisfying(
            VigilError.class,
            e -> {
              assertThat(e.code()).isEqualTo(VigilError.Code.REFUSED);
              assertThat(e.message()).isEqualTo(read.get().message());
              assertThat(e.details()).isEqualTo(Map.copyOf(read.get().details()));
            });
  }

  @Test
  @DisplayName("DM-E59-R1 the refusal read before a decision is the one it gives, checks 1 to 6")
  void refusalBeforeDecision() {
    ApprovalApiFixture.PendingRequest cancelled = pending();
    step.cancel(f.cancel(cancelled.call()));
    sameRefusal(f, cancelled.view().id(), "dbops-engineer", "cancelled");

    ApprovalApiFixture.PendingRequest pre =
        api.request(preauthorized, preauthorized.openIncident());
    sameRefusal(preauthorized, pre.view().id(), "dbops-engineer", "not_pending");

    ApprovalApiFixture.PendingRequest decided = pending();
    api.actions.decide(
        f.tenant,
        decided.view().id(),
        f.userId("dbops-engineer"),
        Decision.APPROVE,
        decided.view().snapshotHash());
    api.clock.advance(Duration.ofSeconds(1));
    sameRefusal(f, decided.view().id(), "dbops-lead", "already_decided");

    ApprovalApiFixture.PendingRequest expired = pending();
    api.clock.advance(Duration.ofSeconds(900));
    sameRefusal(f, expired.view().id(), "dbops-engineer", "expired");

    ApprovalApiFixture.PendingRequest open = pending();
    sameRefusal(f, open.view().id(), "noc-l1", "not_eligible");

    UUID incident = f.openIncident();
    f.match(incident);
    StepCall excludedCall =
        f.approval(
            Ids.newId(), incident, ActionsFixture.APPROVAL, List.of(f.userId("dbops-engineer")));
    step.start(excludedCall);
    UUID excluded = api.actions.requests(f.tenant, excludedCall.runId()).getFirst().id();
    sameRefusal(f, excluded, "dbops-engineer", "excluded");

    assertThat(api.actions.decisionRefusal(f.tenant, open.view().id(), f.userId("dbops-engineer")))
        .isEmpty();
    ApprovalRequestView done =
        api.actions.decide(
            f.tenant,
            open.view().id(),
            f.userId("dbops-engineer"),
            Decision.APPROVE,
            open.view().snapshotHash());
    assertThat(done.state().key()).isEqualTo("approved");
  }

  /** The read refusal of the page and the 422 of the decision for the same case. */
  private void sameOnBothSides(UUID request, String username, String key) {
    String hash = api.actions.request(f.tenant, request).orElseThrow().snapshotHash();
    JsonNode refusal = send(username, api.client.get(page(request))).json().get("refusal");
    Response response = decide(username, request, "approve", hash);
    assertThat(response.status()).isEqualTo(422);
    JsonNode error = response.json().get("error");
    assertThat(refusal.get("key").stringValue())
        .isEqualTo(error.get("details").get("refusal").stringValue())
        .isEqualTo(key);
    assertThat(refusal.get("message").stringValue()).isEqualTo(error.get("message").stringValue());
  }

  @Test
  @DisplayName("AR-I-03 the read refusal is the 422 refusal of the decision for the same case")
  void readRefusalIsTheErrorRefusal() {
    ApprovalApiFixture.PendingRequest decided = pending();
    decide("dbops-engineer", decided.view().id(), "approve", decided.view().snapshotHash());
    sameOnBothSides(decided.view().id(), "dbops-lead", "already_decided");

    ApprovalApiFixture.PendingRequest open = pending();
    sameOnBothSides(open.view().id(), "noc-l1", "not_eligible");

    ApprovalApiFixture.PendingRequest expired = pending();
    api.clock.advance(Duration.ofSeconds(900));
    sameOnBothSides(expired.view().id(), "dbops-engineer", "expired");
  }

  @Test
  @DisplayName("AR-I-07 the Approval page and decision: 401, 403 as admin and presenter")
  void access() {
    ApprovalApiFixture.PendingRequest r = pending();
    api.noView(f);
    String hash = r.view().snapshotHash();
    assertThat(api.client.get(page(r.view().id())).host(f.notifications.host).send().status())
        .isEqualTo(401);
    assertThat(
            api.client
                .post(decisions(r.view().id()))
                .host(f.notifications.host)
                .json(body("approve", hash))
                .send()
                .status())
        .isEqualTo(401);
    Response page = send("admin", api.client.get(page(r.view().id())));
    assertThat(page.status()).isEqualTo(403);
    assertThat(page.json().get("error").get("code").stringValue()).isEqualTo("forbidden");
    assertThat(decide("admin", r.view().id(), "approve", hash).status()).isEqualTo(403);
    assertThat(decide("presenter", r.view().id(), "approve", hash).status()).isEqualTo(403);
    assertThat(api.actions.request(f.tenant, r.view().id()).orElseThrow().state().key())
        .isEqualTo("pending");
  }
}
