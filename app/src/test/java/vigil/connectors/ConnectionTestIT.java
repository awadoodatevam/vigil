package vigil.connectors;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import vigil.connectors.api.ConnectionTestResult;
import vigil.connectors.api.ConnectorImport;
import vigil.connectors.api.ConnectorService;
import vigil.connectors.api.ConnectorsPart;
import vigil.shared.Ids;
import vigil.shared.TenantId;
import vigil.shared.VigilError;
import vigil.testsupport.LogLines;
import vigil.testsupport.TestSecrets;
import vigil.testsupport.VigilDatabase;
import vigil.testsupport.VigilTestApp;

@ExtendWith(OutputCaptureExtension.class)
class ConnectionTestIT {

  static final String ZABBIX_PATH = "/api_jsonrpc.php";
  static final String ALERTMANAGER_PATH = "/api/v2/status";
  static final String TEST_BODY =
      "{\"jsonrpc\":\"2.0\",\"method\":\"host.get\",\"params\":{\"output\":[\"hostid\"],"
          + "\"limit\":1},\"id\":1}";
  private static final String PASSING =
      "{\"jsonrpc\":\"2.0\",\"result\":[{\"hostid\":\"10084\"}],\"id\":1}";
  private static final String NOT_AUTHORIZED =
      "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32602,\"message\":\"Invalid params.\","
          + "\"data\":\"Not authorized.\"},\"id\":1}";

  private static WireMockServer wiremock;
  private static VigilTestApp app;
  private static TestSecrets secrets;
  private static ConnectorService connectors;
  private static TenantId tenantA;
  private static Map<String, UUID> ids;
  private static UUID noCredential;
  private static int closed;

  /** Connect 1 s and monitoring test 2 s. */
  static class TestTimings {

    @Bean
    @Primary
    ConnectorTimings testConnectorTimings() {
      return new ConnectorTimings(Duration.ofSeconds(1), Duration.ofSeconds(2));
    }
  }

