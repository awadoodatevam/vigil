package vigil.ticketing;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import tools.jackson.databind.JsonNode;
import vigil.connectors.Recordings;
import vigil.connectors.api.ConnectorInstanceView;
import vigil.connectors.api.ConnectorKind;
import vigil.connectors.api.ConnectorService;
import vigil.connectors.api.TicketingConnector;
import vigil.incidents.api.EscalationCause;
import vigil.incidents.api.IncidentService;
import vigil.shared.Ids;
import vigil.testsupport.TestSecrets;
import vigil.testsupport.VigilDatabase;
import vigil.testsupport.VigilTestApp;
import vigil.ticketing.api.SyncOperation;
import vigil.ticketing.api.SyncOutcome;
import vigil.ticketing.api.SyncRecordView;
import vigil.ticketing.api.SyncRequester;
import vigil.ticketing.api.TicketLinkState;
import vigil.ticketing.api.TicketingService;
import vigil.ticketing.api.TicketingStepHandler;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RecordEscalationIT {

  static final String COMMENT = "/rest/api/3/issue/NOC-1/comment";
  static final List<String> TEAMS = List.of("NOC Shift Lead", "Database Operations");
  static final Duration TIMER = Duration.ofSeconds(120);

  private WireMockServer wiremock;
  private VigilTestApp app;
  private TestSecrets secrets;
  private TicketingService ticketing;
  private String jira;
  private int count;

  @BeforeAll
  void start() {
    wiremock = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort());
    wiremock.start();
    jira = "http://127.0.0.1:" + wiremock.port();
    VigilDatabase database = VigilDatabase.fresh();
    secrets = TestSecrets.create(database, TicketingFixture.secrets());
    app = VigilTestApp.start(database, secrets.folder(), List.of());
    app.awaitStarted();
    ticketing = app.context().getBean(TicketingService.class);
  }

  @AfterAll
  void stop() {
    app.close();
    wiremock.stop();
  }

  @BeforeEach
  void reset() {
    wiremock.resetAll();
    String create = Recordings.provider("jira", "0002-create.json", jira).responseBody();
    wiremock.stubFor(
        post(urlEqualTo("/rest/api/3/issue"))
            .willReturn(aResponse().withStatus(201).withBody(create)));
    String comment = Recordings.provider("jira", "0005-comment.json", jira).responseBody();
    wiremock.stubFor(
        post(urlEqualTo(COMMENT)).willReturn(aResponse().withStatus(201).withBody(comment)));
  }

  TicketingFixture tenant() {
    count++;
    return TicketingFixture.create(
        app, "Escalation " + count, "e" + count + ".ticketing.test", jira);
  }

  /** An open incident with its ticket NOC-1 made by a Ticket step. */
  UUID ticketed(TicketingFixture f) {
    UUID incident = f.openIncident();
    app.context()
        .getBean(TicketingStepHandler.class)
        .start(f.call(incident, Ids.newId(), "create"));
    assertThat(ticketing.ticketLink(f.tenant, incident)).isPresent();
    wiremock.resetRequests();
    return incident;
  }

  List<LoggedRequest> requests() {
    List<LoggedRequest> requests = new ArrayList<>(wiremock.findAll(anyRequestedFor(anyUrl())));
    return requests;
  }

  List<String> commentLines(LoggedRequest request) {
    List<String> lines = new ArrayList<>();
    for (JsonNode paragraph :
        Recordings.tree(request.getBodyAsString()).get("body").get("content")) {
      lines.add(paragraph.get("content").get(0).get("text").stringValue());
    }
    return lines;
  }

  SyncRecordView recordOf(TicketingFixture f, UUID incident, UUID escalation) {
    return ticketing.syncRecords(f.tenant, incident).stream()
        .filter(r -> escalation.equals(r.escalationId()))
        .findFirst()
        .orElseThrow();
  }

  List<String> evidenceLines(TicketingFixture f, UUID incident) {
    return app.context().getBean(IncidentService.class).evidenceSummary(f.tenant, incident).stream()
        .map(summary -> "- " + summary)
        .toList();
  }

  @Test
  @DisplayName("DC-OUT-02 the escalation comment: level, team names, cause and evidence")
  void comment() {
    TicketingFixture f = tenant();
    UUID incident = ticketed(f);
    UUID escalation = Ids.newId();
    SyncOutcome outcome =
        ticketing.recordEscalation(
            f.tenant,
            incident,
            escalation,
            1,
            TEAMS,
            EscalationCause.ACKNOWLEDGEMENT_TIMEOUT,
            TIMER);
    assertThat(outcome).isEqualTo(SyncOutcome.DONE);
    List<String> expected = new ArrayList<>();
    expected.add(
        "Escalated to level 1: Database Operations, NOC Shift Lead (not acknowledged within 2 min).");
    expected.add("Evidence:");
    expected.addAll(evidenceLines(f, incident));
    assertThat(expected).hasSizeGreaterThan(2);
    assertThat(requests()).hasSize(1);
    assertThat(commentLines(requests().getFirst())).isEqualTo(expected);
    SyncRecordView record = recordOf(f, incident, escalation);
    assertThat(record.operation()).isEqualTo(SyncOperation.RECORD_ESCALATION);
    assertThat(record.requestedBy()).isEqualTo(SyncRequester.ROUTING);
    assertThat(record.outcome()).isEqualTo(SyncOutcome.DONE);
    assertThat(record.stepExecutionId()).isNull();
    assertThat(record.ticketLinkId())
        .isEqualTo(ticketing.ticketLink(f.tenant, incident).orElseThrow().id());
    assertThat(
            Recordings.tree(record.contentSentJson())
                .get("comment")
                .valueStream()
                .map(JsonNode::stringValue)
                .toList())
        .isEqualTo(expected);

    wiremock.resetRequests();
    assertThat(
            ticketing.recordEscalation(
                f.tenant,
                incident,
                escalation,
                1,
                TEAMS,
                EscalationCause.ACKNOWLEDGEMENT_TIMEOUT,
                TIMER))
        .isEqualTo(SyncOutcome.DONE);
    assertThat(requests()).isEmpty();

    UUID pending = Ids.newId();
    Timestamp now = Timestamp.from(Instant.now());
    f.postgres()
        .sql(
            "INSERT INTO ticketing.ticket_sync_record (id, tenant_id, created_at, updated_at,"
                + " incident_id, ticket_link_id, operation, requested_by, escalation_id,"
                + " content_sent, outcome, at) VALUES (:id, :t, :now, :now, :i, :link,"
                + " 'record_escalation', 'routing', :e, CAST('{\"comment\":[]}' AS jsonb),"
                + " 'pending', :now)")
        .param("id", Ids.newId())
        .param("t", f.tenant.value())
        .param("now", now)
        .param("i", incident)
        .param("link", record.ticketLinkId())
        .param("e", pending)
        .update();
    assertThat(
            ticketing.recordEscalation(
                f.tenant, incident, pending, 2, List.of(), EscalationCause.ESCALATE_NOW, null))
        .isEqualTo(SyncOutcome.DONE);
    assertThat(requests()).hasSize(1);
    assertThat(commentLines(requests().getFirst()).getFirst())
        .isEqualTo("Escalated to level 2: none (workflow escalation step).");
    assertThat(Recordings.tree(recordOf(f, incident, pending).contentSentJson()).get("comment"))
        .hasSize(expected.size());

    UUID noTicket = f.openIncident();
    UUID failed = Ids.newId();
    assertThat(
            ticketing.recordEscalation(
                f.tenant,
                noTicket,
                failed,
                1,
                TEAMS,
                EscalationCause.ACKNOWLEDGEMENT_TIMEOUT,
                TIMER))
        .isEqualTo(SyncOutcome.FAILED);
    SyncRecordView noLink = recordOf(f, noTicket, failed);
    assertThat(noLink.error()).isEqualTo("no ticket");
    assertThat(noLink.contentSentJson()).isEqualTo("{}");

    assertThatThrownBy(
            () ->
                ticketing.recordEscalation(
                    f.tenant,
                    incident,
                    Ids.newId(),
                    1,
                    TEAMS,
                    EscalationCause.ACKNOWLEDGEMENT_TIMEOUT,
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("timer: required for acknowledgement_timeout only");
    assertThatThrownBy(
            () ->
                ticketing.recordEscalation(
                    f.tenant, incident, Ids.newId(), 1, TEAMS, EscalationCause.ESCALATE_NOW, TIMER))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("timer: required for acknowledgement_timeout only");
    assertThatThrownBy(
            () ->
                ticketing.recordEscalation(
                    f.tenant, incident, null, 1, TEAMS, EscalationCause.ESCALATE_NOW, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("a required argument is missing");
  }

  @Test
  @DisplayName("PM-M19-01 the escalation comment changes the ticket only by a comment")
  void escalationOnlyComments() {
    TicketingFixture f = tenant();
    UUID incident = ticketed(f);
    ticketing.recordEscalation(
        f.tenant, incident, Ids.newId(), 1, TEAMS, EscalationCause.ACKNOWLEDGEMENT_TIMEOUT, TIMER);
    assertThat(requests())
        .extracting(r -> r.getMethod().getName() + " " + r.getUrl())
        .containsExactly("POST " + COMMENT);
    assertThat(ticketing.ticketLink(f.tenant, incident).orElseThrow().state())
        .isEqualTo(TicketLinkState.OPEN);
  }

  @Test
  @DisplayName("AR-K-00c the TicketingConnector bean of kind jira_cloud serves the instance")
  void connectorType() {
    TicketingFixture f = tenant();
    List<TicketingConnector> beans =
        List.copyOf(app.context().getBeansOfType(TicketingConnector.class).values());
    assertThat(beans)
        .extracting(TicketingConnector::kind)
        .containsExactly(ConnectorKind.JIRA_CLOUD);
    ConnectorInstanceView instance =
        app.context()
            .getBean(ConnectorService.class)
            .activeInstances(f.tenant, ConnectorKind.JIRA_CLOUD)
            .getFirst();
    assertThat(instance.id()).isEqualTo(f.jiraInstanceId);
    UUID incident = ticketed(f);
    assertThat(ticketing.ticketLink(f.tenant, incident).orElseThrow().connectorInstanceId())
        .isEqualTo(f.jiraInstanceId);
  }

  @Test
  @DisplayName("PM-K-04 the jira_cloud connector sends with the jira secret's email and token")
  void jiraConnector() {
    TicketingFixture f = tenant();
    UUID incident = ticketed(f);
    ticketing.recordEscalation(
        f.tenant, incident, Ids.newId(), 1, TEAMS, EscalationCause.ACKNOWLEDGEMENT_TIMEOUT, TIMER);
    JsonNode secret = Recordings.tree(secrets.value("jira"));
    String basic =
        Base64.getEncoder()
            .encodeToString(
                (secret.get("email").stringValue() + ":" + secret.get("api_token").stringValue())
                    .getBytes(StandardCharsets.UTF_8));
    assertThat(requests().getFirst().getHeader("Authorization")).isEqualTo("Basic " + basic);
    assertThat(requests().getFirst().getHeader("Accept")).isEqualTo("application/json");
  }
}
