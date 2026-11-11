package vigil.connectors;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.request;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import vigil.connectors.Recordings.ProviderCall;
import vigil.connectors.api.ConnectorImport;
import vigil.connectors.api.ConnectorInstanceView;
import vigil.connectors.api.ConnectorKind;
import vigil.connectors.api.ConnectorService;
import vigil.connectors.api.ConnectorsPart;
import vigil.connectors.api.TicketLine;
import vigil.connectors.api.TicketOutcome;
import vigil.connectors.api.TicketingConnector;
import vigil.shared.Ids;
import vigil.shared.TenantId;
import vigil.testsupport.TestSecrets;
import vigil.testsupport.VigilDatabase;
import vigil.testsupport.VigilTestApp;

class JiraConnectorIT {

  private static final String SETTINGS =
      "{\"project_key\":\"NOC\",\"issue_type\":\"Task\",\"resolved_status\":\"Done\","
          + "\"reopened_status\":\"To Do\",\"label_prefix\":\"vigil-\"}";
  private static final String SPIKE_LABEL = "vigil-spike-20261110T044230Z";
  private static WireMockServer wiremock;
  private static VigilTestApp app;
  private static TestSecrets secrets;
  private static TenantId tenantId;
  private static Map<String, UUID> ids;
  private static TicketingConnector jira;
  private static String base;
  private static String email;
  private static String token;
  private static int closedPort;

