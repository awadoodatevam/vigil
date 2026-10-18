package vigil.connectors;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vigil.connectors.api.ConnectionTestResult;
import vigil.connectors.api.ConnectorImport;
import vigil.connectors.api.ConnectorInstanceView;
import vigil.connectors.api.ConnectorKind;
import vigil.connectors.api.ConnectorService;
import vigil.connectors.api.ConnectorsPart;
import vigil.connectors.api.MonitoringSourceConnector;
import vigil.connectors.api.ParseResult;
import vigil.shared.Ids;
import vigil.shared.TenantId;
import vigil.testsupport.TestSecrets;
import vigil.testsupport.VigilDatabase;
import vigil.testsupport.VigilTestApp;

class ExternalCallGuardIT {

  private static WireMockServer wiremock;
  private static VigilTestApp app;
  private static ConnectorService connectors;
  private static TransactionTemplate transaction;
  private static Map<ConnectorKind, MonitoringSourceConnector> beans;
  private static TenantId tenantA;
  private static Map<String, UUID> ids;

  @BeforeAll
  static void start() {
    wiremock = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort());
    wiremock.start();
    VigilDatabase database = VigilDatabase.fresh();
    TestSecrets secrets =
        TestSecrets.create(
            database,
            Map.of(
                "zabbix-api", TestSecrets.randomToken(),
                "alertmanager-password", TestSecrets.randomToken()));
    app = VigilTestApp.start(database, secrets.folder(), List.of());
    app.awaitStarted();
    connectors = app.context().getBean(ConnectorService.class);
    transaction = new TransactionTemplate(app.context().getBean(PlatformTransactionManager.class));
    beans = new LinkedHashMap<>();
    app.context()
        .getBeansOfType(MonitoringSourceConnector.class)
        .values()
        .forEach(bean -> beans.put(bean.kind(), bean));
    tenantA = new TenantId(Ids.newId());
    String local = "http://127.0.0.1:" + wiremock.port();
    ids =
        app.context()
            .getBean(ConnectorImport.class)
            .importPart(
                tenantA,
                new ConnectorsPart(
                    List.of(
                        TestParts.credential("za", "external_api", "secret:zabbix-api"),
                        TestParts.credential("aa", "external_api", "secret:alertmanager-password")),
                    List.of(
                        TestParts.connector(
                            "zabbix-ok",
                            "zabbix",
                            local,
                            "{\"api_path\":\"/api_jsonrpc.php\"}",
                            "za"),
                        TestParts.connector(
                            "alertmanager-ok",
                            "alertmanager",
                            local,
                            "{\"username\":\"vigil\"}",
                            "aa"))),
                Instant.now(),
                Map.of());
    wiremock.stubFor(
        post(urlEqualTo(ConnectionTestIT.ZABBIX_PATH))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withBody("{\"jsonrpc\":\"2.0\",\"result\":[],\"id\":1}")));
    wiremock.stubFor(
        get(urlEqualTo(ConnectionTestIT.ALERTMANAGER_PATH))
            .willReturn(aResponse().withStatus(200)));
  }

  @AfterAll
  static void stop() {
    app.close();
    wiremock.stop();
  }

  private static ConnectorInstanceView instance(String handle) {
    return connectors.instance(tenantA, ids.get(handle)).orElseThrow();
  }

  @Test
  @DisplayName("AR-Q-04 every external call entry point refuses to run inside a transaction")
  void entryPoints() {
    assertThat(beans.keySet())
        .containsExactlyInAnyOrder(ConnectorKind.ZABBIX, ConnectorKind.ALERTMANAGER);
    assertThat(app.context().getBeansOfType(MonitoringSourceConnector.class)).hasSize(2);
    Map<String, Supplier<ConnectionTestResult>> entryPoints = new LinkedHashMap<>();
    entryPoints.put(
        "ConnectorService.testConnection",
        () -> connectors.testConnection(tenantA, ids.get("zabbix-ok")));
    entryPoints.put(
        "MonitoringSourceConnector.test of zabbix",
        () -> beans.get(ConnectorKind.ZABBIX).test(instance("zabbix-ok")));
    entryPoints.put(
        "MonitoringSourceConnector.test of alertmanager",
        () -> beans.get(ConnectorKind.ALERTMANAGER).test(instance("alertmanager-ok")));
    for (Map.Entry<String, Supplier<ConnectionTestResult>> entry : entryPoints.entrySet()) {
      wiremock.resetRequests();
      assertThatThrownBy(() -> transaction.execute(status -> entry.getValue().get()))
          .as(entry.getKey())
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("external call inside a transaction");
      wiremock.verify(0, anyRequestedFor(anyUrl()));
      assertThat(entry.getValue().get())
          .as(entry.getKey())
          .isEqualTo(new ConnectionTestResult.Passed());
      wiremock.verify(1, anyRequestedFor(anyUrl()));
    }
  }

  @Test
  @DisplayName("AR-R-09 parse is not an external call and does not read the content type")
  void parseIsNotGuarded() {
    byte[] body = Recordings.bytes(Recordings.body("0001-zabbix-lab.json"));
    ParseResult inside =
        transaction.execute(
            status -> connectors.parse(ConnectorKind.ZABBIX, body, "application/json"));
    assertThat(inside).isInstanceOf(ParseResult.Parsed.class);
    ParseResult expected = ZabbixPayloads.parse(body);
    for (String contentType : Arrays.asList("application/json", "text/plain", null)) {
      assertThat(connectors.parse(ConnectorKind.ZABBIX, body, contentType)).isEqualTo(expected);
    }
    assertThat(inside).isEqualTo(expected);
  }
}