  @BeforeAll
  static void start() throws Exception {
    wiremock = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort());
    wiremock.start();
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      closed = socket.getLocalPort();
    }
    VigilDatabase database = VigilDatabase.fresh();
    secrets =
        TestSecrets.create(
            database,
            Map.of(
                "zabbix-api", TestSecrets.randomToken(),
                "alertmanager-password", TestSecrets.randomToken(),
                "zabbix-bad", "tok€" + TestSecrets.randomToken()));
    app = VigilTestApp.start(database, secrets.folder(), List.of(), TestTimings.class);
    app.awaitStarted();
    connectors = app.context().getBean(ConnectorService.class);
    tenantA = new TenantId(Ids.newId());
    String local = "http://127.0.0.1:" + wiremock.port();
    String api = "{\"api_path\":\"/api_jsonrpc.php\"}";
    String user = "{\"username\":\"vigil\"}";
    ids =
        app.context()
            .getBean(ConnectorImport.class)
            .importPart(
                tenantA,
                new ConnectorsPart(
                    List.of(
                        TestParts.credential("za", "external_api", "secret:zabbix-api"),
                        TestParts.credential("aa", "external_api", "secret:alertmanager-password"),
                        TestParts.credential("zm", "external_api", "secret:missing-api"),
                        TestParts.credential("zb", "external_api", "secret:zabbix-bad")),
                    List.of(
                        TestParts.connector("zabbix-ok", "zabbix", local, api, "za"),
                        TestParts.connector("alertmanager-ok", "alertmanager", local, user, "aa"),
                        TestParts.connector(
                            "zabbix-closed", "zabbix", "http://127.0.0.1:" + closed, api, "za"),
                        TestParts.connector(
                            "alertmanager-closed",
                            "alertmanager",
                            "http://127.0.0.1:" + closed,
                            user,
                            "aa"),
                        TestParts.connector(
                            "zabbix-nohost", "zabbix", "http://nowhere.invalid:8080", api, "za"),
                        TestParts.connector(
                            "zabbix-noscheme", "zabbix", "lab-zabbix-web", api, "za"),
                        TestParts.connector("zabbix-ftp", "zabbix", "ftp://127.0.0.1/x", api, "za"),
                        TestParts.connector("zabbix-nosecret", "zabbix", local, api, "zm"),
                        TestParts.connector("zabbix-badsecret", "zabbix", local, api, "zb"),
                        TestParts.connector(
                            "linux",
                            "linux_server",
                            "lab-db-01",
                            "{\"port\":22,\"host_key_sha256\":\"SHA256:x\"}",
                            null))),
                Instant.now(),
                Map.of());
    noCredential = Ids.newId();
    Timestamp now = Timestamp.from(Instant.now());
    database
        .postgres()
        .sql(
            "INSERT INTO connectors.connector_instance (id, tenant_id, created_at, updated_at,"
                + " name, kind, address, settings, credential_ref_id, active, updated_by_kind,"
                + " updated_by_user_id, updated_by_module) VALUES (:id, :tenant, :now, :now,"
                + " 'zabbix-nocred', 'zabbix', :address, CAST(:settings AS jsonb), NULL, true,"
                + " 'module', NULL, 'vigil-setup')")
        .param("id", noCredential)
        .param("tenant", tenantA.value())
        .param("now", now)
        .param("address", local)
        .param("settings", api)
        .update();
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

  private static ConnectionTestResult test(String handle) {
    return connectors.testConnection(tenantA, ids.get(handle));
  }

  private static void zabbixAnswers(int status, String body) {
    wiremock.stubFor(
        post(urlEqualTo(ZABBIX_PATH)).willReturn(aResponse().withStatus(status).withBody(body)));
  }

  private static void alertmanagerAnswers(int status) {
    wiremock.stubFor(get(urlEqualTo(ALERTMANAGER_PATH)).willReturn(aResponse().withStatus(status)));
  }

  private static ConnectionTestResult failed(String reason) {
    return new ConnectionTestResult.Failed(reason);
  }

  private static String basic() {
    return Base64.getEncoder()
        .encodeToString(
            ("vigil:" + secrets.value("alertmanager-password")).getBytes(StandardCharsets.UTF_8));
  }

  @Test
  @DisplayName("PM-M04-04 testConnection tests monitoring source instances only")
  void testConnection() {
    zabbixAnswers(200, PASSING);
    assertThat(test("zabbix-ok")).isEqualTo(new ConnectionTestResult.Passed());
    assertThatThrownBy(() -> test("linux")).isInstanceOf(IllegalArgumentException.class);
    UUID unknown = Ids.newId();
    assertThatThrownBy(() -> connectors.testConnection(tenantA, unknown))
        .isInstanceOfSatisfying(
            VigilError.class,
            e -> {
              assertThat(e.code()).isEqualTo(VigilError.Code.NOT_FOUND);
              assertThat(e.message()).isEqualTo("Connector instance " + unknown + " not found.");
            });
  }

  @Test
  @DisplayName("AR-K-01 the zabbix connection test request and its answers")
  void zabbix() {
    zabbixAnswers(200, PASSING);
    assertThat(test("zabbix-ok")).isEqualTo(new ConnectionTestResult.Passed());
    wiremock.verify(1, anyRequestedFor(anyUrl()));
    wiremock.verify(
        1,
        postRequestedFor(urlEqualTo(ZABBIX_PATH))
            .withHeader("Content-Type", equalTo("application/json-rpc"))
            .withHeader("Authorization", equalTo("Bearer " + secrets.value("zabbix-api")))
            .withRequestBody(equalTo(TEST_BODY)));
    zabbixAnswers(200, NOT_AUTHORIZED);
    assertThat(test("zabbix-ok")).isEqualTo(failed("Not authorized."));
    zabbixAnswers(401, NOT_AUTHORIZED);
    assertThat(test("zabbix-ok")).isEqualTo(failed("Not authorized."));
    zabbixAnswers(500, "oops");
    assertThat(test("zabbix-ok")).isEqualTo(failed("HTTP 500"));
    zabbixAnswers(200, "{\"result\":{}}");
    assertThat(test("zabbix-ok")).isEqualTo(failed("HTTP 200"));
    wiremock.stubFor(
        post(urlEqualTo(ZABBIX_PATH))
            .willReturn(aResponse().withStatus(302).withHeader("Location", "/elsewhere")));
    wiremock.stubFor(get(urlEqualTo("/elsewhere")).willReturn(aResponse().withStatus(200)));
    assertThat(test("zabbix-ok")).isEqualTo(failed("HTTP 302"));
    wiremock.verify(0, anyRequestedFor(urlEqualTo("/elsewhere")));
    String long600 = "e".repeat(600);
    zabbixAnswers(200, "{\"error\":{\"data\":\"" + long600 + "\"}}");
    assertThat(test("zabbix-ok")).isEqualTo(failed("e".repeat(500)));
    zabbixAnswers(200, "{\"error\":{\"data\":\"bad " + secrets.value("zabbix-api") + "\"}}");
    assertThat(test("zabbix-ok")).isEqualTo(failed("HTTP 200"));
    String old = secrets.value("zabbix-api");
    String fresh = TestSecrets.randomToken();
    secrets.replace("zabbix-api", fresh);
    try {
      wiremock.resetRequests();
      zabbixAnswers(200, PASSING);
      assertThat(test("zabbix-ok")).isEqualTo(new ConnectionTestResult.Passed());
      wiremock.verify(
          1,
          postRequestedFor(urlEqualTo(ZABBIX_PATH))
              .withHeader("Authorization", equalTo("Bearer " + fresh)));
    } finally {
      secrets.replace("zabbix-api", old);
    }
  }

  @Test
  @DisplayName("AR-K-02 the alertmanager connection test request and its answers")
  void alertmanager() {
    alertmanagerAnswers(200);
    assertThat(test("alertmanager-ok")).isEqualTo(new ConnectionTestResult.Passed());
    wiremock.verify(
        1,
        getRequestedFor(urlEqualTo(ALERTMANAGER_PATH))
            .withHeader("Authorization", equalTo("Basic " + basic())));
    alertmanagerAnswers(401);
    assertThat(test("alertmanager-ok")).isEqualTo(failed("HTTP 401"));
  }

  @Test
  @DisplayName("AR-T-13 the connector client: HTTP/1.1, explicit timeouts, no redirects, no proxy")
  void client() {
    HttpClient client = app.context().getBean(ConnectorHttp.class).client();
    assertThat(client.version()).isEqualTo(HttpClient.Version.HTTP_1_1);
    assertThat(client.connectTimeout()).contains(Duration.ofSeconds(1));
    assertThat(client.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER);
    assertThat(client.proxy()).containsSame(HttpClient.Builder.NO_PROXY);
    zabbixAnswers(200, PASSING);
    alertmanagerAnswers(200);
    assertThat(test("zabbix-ok")).isEqualTo(new ConnectionTestResult.Passed());
    assertThat(test("alertmanager-ok")).isEqualTo(new ConnectionTestResult.Passed());
    wiremock.verify(1, postRequestedFor(urlEqualTo(ZABBIX_PATH)).withoutHeader("Upgrade"));
    wiremock.verify(1, getRequestedFor(urlEqualTo(ALERTMANAGER_PATH)).withoutHeader("Upgrade"));
  }

  @Test
  @DisplayName("AR-TM-01 a connection test waits at most the monitoring test timeout")
  void timeout() {
    MappingBuilder slow =
        post(urlEqualTo(ZABBIX_PATH))
            .willReturn(aResponse().withStatus(200).withBody(PASSING).withFixedDelay(3000));
    wiremock.stubFor(slow);
    long start = System.nanoTime();
    ConnectionTestResult result = test("zabbix-ok");
    Duration took = Duration.ofNanos(System.nanoTime() - start);
    assertThat(result).isEqualTo(failed("No answer within 2 s."));
    assertThat(took).isBetween(Duration.ofMillis(1800), Duration.ofMillis(3000));
  }

  @Test
  @DisplayName("BP-A-22 the failure reasons of connection tests that get no answer")
  void reasons() {
    assertThat(test("zabbix-closed"))
        .isEqualTo(failed("Could not connect to 127.0.0.1:" + closed + "."));
    assertThat(test("alertmanager-closed"))
        .isEqualTo(failed("Could not connect to 127.0.0.1:" + closed + "."));
    assertThat(test("zabbix-nohost")).isEqualTo(failed("Host nowhere.invalid not found."));
    assertThat(test("zabbix-noscheme"))
        .isEqualTo(failed("The address is not a valid http or https address."));
    assertThat(test("zabbix-ftp"))
        .isEqualTo(failed("The address is not a valid http or https address."));
    assertThat(test("zabbix-nosecret")).isEqualTo(failed("Secret missing-api is not available."));
    assertThat(test("zabbix-badsecret")).isEqualTo(failed("Secret zabbix-bad is not available."));
    wiremock.verify(0, anyRequestedFor(anyUrl()));
    assertThat(connectors.testConnection(tenantA, noCredential))
        .isEqualTo(failed("No credential is set."));
  }

  @Test
  @DisplayName("AR-S-11 no secret value in a log line of the connection tests")
  void noSecretInLogs(CapturedOutput output) {
    zabbixAnswers(200, PASSING);
    assertThat(test("zabbix-ok")).isInstanceOf(ConnectionTestResult.Passed.class);
    zabbixAnswers(200, NOT_AUTHORIZED);
    assertThat(test("zabbix-ok")).isInstanceOf(ConnectionTestResult.Failed.class);
    alertmanagerAnswers(200);
    assertThat(test("alertmanager-ok")).isInstanceOf(ConnectionTestResult.Passed.class);
    alertmanagerAnswers(401);
    assertThat(test("alertmanager-ok")).isInstanceOf(ConnectionTestResult.Failed.class);
    assertThat(test("zabbix-badsecret")).isInstanceOf(ConnectionTestResult.Failed.class);
    LogLines.assertNoneContains(
        output.getOut(),
        List.of(
            secrets.value("zabbix-api"),
            secrets.value("alertmanager-password"),
            basic(),
            secrets.value("zabbix-bad")));
  }
}