  @BeforeAll
  static void start() throws Exception {
    wiremock = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort());
    wiremock.start();
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      closedPort = socket.getLocalPort();
    }
    base = "http://127.0.0.1:" + wiremock.port();
    email = "noc-" + TestSecrets.randomToken().substring(0, 8).toLowerCase() + "@vigil.test";
    token = TestSecrets.randomToken();
    VigilDatabase database = VigilDatabase.fresh();
    secrets =
        TestSecrets.create(
            database,
            Map.of(
                "jira",
                "{\"email\":\""
                    + email
                    + "\",\"api_token\":\""
                    + token
                    + "\",\"expires_on\":\"2099-12-31\"}",
                "jira-bad",
                "{\"email\":\"" + email + "\",\"api_token\":\"\"}"));
    app = VigilTestApp.start(database, secrets.folder(), List.of());
    app.awaitStarted();
    tenantId = new TenantId(Ids.newId());
    ids =
        app.context()
            .getBean(ConnectorImport.class)
            .importPart(
                tenantId,
                new ConnectorsPart(
                    List.of(
                        TestParts.credential("cred-jira", "external_api", "secret:jira"),
                        TestParts.credential("cred-missing", "external_api", "secret:jira-gone"),
                        TestParts.credential("cred-bad", "external_api", "secret:jira-bad")),
                    List.of(
                        TestParts.connector("jira-demo", "jira_cloud", base, SETTINGS, "cred-jira"),
                        TestParts.connector(
                            "jira-missing", "jira_cloud", base, SETTINGS, "cred-missing"),
                        TestParts.connector("jira-bad", "jira_cloud", base, SETTINGS, "cred-bad"),
                        TestParts.connector(
                            "jira-closed",
                            "jira_cloud",
                            "http://127.0.0.1:" + closedPort,
                            SETTINGS,
                            "cred-jira"))),
                Instant.now(),
                Map.of());
    jira =
        app.context().getBeansOfType(TicketingConnector.class).values().stream()
            .filter(bean -> bean.kind() == ConnectorKind.JIRA_CLOUD)
            .findFirst()
            .orElseThrow();
  }

  @AfterAll
  static void stop() {
    app.close();
    wiremock.stop();
  }

  @BeforeEach
  void reset() {
    wiremock.resetAll();
  }

  private static ConnectorInstanceView instance(String handle) {
    return app.context()
        .getBean(ConnectorService.class)
        .instance(tenantId, ids.get(handle))
        .orElseThrow();
  }

  private static ConnectorInstanceView changed(String address, UUID credentialRefId) {
    ConnectorInstanceView demo = instance("jira-demo");
    return new ConnectorInstanceView(
        demo.tenantId(),
        demo.id(),
        demo.name(),
        demo.kind(),
        address,
        demo.settings(),
        credentialRefId,
        true);
  }

  private static ProviderCall recorded(String name) {
    return Recordings.provider("jira", name, base);
  }

  /** WireMock answers the recorded call's method and address with the recorded answer. */
  private static void answer(ProviderCall call) {
    wiremock.stubFor(
        request(call.method(), urlEqualTo(call.url().substring(base.length())))
            .willReturn(
                aResponse()
                    .withStatus(call.status())
                    .withHeader("Content-Type", "application/json")
                    .withBody(call.responseBody())));
  }

  private static List<LoggedRequest> requests() {
    return wiremock.findAll(anyRequestedFor(anyUrl()));
  }

  private static String basic() {
    return "Basic "
        + Base64.getEncoder()
            .encodeToString((email + ":" + token).getBytes(StandardCharsets.UTF_8));
  }

  /** The description lines the P-06 spike sent, the last ending with its link. */
  private static List<TicketLine> spikeLines(JsonNode adf) {
    JsonNode content = adf.get("content");
    String first = content.get(0).get("content").get(0).get("text").stringValue();
    String second = content.get(1).get("content").get(0).get("text").stringValue();
    String link = content.get(2).get("content").get(1).get("text").stringValue();
    return List.of(
        TicketLine.of(first), TicketLine.of(second), new TicketLine("Link: " + link, link));
  }

  @Test
  @DisplayName("AR-K-14 create, label search and an empty search as recorded")
  void createAndSearch() {
    ProviderCall create = recorded("0002-create.json");
    answer(create);
    JsonNode fields = create.request().get("fields");
    TicketOutcome created =
        jira.create(
            tenantId,
            instance("jira-demo"),
            fields.get("summary").stringValue(),
            spikeLines(fields.get("description")),
            SPIKE_LABEL);
    assertThat(created).isEqualTo(new TicketOutcome.Done("NOC-1", base + "/browse/NOC-1", null));
    LoggedRequest sent = requests().getFirst();
    assertThat(requests()).hasSize(1);
    assertThat(sent.getMethod().getName()).isEqualTo("POST");
    assertThat(sent.getUrl()).isEqualTo("/rest/api/3/issue");
    assertThat(sent.getHeader("Authorization")).isEqualTo(basic());
    assertThat(sent.getHeader("Accept")).isEqualTo("application/json");
    assertThat(sent.getHeader("Content-Type")).isEqualTo("application/json");
    JsonNode body = Recordings.tree(sent.getBodyAsString());
    assertThat(body).isEqualTo(create.request());
    assertThat(body.get("fields").propertyNames())
        .containsExactly("project", "issuetype", "summary", "description", "labels");

    wiremock.resetAll();
    ProviderCall search = recorded("0003-search-1.json");
    answer(search);
    TicketOutcome found = jira.findByLabel(tenantId, instance("jira-demo"), SPIKE_LABEL);
    assertThat(found).isEqualTo(new TicketOutcome.Done("NOC-1", base + "/browse/NOC-1", "To Do"));
    assertThat(base + requests().getFirst().getUrl()).isEqualTo(search.url());
    assertThat(requests().getFirst().getHeader("Authorization")).isEqualTo(basic());
    assertThat(requests().getFirst().getHeader("Content-Type")).isNull();

    wiremock.resetAll();
    wiremock.stubFor(
        get(urlPathEqualTo("/rest/api/3/search/jql"))
            .willReturn(
                aResponse().withStatus(200).withBody("{\"issues\": [], \"isLast\": true}")));
    assertThat(jira.findByLabel(tenantId, instance("jira-demo"), "vigil-x"))
        .isEqualTo(new TicketOutcome.Done(null, null, null));
  }

  @Test
  @DisplayName("AR-K-16 comment, status and transition by target name as recorded")
  void commentStatusTransition() {
    ProviderCall comment = recorded("0005-comment.json");
    answer(comment);
    JsonNode adf = comment.request().get("body");
    String first = adf.get("content").get(0).get("content").get(0).get("text").stringValue();
    String link = adf.get("content").get(1).get("content").get(1).get("text").stringValue();
    assertThat(
            jira.addComment(
                tenantId,
                instance("jira-demo"),
                "NOC-1",
                List.of(TicketLine.of(first), new TicketLine("Link: " + link, link))))
        .isEqualTo(new TicketOutcome.Done("NOC-1", null, null));
    assertThat(Recordings.tree(requests().getFirst().getBodyAsString()))
        .isEqualTo(comment.request());
    assertThat(requests().getFirst().getUrl()).isEqualTo("/rest/api/3/issue/NOC-1/comment");

    wiremock.resetAll();
    ProviderCall status = recorded("0006-status-1.json");
    answer(status);
    assertThat(jira.status(tenantId, instance("jira-demo"), "NOC-1"))
        .isEqualTo(new TicketOutcome.Done("NOC-1", null, "To Do"));
    assertThat(base + requests().getFirst().getUrl()).isEqualTo(status.url());

    wiremock.resetAll();
    ProviderCall transitions = recorded("0007-transitions-1.json");
    ProviderCall resolve = recorded("0008-resolve.json");
    answer(transitions);
    answer(resolve);
    assertThat(jira.transition(tenantId, instance("jira-demo"), "NOC-1", "Done"))
        .isEqualTo(new TicketOutcome.Done("NOC-1", null, "Done"));
    List<LoggedRequest> calls = requests();
    assertThat(calls).hasSize(2);
    assertThat(calls.get(0).getMethod().getName()).isEqualTo("GET");
    assertThat(calls.get(1).getMethod().getName()).isEqualTo("POST");
    assertThat(Recordings.tree(calls.get(1).getBodyAsString())).isEqualTo(resolve.request());
  }

  private static void stub(String method, String path, int status, String body, int delayMs) {
    wiremock.stubFor(
        request(method, urlPathEqualTo(path))
            .willReturn(aResponse().withStatus(status).withBody(body).withFixedDelay(delayMs)));
  }

  @Test
  @DisplayName("AR-K-04 the failure reasons of the Jira connector, never with the credential")
  void failures() {
    UUID credential = instance("jira-demo").credentialRefId();
    Map<String, Supplier<TicketOutcome>> cases = new java.util.LinkedHashMap<>();
    cases.put("No credential is set.", () -> jira.status(tenantId, changed(base, null), "NOC-1"));
    cases.put(
        "Secret jira-gone is not available.",
        () -> jira.status(tenantId, instance("jira-missing"), "NOC-1"));
    cases.put(
        "Secret jira-bad is not valid.",
        () -> jira.status(tenantId, instance("jira-bad"), "NOC-1"));
    cases.put(
        "The address is not a valid http or https address.",
        () -> jira.status(tenantId, changed("ftp://jira.test", credential), "NOC-1"));
    cases.put(
        "Could not connect to 127.0.0.1:" + closedPort + ".",
        () -> jira.status(tenantId, instance("jira-closed"), "NOC-1"));
    for (Map.Entry<String, Supplier<TicketOutcome>> entry : cases.entrySet()) {
      assertThat(entry.getValue().get())
          .as(entry.getKey())
          .isEqualTo(new TicketOutcome.Failed(entry.getKey()));
    }
    assertThat(requests()).isEmpty();
    String notJson = "{\"email\":\"x\"";
    secrets.replace("jira-bad", notJson);
    assertThat(jira.status(tenantId, instance("jira-bad"), "NOC-1"))
        .isEqualTo(new TicketOutcome.Failed("Secret jira-bad is not valid."));

    ConnectorInstanceView demo = instance("jira-demo");
    stub("GET", "/rest/api/3/issue/NOC-1", 401, "{\"errorMessages\":[\"" + email + "\"]}", 0);
    assertThat(jira.status(tenantId, demo, "NOC-1"))
        .isEqualTo(new TicketOutcome.Failed("HTTP 401"));
    stub("GET", "/rest/api/3/issue/NOC-1", 200, "{\"fields\":{}}", 0);
    assertThat(jira.status(tenantId, demo, "NOC-1"))
        .isEqualTo(new TicketOutcome.Failed("The answer has no status."));
    stub("POST", "/rest/api/3/issue", 201, "{\"id\":\"10017\"}", 0);
    assertThat(jira.create(tenantId, demo, "t", List.of(TicketLine.of("x")), "vigil-x"))
        .isEqualTo(new TicketOutcome.Failed("The answer has no issue key."));
    stub("GET", "/rest/api/3/issue/NOC-1/transitions", 200, "{\"transitions\":[]}", 0);
    assertThat(jira.transition(tenantId, demo, "NOC-1", "Done"))
        .isEqualTo(new TicketOutcome.Failed("No transition to status 'Done'."));
    stub("GET", "/rest/api/3/search/jql", 200, "{\"total\":0}", 0);
    assertThat(jira.findByLabel(tenantId, demo, "vigil-x"))
        .isEqualTo(new TicketOutcome.Failed("The answer has no issues."));
    stub("GET", "/rest/api/3/issue/NOC-2", 200, "{}", 16_000);
    TicketOutcome late = jira.status(tenantId, demo, "NOC-2");
    assertThat(late).isEqualTo(new TicketOutcome.Failed("No answer within 15 s."));

    String encoded = basic().substring("Basic ".length());
    for (TicketOutcome outcome :
        List.of(
            jira.status(tenantId, demo, "NOC-1"),
            jira.status(tenantId, instance("jira-closed"), "NOC-1"),
            late)) {
      assertThat(((TicketOutcome.Failed) outcome).reason())
          .doesNotContain(token)
          .doesNotContain(email)
          .doesNotContain(encoded);
    }

    TransactionTemplate transaction =
        new TransactionTemplate(app.context().getBean(PlatformTransactionManager.class));
    wiremock.resetRequests();
    assertThatThrownBy(() -> transaction.execute(status -> jira.status(tenantId, demo, "NOC-1")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("external call inside a transaction");
    assertThat(requests()).isEmpty();
  }
}
