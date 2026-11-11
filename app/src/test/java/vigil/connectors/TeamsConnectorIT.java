package vigil.connectors;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import vigil.connectors.api.ConnectorImport;
import vigil.connectors.api.ConnectorInstanceView;
import vigil.connectors.api.ConnectorKind;
import vigil.connectors.api.ConnectorService;
import vigil.connectors.api.ConnectorsPart;
import vigil.connectors.api.Delivery;
import vigil.connectors.api.MessageLine;
import vigil.connectors.api.NotificationChannelConnector;
import vigil.shared.Ids;
import vigil.shared.TenantId;
import vigil.testsupport.LogLines;
import vigil.testsupport.TestSecrets;
import vigil.testsupport.VigilDatabase;
import vigil.testsupport.VigilTestApp;

@ExtendWith(OutputCaptureExtension.class)
class TeamsConnectorIT {

  private static final Path RECORDING =
      Path.of("src/test/resources/recordings/teams/0001-post-noc-l1.json");
  private static final String LINK =
      "https://demo.vigil.test/m/acknowledge/0190f0b2-0000-7000-8000-000000000007";
  private static final List<MessageLine> LINES =
      List.of(
          MessageLine.of("Demo environment — fictional data - [P1] INC-0007 Disk full"),
          MessageLine.of("Owner: Database Operations. Informed: none."),
          MessageLine.of("Summary: the /data filesystem is full."),
          new MessageLine("Acknowledge: " + LINK, LINK));
  private static WireMockServer wiremock;
  private static VigilTestApp app;
  private static TenantId tenantId;
  private static Map<String, UUID> ids;
  private static NotificationChannelConnector teams;
  private static final Map<String, String> SECRETS = new LinkedHashMap<>();

