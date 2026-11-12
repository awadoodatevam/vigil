package vigil.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
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
import vigil.actions.ActionsFixture;
import vigil.incidents.api.AcknowledgeRefusal;
import vigil.incidents.api.IncidentService;
import vigil.incidents.api.IncidentView;
import vigil.incidents.api.NotifiedPurpose;
import vigil.shared.Actor;
import vigil.shared.Key;
import vigil.shared.VigilError;
import vigil.testsupport.ApiClient.Request;
import vigil.testsupport.ApiClient.Response;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AcknowledgeApiIT {

  private static final Actor NOTIFICATIONS = Actor.module("vigil-notifications");

  private ApprovalApiFixture api;
  private ActionsFixture f;
  private IncidentService incidents;

  @BeforeAll
  void start() {
    api = new ApprovalApiFixture();
    f = api.fixture;
    incidents = f.incidents;
  }

  @AfterAll
  void stop() {
    api.close();
  }

  private Response send(String username, Request request) {
    return api.as(f, username, request).send();
  }

  private static String page(UUID incident) {
    return "/api/v1/incidents/" + incident + "/acknowledgement";
  }

  private static String action(UUID incident) {
    return "/api/v1/incidents/" + incident + "/acknowledgements";
  }

  /** An open incident with {@code noc-l1} recorded as notified. */
  private UUID notifiedIncident() {
    UUID incident = f.openIncident();
    notified(incident, "noc-l1");
    return incident;
  }

  private void notified(UUID incident, String team) {
    incidents.recordNotifiedTeam(
        f.tenant, NOTIFICATIONS, incident, new Key(team), NotifiedPurpose.INCIDENT_NOTIFICATION);
  }

  private static void refused(Response response, String key, String message) {
    assertThat(response.status()).isEqualTo(422);
    JsonNode error = response.json().get("error");
    assertThat(error.get("code").stringValue()).isEqualTo("refused");
    assertThat(error.get("message").stringValue()).isEqualTo(message);
    assertThat(error.get("details").get("refusal").stringValue()).isEqualTo(key);
  }

  @Test
  @DisplayName("AR-I-32 the Acknowledge page with may_act and the refusal for the caller")
  void acknowledgePage() {
    UUID incident = notifiedIncident();
    api.summary(
        f, incident, "template", "Disk full on the Subscriber DB server.", api.clock.instant());
    IncidentView view = incidents.incident(f.tenant, incident).orElseThrow();

    JsonNode body = send("noc-l1", api.client.get(page(incident))).json();
    assertThat(body.get("incident_id").stringValue()).isEqualTo(incident.toString());
    assertThat(body.get("number").longValue()).isEqualTo(view.number());
    assertThat(body.get("title").stringValue()).isEqualTo(view.title());
    assertThat(body.get("severity").stringValue()).isEqualTo("P1");
    assertThat(body.get("owner").get("team_key").stringValue()).isEqualTo("db-ops");
    assertThat(body.get("owner").get("name").stringValue()).isEqualTo("Database Operations");
    assertThat(body.get("first_alert_fired_at").stringValue())
        .isEqualTo(ApiTimes.format(view.firstAlertFiredAt()));
    assertThat(body.get("alert_count").longValue()).isEqualTo(view.alertCount());
    assertThat(body.get("summary").stringValue())
        .isEqualTo("Disk full on the Subscriber DB server.");
    assertThat(body.get("state").stringValue()).isEqualTo("open");
    assertThat(body.get("acknowledged").isNull()).isTrue();
    assertThat(body.get("may_act").booleanValue()).isTrue();
    assertThat(body.get("refusal").isNull()).isTrue();
    assertThat(body.propertyNames())
        .containsExactlyInAnyOrder(
            "incident_id",
            "number",
            "title",
            "severity",
            "owner",
            "first_alert_fired_at",
            "alert_count",
            "summary",
            "state",
            "acknowledged",
            "may_act",
            "refusal");

    JsonNode viewer = send("presenter", api.client.get(page(incident))).json();
    assertThat(viewer.get("may_act").booleanValue()).isFalse();
    assertThat(viewer.get("refusal").isNull()).isTrue();

    JsonNode other = send("dbops-engineer", api.client.get(page(incident))).json();
    assertThat(other.get("may_act").booleanValue()).isTrue();
    assertThat(other.get("refusal").get("key").stringValue()).isEqualTo("not_notified");
    assertThat(other.get("refusal").get("message").stringValue())
        .isEqualTo("You are not in a team that was notified about this incident.");

    api.noView(f);
    assertThat(send("admin", api.client.get(page(incident))).status()).isEqualTo(403);

    for (UUID missing : List.of(api.formingIncident(f), api.mergedIncident(f), UUID.randomUUID())) {
      Response response = send("presenter", api.client.get(page(missing)));
      assertThat(response.status()).isEqualTo(404);
      assertThat(response.json().get("error").get("code").stringValue()).isEqualTo("not_found");
    }
  }

  @Test
  @DisplayName("AR-I-33 the acknowledgement: 201, its refusals as 422, 403 and 404")
  void acknowledge() {
    UUID incident = notifiedIncident();
    Response done = send("noc-l1", api.client.post(action(incident)));
    assertThat(done.status()).isEqualTo(201);
    UUID nocL1 = f.userId("noc-l1");
    assertThat(done.json().get("user").get("user_id").stringValue()).isEqualTo(nocL1.toString());
    assertThat(done.json().get("user").get("display_name").stringValue())
        .isEqualTo("Hira Baig (NOC L1)");
    assertThat(done.json().get("at").stringValue()).isEqualTo(ApiTimes.format(api.clock.instant()));
    IncidentView view = incidents.incident(f.tenant, incident).orElseThrow();
    assertThat(view.acknowledgedBy()).isEqualTo(nocL1);
    assertThat(view.acknowledgedAt()).isEqualTo(api.clock.instant());

    Response again = send("noc-l1", api.client.post(action(incident)));
    refused(
        again,
        "already_acknowledged",
        "Already acknowledged by Hira Baig (NOC L1) at "
            + ApprovalApiFixture.outbound(api.clock.instant())
            + ".");
    JsonNode details = again.json().get("error").get("details");
    assertThat(details.get("user_id").stringValue()).isEqualTo(nocL1.toString());
    assertThat(details.get("at").stringValue()).isEqualTo(ApiTimes.format(api.clock.instant()));

    UUID resolved = notifiedIncident();
    f.notifications.resolve(resolved);
    refused(
        send("noc-l1", api.client.post(action(resolved))),
        "not_open",
        "This incident is resolved. It does not need acknowledgement.");

    refused(
        send("dbops-engineer", api.client.post(action(notifiedIncident()))),
        "not_notified",
        "You are not in a team that was notified about this incident.");

    assertThat(send("presenter", api.client.post(action(notifiedIncident()))).status())
        .isEqualTo(403);
    Response forming = send("noc-l1", api.client.post(action(api.formingIncident(f))));
    assertThat(forming.status()).isEqualTo(404);
    assertThat(forming.json().get("error").get("code").stringValue()).isEqualTo("not_found");
  }

  @Test
  @DisplayName("AR-I-05 the Acknowledge read follows an acknowledgement by another user")
  void followsTheCurrentState() {
    UUID incident = notifiedIncident();
    notified(incident, "db-ops");
    JsonNode before = send("noc-l1", api.client.get(page(incident))).json();
    assertThat(before.get("acknowledged").isNull()).isTrue();
    assertThat(before.get("refusal").isNull()).isTrue();

    incidents.acknowledge(f.tenant, incident, f.userId("dbops-lead"));

    JsonNode after = send("noc-l1", api.client.get(page(incident))).json();
    assertThat(after.get("acknowledged").get("user").get("display_name").stringValue())
        .isEqualTo("Farah Naqvi (DB Ops Lead)");
    assertThat(after.get("acknowledged").get("at").stringValue())
        .isEqualTo(ApiTimes.format(api.clock.instant()));
    assertThat(after.get("refusal").get("key").stringValue()).isEqualTo("already_acknowledged");
    assertThat(after.get("refusal").get("message").stringValue())
        .isEqualTo(
            "Already acknowledged by Farah Naqvi (DB Ops Lead) at "
                + ApprovalApiFixture.outbound(api.clock.instant())
                + ".");
  }

  /** The refusal read now, then the acknowledgement's refusal: the same key, text and details. */
  private void sameRefusal(UUID incident, String username, String key) {
    UUID user = f.userId(username);
    Optional<AcknowledgeRefusal> read = incidents.acknowledgeRefusal(f.tenant, incident, user);
    assertThat(read).isPresent();
    assertThat(read.get().key()).isEqualTo(key);
    assertThat(read.get().details()).containsEntry("refusal", key);
    assertThatThrownBy(() -> incidents.acknowledge(f.tenant, incident, user))
        .isInstanceOfSatisfying(
            VigilError.class,
            e -> {
              assertThat(e.code()).isEqualTo(VigilError.Code.REFUSED);
              assertThat(e.message()).isEqualTo(read.get().message());
              assertThat(e.details()).isEqualTo(Map.copyOf(read.get().details()));
            });
  }

  @Test
  @DisplayName("DM-E35-R1 the refusal read before an acknowledgement is the one it gives")
  void refusalBeforeAcknowledgement() {
    UUID incident = notifiedIncident();
    assertThat(incidents.acknowledgeRefusal(f.tenant, incident, f.userId("noc-l1"))).isEmpty();
    incidents.acknowledge(f.tenant, incident, f.userId("noc-l1"));
    api.clock.advance(Duration.ofSeconds(1));
    sameRefusal(incident, "noc-l1", "already_acknowledged");

    UUID other = notifiedIncident();
    sameRefusal(other, "dbops-engineer", "not_notified");

    UUID resolved = notifiedIncident();
    f.notifications.resolve(resolved);
    sameRefusal(resolved, "noc-l1", "not_open");
  }

  @Test
  @DisplayName("AR-I-07 the incident and dashboard operations of P-43: 401 and 403")
  void access() {
    UUID incident = notifiedIncident();
    api.noView(f);
    List<String> reads =
        List.of(
            "/api/v1/incidents/" + incident + "/investigation",
            "/api/v1/incidents/" + incident + "/response",
            "/api/v1/incidents/" + incident + "/recovery",
            page(incident),
            "/api/v1/dashboard");
    for (String path : reads) {
      assertThat(api.client.get(path).host(f.notifications.host).send().status())
          .as("%s without a session", path)
          .isEqualTo(401);
      Response forbidden = send("admin", api.client.get(path));
      assertThat(forbidden.status()).as("%s as admin", path).isEqualTo(403);
      assertThat(forbidden.json().get("error").get("code").stringValue()).isEqualTo("forbidden");
    }
    assertThat(api.client.post(action(incident)).host(f.notifications.host).send().status())
        .isEqualTo(401);
    assertThat(send("admin", api.client.post(action(incident))).status()).isEqualTo(403);
    assertThat(send("presenter", api.client.post(action(incident))).status()).isEqualTo(403);
    assertThat(incidents.incident(f.tenant, incident).orElseThrow().acknowledgedBy()).isNull();
  }
}
