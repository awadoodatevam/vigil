package vigil.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.JsonNode;
import vigil.audit.api.AuditEventType;
import vigil.audit.api.AuditEventView;
import vigil.connectors.Recordings;
import vigil.incidents.api.IncidentService;
import vigil.incidents.api.NotifiedPurpose;
import vigil.incidents.api.NotifiedTeamView;
import vigil.notifications.api.Channel;
import vigil.notifications.api.DeliveryStatus;
import vigil.notifications.api.NotificationRecordView;
import vigil.notifications.api.NotificationsStepHandler;
import vigil.notifications.api.RecipientKind;
import vigil.shared.Actor;
import vigil.shared.Completed;
import vigil.shared.Ids;
import vigil.shared.Key;
import vigil.shared.StepAnswer;
import vigil.shared.StepCall;
import vigil.shared.StepCancel;
import vigil.testsupport.LogLines;
import vigil.ticketing.TicketingFixture;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class NotificationStepIT {

  static final String DEMO =
      "{\"recipients\": [\"owner_team\", \"informed_parties\", \"named_teams\"],"
          + " \"named_teams\": [\"noc-l1\"]}";
  static final String LABEL = TicketingFixture.LABEL;

  private NotificationsApp servers;
  private NotificationsStepHandler step;
  private IncidentService incidents;

  @BeforeAll
  void start() {
    servers = NotificationsApp.start();
    step = servers.app.context().getBean(NotificationsStepHandler.class);
    incidents = servers.app.context().getBean(IncidentService.class);
  }

  @AfterAll
  void stop() {
    servers.close();
  }

  @BeforeEach
  void reset() {
    servers.reset();
  }

  private NotificationsFixture tenant() {
    return servers.tenant("Notify");
  }

  private static String link(NotificationsFixture f, UUID incident) {
    return f.acknowledgeLink(incident);
  }

  private List<NotifiedTeamView> notified(NotificationsFixture f, UUID incident) {
    return incidents.notifiedTeams(f.tenant, incident);
  }

  private static String key(NotificationRecordView record) {
    return record.recipientKey() + " " + record.channel().key();
  }

  @Test
  @DisplayName("SL-A1-05 the incident notification to the owner, informed teams and NOC L1")
  void demoStep() throws Exception {
    NotificationsFixture f = tenant();
    UUID incident = f.openIncident();
    UUID execution = Ids.newId();
    StepAnswer answer = step.start(f.notification(incident, execution, DEMO));
    assertThat(servers.teamsKeys())
        .containsExactly("db-ops", "customer-care", "vas-service-owners", "noc-l1");
    assertThat(servers.emailRecipients())
        .containsExactly(
            "db-ops@vigil.test",
            "customer-care@vigil.test",
            "vas-service-owners@vigil.test",
            "noc-l1@vigil.test");
    List<NotificationRecordView> records = servers.records(f, incident);
    assertThat(records).hasSize(8);
    assertThat(records.stream().map(NotificationStepIT::key).toList())
        .containsExactly(
            "db-ops teams",
            "db-ops email",
            "customer-care teams",
            "customer-care email",
            "vas-service-owners teams",
            "vas-service-owners email",
            "noc-l1 teams",
            "noc-l1 email");
    String link = link(f, incident);
    String content = String.join("\n", NotificationsApp.teamsTexts(servers.teamsPosts().get(0)));
    for (NotificationRecordView record : records) {
      assertThat(record.status()).isEqualTo(DeliveryStatus.SENT);
      assertThat(record.error()).isNull();
      assertThat(record.link()).isEqualTo(link);
      assertThat(record.purpose()).isEqualTo(NotifiedPurpose.INCIDENT_NOTIFICATION);
      assertThat(record.content()).isEqualTo(content.replace("[" + link + "](" + link + ")", link));
      assertThat(record.content().split("\n")).hasSize(5);
      assertThat(record.content()).endsWith("Acknowledge: " + link);
    }
    assertThat(records.get(0).address()).isEqualTo("Techfinity Demo / Database Operations");
    assertThat(records.get(1).address()).isEqualTo("db-ops@vigil.test");
    assertThat(records.get(6).address()).isEqualTo("Techfinity Demo / NOC L1");
    assertThat(records.get(0).content().split("\n")[1])
        .isEqualTo("Owner: Database Operations. Informed: Customer Care, VAS Service Owners.");
    assertThat(notified(f, incident))
        .extracting(NotifiedTeamView::teamKey)
        .containsExactlyInAnyOrder("db-ops", "customer-care", "vas-service-owners", "noc-l1");
    assertThat(notified(f, incident))
        .allMatch(team -> team.purpose() == NotifiedPurpose.INCIDENT_NOTIFICATION);
    assertThat(servers.events(f, AuditEventType.NOTIFICATION_SENT)).hasSize(8);
    assertThat(answer)
        .isEqualTo(
            new Completed(
                new Key("sent"),
                new Completed.ResultRef(
                    "vigil-notifications", "NotificationRecord", records.getFirst().id())));
    MimeMessage mail = servers.emails().getFirst();
    assertThat(mail.getSubject()).isEqualTo(records.getFirst().content().split("\n")[0]);
  }

  @Test
  @DisplayName("PM-M20-01 the Notification step sends once and answers the same when called again")
  void notificationStep() {
    NotificationsFixture f = tenant();
    UUID incident = f.openIncident();
    UUID execution = Ids.newId();
    StepCall call = f.notification(incident, execution, "{\"recipients\": [\"owner_team\"]}");
    StepAnswer first = step.start(call);
    StepAnswer again = step.evaluate(call);
    assertThat(again).isEqualTo(first);
    assertThat(servers.teamsPosts()).hasSize(1);
    assertThat(servers.emails()).hasSize(1);
    assertThat(servers.records(f, incident)).hasSize(2);
    step.cancel(
        new StepCancel(
            f.tenant, call.runId(), incident, execution, call.stepId(), call.stepType()));
    step.cancel(
        new StepCancel(
            f.tenant, call.runId(), incident, execution, call.stepId(), call.stepType()));
    assertThat(servers.records(f, incident)).hasSize(2);
  }

  @Test
  @DisplayName("PM-M20-02 every active address of the recipient, Teams and email")
  void everyActiveAddress() {
    NotificationsFixture f = tenant();
    UUID incident = f.openIncident();
    step.start(
        f.notification(
            incident,
            Ids.newId(),
            "{\"recipients\": [\"named_teams\"], \"named_teams\": [\"db-ops\", \"it-infra\"]}"));
    assertThat(servers.records(f, incident).stream().map(NotificationStepIT::key).toList())
        .containsExactly("db-ops teams", "db-ops email");
    f.postgres()
        .sql(
            "UPDATE notifications.delivery_address SET active = false WHERE tenant_id = :t"
                + " AND subject_key = 'noc-l1' AND channel = 'teams'")
        .param("t", f.tenant.value())
        .update();
    servers.reset();
    UUID other = f.openIncident();
    step.start(
        f.notification(
            other,
            Ids.newId(),
            "{\"recipients\": [\"named_teams\"], \"named_teams\": [\"noc-l1\"]}"));
    assertThat(servers.records(f, other).stream().map(NotificationStepIT::key).toList())
        .containsExactly("noc-l1 email");
    assertThat(servers.teamsPosts()).isEmpty();
  }

  @Test
  @DisplayName("PM-M20-05 recipients from the step's parameters; each notified team recorded")
  void recipients() {
    NotificationsFixture f = tenant();
    UUID owner = f.openIncident();
    step.start(f.notification(owner, Ids.newId(), "{\"recipients\": [\"owner_team\"]}"));
    assertThat(servers.teamsKeys()).containsExactly("db-ops");
    assertThat(notified(f, owner)).extracting(NotifiedTeamView::teamKey).containsExactly("db-ops");

    servers.reset();
    UUID informed = f.openIncident();
    step.start(f.notification(informed, Ids.newId(), "{\"recipients\": [\"informed_parties\"]}"));
    assertThat(servers.teamsKeys()).containsExactly("customer-care", "vas-service-owners");

    servers.reset();
    UUID named = f.openIncident();
    step.start(
        f.notification(
            named,
            Ids.newId(),
            "{\"recipients\": [\"named_teams\", \"owner_team\"],"
                + " \"named_teams\": [\"noc-management\", \"it-apps\", \"db-ops\"]}"));
    assertThat(servers.teamsKeys()).containsExactly("db-ops", "noc-management", "it-apps");
    assertThat(notified(f, named))
        .extracting(NotifiedTeamView::teamKey)
        .containsExactlyInAnyOrder("db-ops", "noc-management", "it-apps");
  }

  @Test
  @DisplayName("PM-S-09 the Notification step's outputs sent and failed")
  void outputs() {
    NotificationsFixture f = tenant();
    UUID incident = f.openIncident();
    Completed sent =
        (Completed)
            step.start(f.notification(incident, Ids.newId(), "{\"recipients\": [\"owner_team\"]}"));
    assertThat(sent.namedOutput()).isEqualTo(new Key("sent"));
    f.active(f.teamsInstanceId, false);
    f.active(f.emailInstanceId, false);
    UUID execution = Ids.newId();
    Completed failed =
        (Completed)
            step.start(f.notification(incident, execution, "{\"recipients\": [\"owner_team\"]}"));
    assertThat(failed.namedOutput()).isEqualTo(new Key("failed"));
    assertThat(failed.resultRef().id())
        .isEqualTo(
            servers.records(f, incident).stream()
                .filter(r -> r.originId().equals(execution))
                .findFirst()
                .orElseThrow()
                .id());
    Completed none =
        (Completed)
            step.start(
                f.notification(
                    incident,
                    Ids.newId(),
                    "{\"recipients\": [\"named_teams\"], \"named_teams\": [\"it-infra\"]}"));
    assertThat(none).isEqualTo(new Completed(new Key("failed"), null));
  }

  @Test
  @DisplayName("DM-S-09 recipients and named_teams are checked; the step never waits")
  void parameters() {
    NotificationsFixture f = tenant();
    UUID incident = f.openIncident();
    for (String parameters :
        List.of(
            "{}",
            "[]",
            "not json",
            "{\"recipients\": []}",
            "{\"recipients\": \"owner_team\"}",
            "{\"recipients\": [\"everyone\"]}",
            "{\"recipients\": [\"named_teams\"]}",
            "{\"recipients\": [\"named_teams\"], \"named_teams\": [\"not a key\"]}",
            "{\"recipients\": [\"named_teams\"], \"named_teams\": \"noc-l1\"}")) {
      assertThatThrownBy(() -> step.start(f.notification(incident, Ids.newId(), parameters)))
          .as(parameters)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("notification step parameters: recipients");
    }
    StepCall wait =
        new StepCall(
            f.tenant,
            Ids.newId(),
            incident,
            Ids.newId(),
            new Key("s-wait"),
            StepCall.StepType.WAIT,
            DEMO,
            1,
            null,
            new StepCall.WakeReason("start"),
            null,
            null);
    assertThatThrownBy(() -> step.start(wait))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("notification step parameters: recipients");
    assertThatThrownBy(() -> step.start(f.notification(Ids.newId(), Ids.newId(), DEMO)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageEndingWith(" not found");
    assertThat(servers.teamsPosts()).isEmpty();
    assertThat(step.start(f.notification(incident, Ids.newId(), DEMO)))
        .isInstanceOf(Completed.class);
    assertThat(step.evaluate(f.notification(incident, Ids.newId(), DEMO)))
        .isInstanceOf(Completed.class);
  }

  @Test
  @DisplayName("DM-E-78 the notification record row: origin, recipient, channel, content, status")
  void recordRow() {
    NotificationsFixture f = tenant();
    UUID incident = f.openIncident();
    UUID execution = Ids.newId();
    step.start(f.notification(incident, execution, "{\"recipients\": [\"owner_team\"]}"));
    List<Map<String, Object>> rows =
        f.postgres()
            .sql(
                "SELECT * FROM notifications.notification_record WHERE tenant_id = :t"
                    + " ORDER BY at, id")
            .param("t", f.tenant.value())
            .query()
            .listOfRows();
    assertThat(rows).hasSize(2);
    Map<String, Object> row = rows.getFirst();
    assertThat(row.get("incident_id")).isEqualTo(incident);
    assertThat(row.get("purpose")).isEqualTo("incident_notification");
    assertThat(row.get("origin_module")).isEqualTo("vigil-engine");
    assertThat(row.get("origin_entity")).isEqualTo("StepExecution");
    assertThat(row.get("origin_id")).isEqualTo(execution);
    assertThat(row.get("recipient_kind")).isEqualTo("team");
    assertThat(row.get("recipient_key")).isEqualTo("db-ops");
    assertThat(row.get("via_team_key")).isNull();
    assertThat(row.get("channel")).isEqualTo("teams");
    assertThat(row.get("address")).isEqualTo("Techfinity Demo / Database Operations");
    assertThat((String) row.get("content")).startsWith(LABEL + " - [P1] INC-");
    assertThat(row.get("link")).isEqualTo(link(f, incident));
    assertThat(row.get("status")).isEqualTo("sent");
    assertThat(row.get("error")).isNull();
    assertThat(((Timestamp) row.get("at")).toInstant())
        .isEqualTo(((Timestamp) row.get("created_at")).toInstant());
    assertThat(rows.get(1).get("channel")).isEqualTo("email");
    assertThat(rows.get(1).get("address")).isEqualTo("db-ops@vigil.test");
    NotificationRecordView view = servers.records(f, incident).getFirst();
    assertThat(view.recipientKind()).isEqualTo(RecipientKind.TEAM);
    assertThat(view.channel()).isEqualTo(Channel.TEAMS);
  }

  @Test
  @DisplayName("DM-E08-R3 one notification_sent event per record, written by vigil-notifications")
  void auditEvents() {
    NotificationsFixture f = tenant();
    UUID incident = f.openIncident();
    servers.teamsAnswer(500);
    step.start(f.notification(incident, Ids.newId(), "{\"recipients\": [\"owner_team\"]}"));
    List<NotificationRecordView> records = servers.records(f, incident);
    List<AuditEventView> events = servers.events(f, AuditEventType.NOTIFICATION_SENT);
    assertThat(events).hasSize(2);
    for (NotificationRecordView record : records) {
      AuditEventView event =
          events.stream().filter(e -> e.subjectId().equals(record.id())).findFirst().orElseThrow();
      assertThat(event.actor()).isEqualTo(Actor.module("vigil-notifications"));
      assertThat(event.subjectType()).isEqualTo("NotificationRecord");
      assertThat(event.incidentId()).isEqualTo(incident);
      JsonNode details = Recordings.tree(event.detailsJson());
      assertThat(details.get("purpose").stringValue()).isEqualTo("incident_notification");
      assertThat(details.get("channel").stringValue()).isEqualTo(record.channel().key());
      assertThat(details.get("status").stringValue()).isEqualTo(record.status().key());
      assertThat(details.size()).isEqualTo(3);
    }
    assertThat(events.stream().map(AuditEventView::summary).toList())
        .containsExactlyInAnyOrder(
            "incident notification to Database Operations by Teams: failed.",
            "incident notification to Database Operations by email: sent.");
  }

  @Test
  @DisplayName("AR-I-101 the Acknowledge link uses the tenant's public base address")
  void acknowledgeLink() {
    NotificationsFixture f = tenant();
    UUID incident = f.openIncident();
    step.start(f.notification(incident, Ids.newId(), "{\"recipients\": [\"owner_team\"]}"));
    String link = link(f, incident);
    LoggedRequest post = servers.teamsPosts().getFirst();
    assertThat(NotificationsApp.teamsTexts(post).getLast())
        .isEqualTo("Acknowledge: [" + link + "](" + link + ")");
    assertThat(servers.records(f, incident)).allMatch(r -> r.link().equals(link));
  }

  @Test
  @DisplayName("SL-DR-03 every message starts with the environment label")
  void environmentLabel() throws Exception {
    NotificationsFixture f = tenant();
    UUID incident = f.openIncident();
    step.start(f.notification(incident, Ids.newId(), DEMO));
    for (LoggedRequest post : servers.teamsPosts()) {
      assertThat(NotificationsApp.teamsTexts(post).getFirst()).startsWith(LABEL + " - [P1] INC-");
    }
    assertThat(servers.emails()).hasSize(4);
    for (MimeMessage mail : servers.emails()) {
      assertThat(mail.getSubject()).startsWith(LABEL + " - [P1] INC-");
      String body = new String(mail.getRawInputStream().readAllBytes(), StandardCharsets.UTF_8);
      assertThat(body).startsWith(mail.getSubject() + "\r\n");
    }
  }

  @Test
  @DisplayName("PM-M20-04 every message failed: records failed with reasons, output failed, WARN")
  void allFailed(CapturedOutput output) {
    NotificationsFixture f = tenant();
    UUID incident = f.openIncident();
    servers.teamsAnswer(500);
    f.postgres()
        .sql(
            "UPDATE connectors.connector_instance SET address = 'smtp://127.0.0.1:1'"
                + " WHERE id = :id")
        .param("id", f.emailInstanceId)
        .update();
    Completed answer = (Completed) step.start(f.notification(incident, Ids.newId(), DEMO));
    assertThat(answer.namedOutput()).isEqualTo(new Key("failed"));
    List<NotificationRecordView> records = servers.records(f, incident);
    assertThat(records).hasSize(8).allMatch(r -> r.status() == DeliveryStatus.FAILED);
    assertThat(records.stream().filter(r -> r.channel() == Channel.TEAMS))
        .allMatch(r -> r.error().equals("HTTP 500"));
    assertThat(records.stream().filter(r -> r.channel() == Channel.EMAIL))
        .allMatch(r -> r.error().equals("Could not connect to 127.0.0.1:1."));
    assertThat(notified(f, incident)).isEmpty();
    long warnings =
        LogLines.application(output.getAll()).stream()
            .filter(line -> line.path("log").path("level").asString("").equals("WARN"))
            .map(line -> line.path("message").asString(""))
            .filter(
                message ->
                    message.startsWith("incident_notification message to team ")
                        && message.contains(" failed for incident " + incident + " of tenant "))
            .count();
    assertThat(warnings).isEqualTo(8);
  }

  @Test
  @DisplayName("PM-M20-04 no active email instance, or no public address: those records failed")
  void failedBeforeSending() {
    NotificationsFixture f = tenant();
    UUID incident = f.openIncident();
    f.active(f.emailInstanceId, false);
    Completed answer =
        (Completed)
            step.start(f.notification(incident, Ids.newId(), "{\"recipients\": [\"owner_team\"]}"));
    assertThat(answer.namedOutput()).isEqualTo(new Key("sent"));
    List<NotificationRecordView> records = servers.records(f, incident);
    assertThat(records.get(0).status()).isEqualTo(DeliveryStatus.SENT);
    assertThat(records.get(1).status()).isEqualTo(DeliveryStatus.FAILED);
    assertThat(records.get(1).error()).isEqualTo("no active email connector instance");
    f.active(f.emailInstanceId, true);
    f.active(f.teamsInstanceId, false);
    UUID other = f.openIncident();
    step.start(f.notification(other, Ids.newId(), "{\"recipients\": [\"owner_team\"]}"));
    assertThat(servers.records(f, other).getFirst().error())
        .isEqualTo("no active Teams connector instance");

    servers.reset();
    NotificationsFixture noAlias =
        NotificationsFixture.create(servers.app, "No alias", null, servers.base, servers.smtpPort);
    UUID unlinked = noAlias.openIncident();
    Completed failed = (Completed) step.start(noAlias.notification(unlinked, Ids.newId(), DEMO));
    assertThat(failed.namedOutput()).isEqualTo(new Key("failed"));
    assertThat(servers.records(noAlias, unlinked))
        .hasSize(8)
        .allMatch(r -> r.error().equals("the tenant has no public address for links"))
        .allMatch(r -> r.link().isEmpty());
    assertThat(servers.teamsPosts()).isEmpty();
    assertThat(servers.emails()).isEmpty();
  }

  @Test
  @DisplayName("DM-R-65 called again after a partial failure, only what was not sent is sent")
  void noDuplicate() {
    NotificationsFixture f = tenant();
    UUID incident = f.openIncident();
    f.postgres()
        .sql(
            "UPDATE notifications.delivery_address SET active = false WHERE tenant_id = :t"
                + " AND subject_key = 'noc-l1' AND channel = 'email'")
        .param("t", f.tenant.value())
        .update();
    servers.teamsAnswer(500);
    StepCall call = f.notification(incident, Ids.newId(), DEMO);
    assertThat(((Completed) step.start(call)).namedOutput()).isEqualTo(new Key("sent"));
    assertThat(servers.emails()).hasSize(3);
    assertThat(notified(f, incident))
        .extracting(NotifiedTeamView::teamKey)
        .containsExactlyInAnyOrder("db-ops", "customer-care", "vas-service-owners");
    servers.reset();
    step.evaluate(call);
    assertThat(servers.emails()).isEmpty();
    assertThat(servers.teamsKeys())
        .containsExactly("db-ops", "customer-care", "vas-service-owners", "noc-l1");
    List<NotificationRecordView> records = servers.records(f, incident);
    assertThat(records).hasSize(11);
    assertThat(records.stream().filter(r -> r.status() == DeliveryStatus.SENT)).hasSize(7);
    assertThat(notified(f, incident))
        .extracting(NotifiedTeamView::teamKey)
        .containsExactlyInAnyOrder("db-ops", "customer-care", "vas-service-owners", "noc-l1");
    servers.reset();
    step.evaluate(call);
    assertThat(servers.teamsPosts()).isEmpty();
    assertThat(servers.emails()).isEmpty();
    assertThat(servers.records(f, incident)).hasSize(11);
  }

  @Test
  @DisplayName("AR-K-17 a webhook address never appears in a record, the log or an audit event")
  void webhookHidden(CapturedOutput output) {
    NotificationsFixture f = tenant();
    UUID incident = f.openIncident();
    step.start(f.notification(incident, Ids.newId(), DEMO));
    servers.teamsAnswer(500);
    UUID other = f.openIncident();
    step.start(f.notification(other, Ids.newId(), DEMO));
    List<String> hidden = servers.hidden();
    List<NotificationRecordView> records = new ArrayList<>(servers.records(f, incident));
    records.addAll(servers.records(f, other));
    for (NotificationRecordView record : records) {
      for (String value : hidden) {
        assertThat(record.toString()).doesNotContain(value);
      }
    }
    for (AuditEventView event : servers.events(f, AuditEventType.NOTIFICATION_SENT)) {
      for (String value : hidden) {
        assertThat(event.summary() + event.detailsJson()).doesNotContain(value);
      }
    }
    LogLines.assertNoneContains(output.getAll(), hidden);
  }
}