  @BeforeAll
  static void start() throws Exception {
    wiremock = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort());
    wiremock.start();
    int closedPort;
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      closedPort = socket.getLocalPort();
    }
    String base = "http://127.0.0.1:" + wiremock.port();
    SECRETS.put("teams-team-noc-l1", webhook(base, "noc-l1"));
    SECRETS.put("teams-team-slow", webhook(base, "slow"));
    SECRETS.put("teams-team-closed", webhook("http://127.0.0.1:" + closedPort, "closed"));
    SECRETS.put("teams-team-bad", "not a web address");
    VigilDatabase database = VigilDatabase.fresh();
    TestSecrets secrets = TestSecrets.create(database, SECRETS);
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
                        TestParts.credential(
                            "cred-noc-l1", "webhook_url", "secret:teams-team-noc-l1"),
                        TestParts.credential("cred-slow", "webhook_url", "secret:teams-team-slow"),
                        TestParts.credential(
                            "cred-closed", "webhook_url", "secret:teams-team-closed"),
                        TestParts.credential("cred-bad", "webhook_url", "secret:teams-team-bad"),
                        TestParts.credential(
                            "cred-missing", "webhook_url", "secret:teams-team-gone"),
                        TestParts.credential(
                            "cred-token", "webhook_token", "secret:teams-team-noc-l1"),
                        TestParts.credential("cred-smtp", "smtp", "secret:smtp")),
                    List.of(
                        TestParts.connector(
                            "teams-demo", "ms_teams", "https://teams.microsoft.com", "{}", null),
                        TestParts.connector(
                            "email-demo",
                            "email",
                            "smtp://127.0.0.1:" + closedPort,
                            "{\"from_address\":\"techfinity-demo@vigil.test\",\"starttls\":false}",
                            "cred-smtp"))),
                Instant.now(),
                Map.of());
    teams = connector(ConnectorKind.MS_TEAMS);
  }

  private static String webhook(String base, String key) {
    return base
        + "/teams/"
        + key
        + "?api-version=1&sp=%2Ftriggers%2Fmanual%2Frun&sig="
        + TestSecrets.randomToken();
  }

  @AfterAll
  static void stop() {
    app.close();
    wiremock.stop();
  }

  @BeforeEach
  void reset() {
    wiremock.resetAll();
    wiremock.stubFor(post(urlPathEqualTo("/teams/noc-l1")).willReturn(aResponse().withStatus(202)));
  }

  private static NotificationChannelConnector connector(ConnectorKind kind) {
    return app.context().getBeansOfType(NotificationChannelConnector.class).values().stream()
        .filter(bean -> bean.kind() == kind)
        .findFirst()
        .orElseThrow();
  }

  private static ConnectorInstanceView instance(String handle) {
    return app.context()
        .getBean(ConnectorService.class)
        .instance(tenantId, ids.get(handle))
        .orElseThrow();
  }

  private static Delivery send(String credentialHandle, List<MessageLine> lines) {
    return teams.send(
        tenantId,
        instance("teams-demo"),
        "Techfinity Demo / NOC L1",
        credentialHandle == null ? null : ids.get(credentialHandle),
        null,
        lines);
  }

  private static List<LoggedRequest> requests() {
    return wiremock.findAll(anyRequestedFor(anyUrl()));
  }

  /** The hidden parts of every webhook secret: the address, its path and its query. */
  private static List<String> hidden() {
    List<String> hidden = new ArrayList<>();
    for (String value : SECRETS.values()) {
      hidden.add(value);
      int path = value.indexOf("/teams/");
      if (path > 0) {
        hidden.add(value.substring(path, value.indexOf('?')));
        hidden.add(value.substring(value.indexOf('?') + 1));
        hidden.add(value.substring(value.indexOf("sig=") + 4));
      }
    }
    return hidden;
  }

  private static void assertHidden(Delivery delivery) {
    String reason = ((Delivery.Failed) delivery).reason();
    for (String value : hidden()) {
      assertThat(reason).doesNotContain(value);
    }
  }

  @Test
  @DisplayName("AR-K-18 the Adaptive Card payload of the recording; 2xx is sent, others failed")
  void payload() throws Exception {
    assertThat(send("cred-noc-l1", LINES)).isEqualTo(new Delivery.Sent());
    List<LoggedRequest> posted = requests();
    assertThat(posted).hasSize(1);
    LoggedRequest request = posted.getFirst();
    assertThat(request.getMethod().getName()).isEqualTo("POST");
    assertThat(request.getHeader("Content-Type")).isEqualTo("application/json");
    JsonNode recording = Recordings.tree(Files.readString(RECORDING, StandardCharsets.UTF_8));
    assertThat(recording.get("response").get("status").intValue()).isEqualTo(202);
    JsonNode expected = Recordings.tree(recording.get("request").get("body").stringValue());
    JsonNode body = expected.get("attachments").get(0).get("content").get("body");
    assertThat(body).hasSize(4);
    for (int i = 0; i < 3; i++) {
      ((ObjectNode) body.get(i)).put("text", LINES.get(i).text());
    }
    ((ObjectNode) body.get(3)).put("text", "Acknowledge: [" + LINK + "](" + LINK + ")");
    assertThat(body.get(0).has("weight")).isTrue();
    assertThat(Recordings.JSON.writeValueAsString(Recordings.tree(request.getBodyAsString())))
        .isEqualTo(Recordings.JSON.writeValueAsString(expected));
    assertThat(new String(request.getBody(), StandardCharsets.UTF_8))
        .contains("Demo environment — fictional data");

    wiremock.stubFor(post(urlPathEqualTo("/teams/noc-l1")).willReturn(aResponse().withStatus(400)));
    Delivery refused = send("cred-noc-l1", LINES);
    assertThat(refused).isEqualTo(new Delivery.Failed("HTTP 400"));

    wiremock.stubFor(
        post(urlPathEqualTo("/teams/slow"))
            .willReturn(aResponse().withStatus(202).withFixedDelay(16_000)));
    Delivery slow = send("cred-slow", LINES);
    assertThat(slow).isEqualTo(new Delivery.Failed("No answer within 15 s."));
    assertHidden(slow);
    Delivery closed = send("cred-closed", LINES);
    assertThat(((Delivery.Failed) closed).reason()).startsWith("Could not connect to 127.0.0.1:");
    assertHidden(closed);
  }

  @Test
  @DisplayName(
      "AR-K-05 ms_teams: no instance credential; the address's reference holds the webhook")
  void webhookOfTheAddress() {
    ConnectorInstanceView instance = instance("teams-demo");
    assertThat(instance.credentialRefId()).isNull();
    assertThat(instance.address()).isEqualTo("https://teams.microsoft.com");
    assertThat(send("cred-noc-l1", LINES)).isEqualTo(new Delivery.Sent());
    LoggedRequest request = requests().getFirst();
    String secret = SECRETS.get("teams-team-noc-l1");
    assertThat(request.getUrl()).isEqualTo(secret.substring(secret.indexOf("/teams/")));
    assertThat(send(null, LINES)).isEqualTo(new Delivery.Failed("No credential is set."));
    assertThat(send("cred-token", LINES)).isEqualTo(new Delivery.Failed("No credential is set."));
    assertThat(requests()).hasSize(1);
  }

  @Test
  @DisplayName("AR-K-17 the channel name is shown, the webhook secret never in a reason or log")
  void webhookIsSecret(CapturedOutput output) {
    assertThat(send("cred-missing", LINES))
        .isEqualTo(new Delivery.Failed("Secret teams-team-gone is not available."));
    Delivery bad = send("cred-bad", LINES);
    assertThat(bad)
        .isEqualTo(new Delivery.Failed("Secret teams-team-bad is not a valid webhook address."));
    assertHidden(bad);
    Delivery closed = send("cred-closed", LINES);
    assertHidden(closed);
    wiremock.stubFor(post(urlPathEqualTo("/teams/noc-l1")).willReturn(aResponse().withStatus(500)));
    assertThat(send("cred-noc-l1", LINES)).isEqualTo(new Delivery.Failed("HTTP 500"));
    assertThat(requests()).hasSize(1);
    LogLines.assertNoneContains(output.getAll(), hidden());
  }

  @Test
  @DisplayName("PM-K-05 the ms_teams connector posts one message per call to the channel")
  void teamsConnector() {
    assertThat(teams.kind()).isEqualTo(ConnectorKind.MS_TEAMS);
    assertThat(send("cred-noc-l1", LINES)).isEqualTo(new Delivery.Sent());
    assertThat(send("cred-noc-l1", List.of(MessageLine.of("One line"))))
        .isEqualTo(new Delivery.Sent());
    List<LoggedRequest> posted = requests();
    assertThat(posted).hasSize(2);
    JsonNode one =
        Recordings.tree(posted.get(1).getBodyAsString())
            .get("attachments")
            .get(0)
            .get("content")
            .get("body");
    assertThat(one).hasSize(1);
    assertThat(one.get(0).get("text").stringValue()).isEqualTo("One line");
    assertThat(one.get(0).get("weight").stringValue()).isEqualTo("Bolder");
  }

  @Test
  @DisplayName("AR-K-00d one channel connector bean per kind; another kind's instance is refused")
  void channelType() {
    Map<String, NotificationChannelConnector> beans =
        app.context().getBeansOfType(NotificationChannelConnector.class);
    assertThat(beans.values().stream().map(NotificationChannelConnector::kind).toList())
        .containsExactlyInAnyOrder(ConnectorKind.MS_TEAMS, ConnectorKind.EMAIL);
    assertThat(app.context().getBeansOfType(TeamsSender.class)).hasSize(1);
    ConnectorInstanceView email = instance("email-demo");
    assertThatThrownBy(() -> teams.send(tenantId, email, "a", ids.get("cred-noc-l1"), null, LINES))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("connector instance " + email.id() + " is not ms_teams");
    ConnectorInstanceView channel = instance("teams-demo");
    assertThatThrownBy(
            () ->
                connector(ConnectorKind.EMAIL)
                    .send(tenantId, channel, "a@vigil.test", null, "Techfinity", LINES))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("connector instance " + channel.id() + " is not email");
    assertThatThrownBy(() -> teams.send(tenantId, channel, "a", null, null, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> teams.send(null, channel, "a", null, null, LINES))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("a required argument is missing");
    assertThat(requests()).isEmpty();
  }
}
