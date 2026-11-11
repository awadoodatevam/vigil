package vigil.ticketing;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.sql.Timestamp;
import java.time.Instant;
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
import vigil.connectors.Recordings;
import vigil.incidents.api.AffectedServiceCopy;
import vigil.incidents.api.IncidentService;
import vigil.incidents.api.IncidentView;
import vigil.investigation.api.InvestigationService;
import vigil.shared.Completed;
import vigil.shared.Ids;
import vigil.shared.StepAnswer;
import vigil.shared.StepCall;
import vigil.testsupport.LogLines;
import vigil.testsupport.TestSecrets;
import vigil.testsupport.VigilDatabase;
import vigil.testsupport.VigilTestApp;
import vigil.ticketing.api.SyncOperation;
import vigil.ticketing.api.SyncOutcome;
import vigil.ticketing.api.SyncRecordView;
import vigil.ticketing.api.SyncRequester;
import vigil.ticketing.api.TicketLinkState;
import vigil.ticketing.api.TicketLinkView;
import vigil.ticketing.api.TicketingService;
import vigil.ticketing.api.TicketingStepHandler;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class TicketStepIT {

  static final String ISSUE = "/rest/api/3/issue";
  static final String SEARCH = "/rest/api/3/search/jql";
  static final String COMMENT = "/rest/api/3/issue/NOC-1/comment";
  static final String TRANSITIONS = "/rest/api/3/issue/NOC-1/transitions";
  static final String STATUS = "/rest/api/3/issue/NOC-1?fields=status";
  static final String NONE_FOUND = "{\"issues\": [], \"isLast\": true}";

  private WireMockServer wiremock;
  private VigilTestApp app;
  private TicketingStepHandler step;
  private TicketingService ticketing;
  private IncidentService incidents;
  private InvestigationService investigation;
  private String jira;
  private int count;

  @BeforeAll
  void start() {
    wiremock = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort());
    wiremock.start();
    jira = "http://127.0.0.1:" + wiremock.port();
    VigilDatabase database = VigilDatabase.fresh();
    TestSecrets secrets = TestSecrets.create(database, TicketingFixture.secrets());
    app =
        VigilTestApp.start(
            database, secrets.folder(), List.of("--vigil.links.base=http://{host}:8080"));
    app.awaitStarted();
    step = app.context().getBean(TicketingStepHandler.class);
    ticketing = app.context().getBean(TicketingService.class);
    incidents = app.context().getBean(IncidentService.class);
    investigation = app.context().getBean(InvestigationService.class);
  }

  @AfterAll
  void stop() {
    app.close();
    wiremock.stop();
  }

  @BeforeEach
  void reset() {
    wiremock.resetAll();
    answer("POST", ISSUE, recorded("0002-create.json"));
    stub(get(urlPathEqualTo(SEARCH)), 200, NONE_FOUND);
    answer("POST", COMMENT, recorded("0005-comment.json"));
    answer("GET", TRANSITIONS, recorded("0007-transitions-1.json"));
    stub(post(urlEqualTo(TRANSITIONS)), 204, "");
    answer("GET", STATUS, recorded("0006-status-1.json"));
  }

  // Helpers.

  String host() {
    return "t" + count + ".ticketing.test";
  }

  TicketingFixture tenant() {
    count++;
    return TicketingFixture.create(app, "Ticketing " + count, host(), jira);
  }

  String recorded(String name) {
    return Recordings.provider("jira", name, jira).responseBody();
  }

  void answer(String method, String url, String body) {
    stub(
        method.equals("POST") ? post(urlEqualTo(url)) : get(urlEqualTo(url)),
        method.equals("POST") ? 201 : 200,
        body);
  }

  void stub(
      com.github.tomakehurst.wiremock.client.MappingBuilder request, int status, String body) {
    wiremock.stubFor(
        request.willReturn(
            aResponse()
                .withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody(body)));
  }

  /** The requests Jira received, oldest first (as WireMock lists them). */
  List<LoggedRequest> requests() {
    List<LoggedRequest> requests = new ArrayList<>(wiremock.findAll(anyRequestedFor(anyUrl())));
    return requests;
  }

  List<String> calls() {
    return requests().stream().map(r -> r.getMethod().getName() + " " + r.getUrl()).toList();
  }

  StepAnswer run(TicketingFixture f, UUID incident, String operation) {
    return step.start(f.call(incident, Ids.newId(), operation));
  }

  static String output(StepAnswer answer) {
    return ((Completed) answer).namedOutput().value();
  }

  static Completed.ResultRef result(StepAnswer answer) {
    return ((Completed) answer).resultRef();
  }

  TicketLinkView link(TicketingFixture f, UUID incident) {
    return ticketing.ticketLink(f.tenant, incident).orElseThrow();
  }

  List<SyncRecordView> records(TicketingFixture f, UUID incident) {
    return ticketing.syncRecords(f.tenant, incident);
  }

  IncidentView view(TicketingFixture f, UUID incident) {
    return incidents.incident(f.tenant, incident).orElseThrow();
  }

  List<String> audit(TicketingFixture f) {
    return f.postgres()
        .sql(
            "SELECT summary FROM audit.audit_event WHERE tenant_id = :t"
                + " AND event_type = 'ticket_changed' ORDER BY sequence")
        .param("t", f.tenant.value())
        .query(String.class)
        .list();
  }

  /** The text of each ADF paragraph. */
  static List<String> paragraphs(JsonNode adf) {
    List<String> lines = new ArrayList<>();
    for (JsonNode paragraph : adf.get("content")) {
      StringBuilder text = new StringBuilder();
      JsonNode nodes = paragraph.get("content");
      if (nodes != null) {
        nodes.forEach(node -> text.append(node.get("text").stringValue()));
      }
      lines.add(text.toString());
    }
    return lines;
  }

  JsonNode body(String method, String url) {
    return requests().stream()
        .filter(r -> r.getMethod().getName().equals(method) && r.getUrl().equals(url))
        .map(r -> Recordings.tree(r.getBodyAsString()))
        .findFirst()
        .orElseThrow();
  }

  /** An incident whose ticket NOC-1 was created by a Ticket step. */
  UUID created(TicketingFixture f) {
    UUID incident = f.openIncident();
    assertThat(output(run(f, incident, "create"))).isEqualTo("done");
    wiremock.resetRequests();
    return incident;
  }

  /** A record stored by the test, as after a stop. */
  UUID insertRecord(
      TicketingFixture f,
      UUID incident,
      UUID linkId,
      String operation,
      UUID stepExecution,
      String content,
      String outcome) {
    UUID id = Ids.newId();
    Timestamp now = Timestamp.from(Instant.now());
    f.postgres()
        .sql(
            "INSERT INTO ticketing.ticket_sync_record (id, tenant_id, created_at, updated_at,"
                + " incident_id, ticket_link_id, operation, requested_by, step_execution_id,"
                + " content_sent, outcome, error, at, finished_at) VALUES (:id, :t, :now, :now,"
                + " :i, :link, :op, 'workflow_step', :step, CAST(:content AS jsonb), :outcome,"
                + " :error, :now, :finished)")
        .param("id", id)
        .param("t", f.tenant.value())
        .param("now", now)
        .param("i", incident)
        .param("link", linkId)
        .param("op", operation)
        .param("step", stepExecution)
        .param("content", content)
        .param("outcome", outcome)
        .param("error", outcome.equals("failed") ? "earlier" : null)
        .param("finished", outcome.equals("pending") ? null : now)
        .update();
    return id;
  }

  List<String> expectedDescription(TicketingFixture f, UUID incident) {
    IncidentView view = view(f, incident);
    List<String> services =
        view.affectedServices().stream()
            .map(AffectedServiceCopy::name)
            .sorted(TicketTexts.CODE_POINTS)
            .toList();
    String summary = investigation.latestSummary(f.tenant, incident).orElseThrow().text();
    return List.of(
        "Incident: " + view.title(),
        "Alerts: 2 alerts. Types: Database write errors, Disk full.",
        "Customer impact: " + String.join(", ", services) + ".",
        "Root-cause hypothesis: "
            + view.hypothesis().name()
            + " (srv-db-01). A hypothesis from the service map, not a confirmed cause.",
        "Summary (template): " + summary,
        "Evidence:",
        "- /data is 100% full (15.9 of 15.9 GiB used, 0.1 GiB free)",
        "Open INC-"
            + String.format("%04d", view.number())
            + " in Techfinity: http://"
            + host()
            + ":8080/incidents/"
            + incident);
  }

  // Tests.

  @Test
  @DisplayName("PM-M19-02 create: prefixed title, seven description parts, label; link, record")
  void create() {
    TicketingFixture f = tenant();
    UUID incident = f.openIncident();
    StepAnswer answer = run(f, incident, "create");
    assertThat(calls()).containsExactly("POST " + ISSUE);
    JsonNode fields = body("POST", ISSUE).get("fields");
    IncidentView view = view(f, incident);
    assertThat(fields.get("summary").stringValue())
        .isEqualTo("Demo environment — fictional data - " + view.title());
    List<String> description = expectedDescription(f, incident);
    assertThat(paragraphs(fields.get("description"))).isEqualTo(description);
    assertThat(fields.get("labels").get(0).stringValue()).isEqualTo("vigil-" + incident);
    assertThat(fields.get("labels")).hasSize(1);
    assertThat(fields.get("project").get("key").stringValue()).isEqualTo("NOC");
    assertThat(fields.get("issuetype").get("name").stringValue()).isEqualTo("Task");
    TicketLinkView link = link(f, incident);
    assertThat(link.state()).isEqualTo(TicketLinkState.OPEN);
    assertThat(link.externalKey()).isEqualTo("NOC-1");
    assertThat(link.url()).isEqualTo(jira + "/browse/NOC-1");
    SyncRecordView record = records(f, incident).getFirst();
    assertThat(record.outcome()).isEqualTo(SyncOutcome.DONE);
    JsonNode content = Recordings.tree(record.contentSentJson());
    assertThat(content.get("title").stringValue()).isEqualTo(fields.get("summary").stringValue());
    assertThat(content.get("description").valueStream().map(JsonNode::stringValue).toList())
        .isEqualTo(description);
    assertThat(content.get("labels").get(0).stringValue()).isEqualTo("vigil-" + incident);
    assertThat(audit(f)).containsExactly("Jira NOC-1: create done.");
    String details =
        f.postgres()
            .sql(
                "SELECT details::text FROM audit.audit_event WHERE tenant_id = :t"
                    + " AND event_type = 'ticket_changed'")
            .param("t", f.tenant.value())
            .query(String.class)
            .single();
    assertThat(Recordings.tree(details))
        .isEqualTo(Recordings.tree("{\"operation\":\"create\",\"outcome\":\"done\"}"));
    assertThat(output(answer)).isEqualTo("done");
    assertThat(result(answer))
        .isEqualTo(new Completed.ResultRef("vigil-ticketing", "TicketSyncRecord", record.id()));
  }

  @Test
  @DisplayName("SL-A1-05 the Jira ticket of an incident with its content and its incident link")
  void ticketOfIncident() {
    TicketingFixture f = tenant();
    UUID incident = f.openIncident();
    run(f, incident, "create");
    JsonNode description = body("POST", ISSUE).get("fields").get("description");
    assertThat(paragraphs(description)).hasSize(8);
    JsonNode last = description.get("content").get(7).get("content").get(1);
    String address = "http://" + host() + ":8080/incidents/" + incident;
    assertThat(last.get("marks").get(0).get("attrs").get("href").stringValue()).isEqualTo(address);
    assertThat(ticketing.ticketLink(f.tenant, incident)).isPresent();
  }

  @Test
  @DisplayName("SL-DR-03 the environment label in the Jira title; no prefix without a label")
  void environmentLabel() {
    TicketingFixture f = tenant();
    UUID incident = f.openIncident();
    run(f, incident, "create");
    String title = view(f, incident).title();
    assertThat(body("POST", ISSUE).get("fields").get("summary").stringValue())
        .isEqualTo("Demo environment — fictional data - " + title);
    wiremock.resetRequests();
    f.withoutLabel();
    UUID second = f.openIncident();
    run(f, second, "create");
    assertThat(body("POST", ISSUE).get("fields").get("summary").stringValue())
        .isEqualTo(view(f, second).title());
  }

  @Test
  @DisplayName("DM-E-75 the ticket link row: incident, instance, key, url, state, times")
  void linkRow() {
    TicketingFixture f = tenant();
    UUID incident = f.openIncident();
    run(f, incident, "create");
    Map<String, Object> row =
        f.postgres()
            .sql("SELECT * FROM ticketing.ticket_link WHERE tenant_id = :t")
            .param("t", f.tenant.value())
            .query()
            .singleRow();
    assertThat(row.get("incident_id")).isEqualTo(incident);
    assertThat(row.get("connector_instance_id")).isEqualTo(f.jiraInstanceId);
    assertThat(row.get("external_key")).isEqualTo("NOC-1");
    assertThat(row.get("url")).isEqualTo(jira + "/browse/NOC-1");
    assertThat(row.get("state")).isEqualTo("open");
    assertThat(row.get("created_at")).isNotNull();
    assertThat(link(f, incident).id()).isEqualTo(row.get("id"));
  }

  @Test
  @DisplayName("DM-E-76 the sync record row: operation, requester, step, content, outcome, times")
  void recordRow() {
    TicketingFixture f = tenant();
    UUID incident = f.openIncident();
    UUID stepExecution = Ids.newId();
    step.start(f.call(incident, stepExecution, "create"));
    SyncRecordView record = records(f, incident).getFirst();
    assertThat(record.incidentId()).isEqualTo(incident);
    assertThat(record.ticketLinkId()).isEqualTo(link(f, incident).id());
    assertThat(record.operation()).isEqualTo(SyncOperation.CREATE);
    assertThat(record.requestedBy()).isEqualTo(SyncRequester.WORKFLOW_STEP);
    assertThat(record.stepExecutionId()).isEqualTo(stepExecution);
    assertThat(record.escalationId()).isNull();
    assertThat(record.error()).isNull();
    assertThat(record.finishedAt()).isAfterOrEqualTo(record.at());
    assertThat(Recordings.tree(record.contentSentJson()).propertyNames())
        .containsExactlyInAnyOrder("title", "description", "labels");
  }

  @Test
  @DisplayName("DM-SM14-T1 a created ticket gives the incident a link in state open")
  void linkOpen() {
    TicketingFixture f = tenant();
    UUID incident = f.openIncident();
    assertThat(ticketing.ticketLink(f.tenant, incident)).isEmpty();
    run(f, incident, "create");
    assertThat(link(f, incident).state()).isEqualTo(TicketLinkState.OPEN);
  }

  @Test
  @DisplayName("PM-S-10 the Ticket step leaves by done or by failed")
  void outputs() {
    TicketingFixture f = tenant();
    UUID incident = f.openIncident();
    assertThat(output(run(f, incident, "create"))).isEqualTo("done");
    assertThat(output(run(f, f.openIncident(), "resolve"))).isEqualTo("failed");
  }

  @Test
  @DisplayName("DM-S-10 operation create or resolve, another refused; never waits")
  void operations() {
    TicketingFixture f = tenant();
    UUID incident = f.openIncident();
    assertThat(step.start(f.call(incident, Ids.newId(), "create"))).isInstanceOf(Completed.class);
    assertThat(step.evaluate(f.call(incident, Ids.newId(), "resolve")))
        .isInstanceOf(Completed.class);
    for (String parameters :
        List.of("{\"operation\": \"update\"}", "{}", "[]", "not json", "{\"operation\": 1}")) {
      assertThatThrownBy(() -> step.start(f.call(incident, Ids.newId(), parameters, 1)))
          .as(parameters)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("ticket step parameters: operation create or resolve");
    }
    StepCall other =
        new StepCall(
            f.tenant,
            Ids.newId(),
            incident,
            Ids.newId(),
            new vigil.shared.Key("s-x"),
            StepCall.StepType.NOTIFICATION,
            "{\"operation\": \"create\"}",
            1,
            null,
            new StepCall.WakeReason("start"),
            null,
            null);
    assertThatThrownBy(() -> step.start(other)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("AR-I-100 the incident link starts with vigil.links.base and the tenant's alias")
  void incidentLink() {
    TicketingFixture f = tenant();
    UUID incident = f.openIncident();
    run(f, incident, "create");
    List<String> lines = paragraphs(body("POST", ISSUE).get("fields").get("description"));
    assertThat(lines.getLast()).endsWith(": http://" + host() + ":8080/incidents/" + incident);
  }

  @Test
  @DisplayName("DM-E08-R3 one ticket_changed event per finished sync record, by vigil-ticketing")
  void auditEvents() {
    TicketingFixture f = tenant();
    UUID incident = f.openIncident();
    run(f, incident, "create");
    run(f, incident, "create");
    run(f, f.openIncident(), "resolve");
    assertThat(audit(f))
        .containsExactly("Jira NOC-1: create done.", "Jira no ticket: resolve failed.");
    List<String> actors =
        f.postgres()
            .sql(
                "SELECT actor_module FROM audit.audit_event WHERE tenant_id = :t"
                    + " AND event_type = 'ticket_changed'")
            .param("t", f.tenant.value())
            .query(String.class)
            .list();
    assertThat(actors).containsOnly("vigil-ticketing");
    long subjects =
        f.postgres()
            .sql(
                "SELECT count(*) FROM audit.audit_event a JOIN ticketing.ticket_sync_record r"
                    + " ON r.id = a.subject_id WHERE a.tenant_id = :t"
                    + " AND a.subject_type = 'TicketSyncRecord'")
            .param("t", f.tenant.value())
            .query(Long.class)
            .single();
    assertThat(subjects).isEqualTo(2);
  }

  @Test
  @DisplayName("DM-E76-R1 create with an open link and resolve with a resolved link: no call")
  void existingLink() {
    TicketingFixture f = tenant();
    UUID incident = created(f);
    StepAnswer again = run(f, incident, "create");
    assertThat(output(again)).isEqualTo("done");
    assertThat(result(again)).isNull();
    assertThat(requests()).isEmpty();
    assertThat(records(f, incident)).hasSize(1);
    f.resolve(incident);
    assertThat(output(run(f, incident, "resolve"))).isEqualTo("done");
    wiremock.resetRequests();
    StepAnswer resolvedAgain = run(f, incident, "resolve");
    assertThat(output(resolvedAgain)).isEqualTo("done");
    assertThat(result(resolvedAgain)).isNull();
    assertThat(requests()).isEmpty();
    assertThat(records(f, incident)).hasSize(2);
  }

  /** The resolve scenario: an incident with its ticket, resolved with a recovery entry. */
  record Resolved(TicketingFixture f, UUID incident, StepAnswer answer) {}

  Resolved resolved() {
    TicketingFixture f = tenant();
    UUID incident = created(f);
    f.resolve(incident);
    return new Resolved(f, incident, run(f, incident, "resolve"));
  }

  static final List<String> RESOLVE_LINES =
      List.of(
          "Resolved: recovery verified.",
          TicketingFixture.RECOVERY_SUMMARY,
          "Conditions:",
          "- No 'Disk full' alert firing on Subscriber DB server: held",
          "- No 'Database write errors' alert firing on Subscriber database: held",
          "- Every monitoring source is healthy: held",
          "Observation window: 60 s, from 2026-11-11 09:12:00 UTC to 2026-11-11 09:13:00 UTC.",
          "Source: " + TicketingFixture.RECOVERY_SOURCE);

  @Test
  @DisplayName("DM-SM14-T2 resolve with the recovery evidence moves the link to resolved")
  void resolveLink() {
    Resolved r = resolved();
    assertThat(output(r.answer())).isEqualTo("done");
    assertThat(link(r.f(), r.incident()).state()).isEqualTo(TicketLinkState.RESOLVED);
    SyncRecordView record = records(r.f(), r.incident()).getLast();
    assertThat(record.operation()).isEqualTo(SyncOperation.RESOLVE);
    assertThat(record.outcome()).isEqualTo(SyncOutcome.DONE);
    assertThat(record.ticketLinkId()).isEqualTo(link(r.f(), r.incident()).id());
  }

  @Test
  @DisplayName("SL-A1-07 the Jira ticket resolved with the recovery evidence")
  void resolvedWithEvidence() {
    Resolved r = resolved();
    assertThat(paragraphs(body("POST", COMMENT).get("body"))).isEqualTo(RESOLVE_LINES);
    assertThat(link(r.f(), r.incident()).state()).isEqualTo(TicketLinkState.RESOLVED);
    assertThat(audit(r.f()))
        .containsExactly("Jira NOC-1: create done.", "Jira NOC-1: resolve done.");
  }

  @Test
  @DisplayName("PM-M19-03 the resolve comment is the recovery evidence of the evidence register")
  void resolveComment() {
    Resolved r = resolved();
    assertThat(paragraphs(body("POST", COMMENT).get("body"))).isEqualTo(RESOLVE_LINES);
    JsonNode content = Recordings.tree(records(r.f(), r.incident()).getLast().contentSentJson());
    assertThat(content.get("comment").valueStream().map(JsonNode::stringValue).toList())
        .isEqualTo(RESOLVE_LINES);
    assertThat(content.get("transition_to").stringValue()).isEqualTo("Done");
  }

  @Test
  @DisplayName("AR-K-16 comment first, then the transitions read and the move to Done")
  void commentThenTransition() {
    resolved();
    assertThat(calls())
        .containsExactly("POST " + COMMENT, "GET " + TRANSITIONS, "POST " + TRANSITIONS);
    assertThat(body("POST", TRANSITIONS))
        .isEqualTo(Recordings.tree("{\"transition\":{\"id\":\"31\"}}"));
  }

  @Test
  @DisplayName("PM-M19-04 resolve with no ticket or no recovery evidence fails with the reason")
  void resolveFailures() {
    TicketingFixture f = tenant();
    UUID noLink = f.openIncident();
    StepAnswer answer = run(f, noLink, "resolve");
    assertThat(output(answer)).isEqualTo("failed");
    SyncRecordView record = records(f, noLink).getFirst();
    assertThat(record.outcome()).isEqualTo(SyncOutcome.FAILED);
    assertThat(record.error()).isEqualTo("no ticket");
    assertThat(record.ticketLinkId()).isNull();
    assertThat(record.contentSentJson()).isEqualTo("{}");
    assertThat(record.finishedAt()).isEqualTo(record.at());
    assertThat(result(answer).id()).isEqualTo(record.id());
    assertThat(audit(f)).containsExactly("Jira no ticket: resolve failed.");
    UUID incident = created(f);
    StepAnswer noEvidence = run(f, incident, "resolve");
    assertThat(output(noEvidence)).isEqualTo("failed");
    assertThat(records(f, incident).getLast().error()).isEqualTo("no recovery evidence");
    assertThat(requests()).isEmpty();
  }

  @Test
  @DisplayName("PM-M19-04 Jira refuses or does not answer; the next create searches the label")
  void jiraFailures(CapturedOutput output) {
    TicketingFixture f = tenant();
    UUID incident = f.openIncident();
    stub(post(urlEqualTo(ISSUE)), 500, "{}");
    StepAnswer refused = run(f, incident, "create");
    assertThat(output(refused)).isEqualTo("failed");
    SyncRecordView record = records(f, incident).getFirst();
    assertThat(record.error()).isEqualTo("HTTP 500");
    assertThat(record.ticketLinkId()).isNull();
    assertThat(ticketing.ticketLink(f.tenant, incident)).isEmpty();
    String warning =
        "ticket create for incident "
            + incident
            + " of tenant "
            + f.tenant.value()
            + " failed: HTTP 500";
    assertThat(LogLines.application(output.getAll()))
        .anySatisfy(
            line -> {
              assertThat(line.path("message").asString()).isEqualTo(warning);
              assertThat(line.path("log").path("level").asString()).isEqualTo("WARN");
            });

    wiremock.resetAll();
    wiremock.stubFor(
        post(urlEqualTo(ISSUE)).willReturn(aResponse().withStatus(201).withFixedDelay(16_000)));
    UUID late = f.openIncident();
    assertThat(output(run(f, late, "create"))).isEqualTo("failed");
    assertThat(records(f, late).getFirst().error()).isEqualTo("No answer within 15 s.");

    wiremock.resetAll();
    stub(get(urlPathEqualTo(SEARCH)), 200, recorded("0003-search-1.json"));
    assertThat(output(run(f, incident, "create"))).isEqualTo("done");
    assertThat(calls()).hasSize(1).allMatch(call -> call.startsWith("GET " + SEARCH));
    assertThat(requests().getFirst().queryParameter("jql").firstValue())
        .isEqualTo("project=NOC AND labels=\"vigil-" + incident + "\"");
    assertThat(link(f, incident).externalKey()).isEqualTo("NOC-1");

    reset();
    UUID reopened = created(f);
    f.resolve(reopened);
    run(f, reopened, "resolve");
    f.reopen(reopened, false);
    wiremock.resetRequests();
    StepAnswer noAlert = run(f, reopened, "create");
    assertThat(output(noAlert)).isEqualTo("failed");
    SyncRecordView reopen = records(f, reopened).getLast();
    assertThat(reopen.operation()).isEqualTo(SyncOperation.REOPEN);
    assertThat(reopen.error()).isEqualTo("no reopening alert");
    assertThat(requests()).isEmpty();
  }

  @Test
  @DisplayName("PM-M19-04 no active Jira instance, no public address, no evidence: failed")
  void setupFailures() {
    TicketingFixture f = tenant();
    f.jiraActive(false);
    UUID incident = f.openIncident();
    assertThat(output(run(f, incident, "create"))).isEqualTo("failed");
    SyncRecordView record = records(f, incident).getFirst();
    assertThat(record.error()).isEqualTo("no active Jira connector instance");
    assertThat(record.contentSentJson()).isEqualTo("{}");
    f.jiraActive(true);

    count++;
    TicketingFixture noAlias = TicketingFixture.create(app, "Ticketing " + count, null, jira);
    UUID other = noAlias.openIncident();
    assertThat(output(run(noAlias, other, "create"))).isEqualTo("failed");
    assertThat(records(noAlias, other).getFirst().error())
        .isEqualTo("the tenant has no public address for links");

    TicketingFixture g = tenant();
    UUID open = created(g);
    assertThat(output(run(g, open, "resolve"))).isEqualTo("failed");
    SyncRecordView resolve = records(g, open).getLast();
    assertThat(resolve.ticketLinkId()).isEqualTo(link(g, open).id());
    assertThat(audit(g)).containsExactly("Jira NOC-1: create done.", "Jira NOC-1: resolve failed.");
    assertThat(requests()).isEmpty();
  }

  @Test
  @DisplayName("DM-SM14-T3 a create for a reopened incident reopens the ticket with a comment")
  void reopen() {
    TicketingFixture f = tenant();
    UUID incident = created(f);
    f.resolve(incident);
    run(f, incident, "resolve");
    assertThat(link(f, incident).state()).isEqualTo(TicketLinkState.RESOLVED);
    f.reopen(incident, true);
    wiremock.resetRequests();
    StepAnswer answer = run(f, incident, "create");
    assertThat(output(answer)).isEqualTo("done");
    assertThat(paragraphs(body("POST", COMMENT).get("body")))
        .containsExactly(
            "Reopened by alert 'Disk full on srv-db-01' (Zabbix) received at"
                + " 2026-11-11 10:20:30 UTC.");
    assertThat(calls())
        .containsExactly("POST " + COMMENT, "GET " + TRANSITIONS, "POST " + TRANSITIONS);
    assertThat(body("POST", TRANSITIONS))
        .isEqualTo(Recordings.tree("{\"transition\":{\"id\":\"11\"}}"));
    assertThat(link(f, incident).state()).isEqualTo(TicketLinkState.OPEN);
    SyncRecordView record = records(f, incident).getLast();
    assertThat(record.operation()).isEqualTo(SyncOperation.REOPEN);
    assertThat(record.outcome()).isEqualTo(SyncOutcome.DONE);
    assertThat(Recordings.tree(record.contentSentJson()).get("transition_to").stringValue())
        .isEqualTo("To Do");
  }

  @Test
  @DisplayName("DM-E76-R2 after a restart: label search before a create, status before resolve")
  void restart() {
    TicketingFixture f = tenant();
    // A pending create of the step: the issue is found at the second search.
    UUID incident = f.openIncident();
    UUID stepExecution = Ids.newId();
    insertRecord(f, incident, null, "create", stepExecution, "{\"title\":\"x\"}", "pending");
    wiremock.stubFor(
        get(urlPathEqualTo(SEARCH))
            .inScenario("lag")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse().withStatus(200).withBody(NONE_FOUND))
            .willSetStateTo("found"));
    wiremock.stubFor(
        get(urlPathEqualTo(SEARCH))
            .inScenario("lag")
            .whenScenarioStateIs("found")
            .willReturn(aResponse().withStatus(200).withBody(recorded("0003-search-1.json"))));
    assertThat(output(step.evaluate(f.call(incident, stepExecution, "create")))).isEqualTo("done");
    assertThat(calls()).hasSize(2);
    assertThat(calls()).allMatch(call -> call.startsWith("GET " + SEARCH));
    assertThat(link(f, incident).externalKey()).isEqualTo("NOC-1");
    SyncRecordView found = records(f, incident).getFirst();
    assertThat(found.outcome()).isEqualTo(SyncOutcome.DONE);
    assertThat(Recordings.tree(found.contentSentJson()))
        .isEqualTo(Recordings.tree("{\"title\":\"x\"}"));

    // None found after four searches: one create.
    reset();
    UUID second = f.openIncident();
    UUID secondStep = Ids.newId();
    insertRecord(f, second, null, "create", secondStep, "{\"title\":\"x\"}", "pending");
    long started = System.nanoTime();
    assertThat(output(step.evaluate(f.call(second, secondStep, "create")))).isEqualTo("done");
    assertThat((System.nanoTime() - started) / 1_000_000_000).isGreaterThanOrEqualTo(15);
    assertThat(calls()).filteredOn(call -> call.startsWith("GET " + SEARCH)).hasSize(4);
    assertThat(calls()).filteredOn(call -> call.equals("POST " + ISSUE)).hasSize(1);
    assertThat(Recordings.tree(records(f, second).getFirst().contentSentJson()).has("description"))
        .isTrue();

    // A pending resolve whose issue is already Done: done without comment or transition.
    reset();
    UUID resolving = created(f);
    f.resolve(resolving);
    UUID resolveStep = Ids.newId();
    insertRecord(
        f,
        resolving,
        link(f, resolving).id(),
        "resolve",
        resolveStep,
        "{\"comment\":[]}",
        "pending");
    stub(
        get(urlEqualTo(STATUS)),
        200,
        "{\"key\":\"NOC-1\",\"fields\":{\"status\":{\"name\":\"Done\"}}}");
    assertThat(output(step.evaluate(f.call(resolving, resolveStep, "resolve")))).isEqualTo("done");
    assertThat(calls()).containsExactly("GET " + STATUS);
    assertThat(link(f, resolving).state()).isEqualTo(TicketLinkState.RESOLVED);

    // A pending create of another step execution: search first, superseded afterwards.
    reset();
    TicketingFixture g = tenant();
    UUID other = g.openIncident();
    UUID earlier =
        insertRecord(g, other, null, "create", Ids.newId(), "{\"title\":\"x\"}", "pending");
    stub(get(urlPathEqualTo(SEARCH)), 200, recorded("0003-search-1.json"));
    assertThat(output(run(g, other, "create"))).isEqualTo("done");
    assertThat(calls()).hasSize(1).allMatch(call -> call.startsWith("GET " + SEARCH));
    SyncRecordView superseded =
        records(g, other).stream().filter(r -> r.id().equals(earlier)).findFirst().orElseThrow();
    SyncRecordView done =
        records(g, other).stream().filter(r -> !r.id().equals(earlier)).findFirst().orElseThrow();
    assertThat(superseded.outcome()).isEqualTo(SyncOutcome.FAILED);
    assertThat(superseded.error()).isEqualTo("superseded by " + done.id());
    assertThat(superseded.ticketLinkId()).isNull();
    assertThat(done.ticketLinkId()).isEqualTo(link(g, other).id());
    assertThat(audit(g))
        .containsExactlyInAnyOrder("Jira no ticket: create failed.", "Jira NOC-1: create done.");

    // An earlier create that failed at once makes no search.
    reset();
    UUID atOnce = g.openIncident();
    insertRecord(g, atOnce, null, "create", Ids.newId(), "{}", "failed");
    assertThat(output(run(g, atOnce, "create"))).isEqualTo("done");
    assertThat(calls()).containsExactly("POST " + ISSUE);
  }

  @Test
  @DisplayName("PM-M19-01 a step called again after it finished answers the same, no call")
  void calledAgain() {
    TicketingFixture f = tenant();
    UUID incident = f.openIncident();
    UUID stepExecution = Ids.newId();
    StepAnswer first = step.start(f.call(incident, stepExecution, "create"));
    wiremock.resetRequests();
    StepAnswer again = step.evaluate(f.call(incident, stepExecution, "create"));
    assertThat(again).isEqualTo(first);
    assertThat(output(again)).isEqualTo("done");
    assertThat(requests()).isEmpty();
    UUID failing = f.openIncident();
    UUID failingStep = Ids.newId();
    StepAnswer failed = step.start(f.call(failing, failingStep, "resolve"));
    assertThat(output(failed)).isEqualTo("failed");
    assertThat(step.evaluate(f.call(failing, failingStep, "resolve"))).isEqualTo(failed);
    assertThat(records(f, failing)).hasSize(1);
    assertThat(requests()).isEmpty();
  }
}
