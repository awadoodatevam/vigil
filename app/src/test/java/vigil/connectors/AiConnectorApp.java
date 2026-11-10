package vigil.connectors;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import vigil.connectors.api.AiPromptPart;
import vigil.connectors.api.AiProviderConnector;
import vigil.connectors.api.ConnectorImport;
import vigil.connectors.api.ConnectorInstanceView;
import vigil.connectors.api.ConnectorKind;
import vigil.connectors.api.ConnectorService;
import vigil.connectors.api.ConnectorsPart;
import vigil.connectors.api.ConnectorsPart.ConnectorInstancePart;
import vigil.shared.Ids;
import vigil.shared.TenantId;
import vigil.testsupport.TestSecrets;
import vigil.testsupport.VigilDatabase;
import vigil.testsupport.VigilTestApp;

/**
 * vigil-app on a fresh database with the secrets {@code claude} and {@code openai} (generated at
 * start), a WireMock server and one tenant whose part 3 holds the five AI instances of the demo at
 * the WireMock address, an instance whose secret is missing and instances at a closed port.
 */
final class AiConnectorApp implements AutoCloseable {

  static final String SL_T_1_SCHEMA_NAME = "sl_t_1";
  static final List<AiPromptPart> TWO_PARTS =
      List.of(
          new AiPromptPart("Member alerts", "Alert data", "1. disk_full | srv-db-01"),
          new AiPromptPart("E1", "Diagnostic output", "lab-db-01: /data 100% used"));
  static final String TWO_PARTS_RENDERED =
      "### Member alerts (Alert data)\n1. disk_full | srv-db-01\n\n"
          + "### E1 (Diagnostic output)\nlab-db-01: /data 100% used";

  final WireMockServer wiremock;
  final VigilTestApp app;
  final TestSecrets secrets;
  final TenantId tenantId;
  final int closedPort;
  private final Map<String, UUID> ids;

  AiConnectorApp(Class<?>... configurations) {
    wiremock = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort());
    wiremock.start();
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      closedPort = socket.getLocalPort();
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
    VigilDatabase database = VigilDatabase.fresh();
    secrets =
        TestSecrets.create(
            database,
            Map.of("claude", TestSecrets.randomToken(), "openai", TestSecrets.randomToken()));
    app = VigilTestApp.start(database, secrets.folder(), List.of(), configurations);
    app.awaitStarted();
    tenantId = new TenantId(Ids.newId());
    String local = "http://127.0.0.1:" + wiremock.port();
    String closed = "http://127.0.0.1:" + closedPort;
    List<ConnectorInstancePart> instances = new ArrayList<>();
    for (String address : List.of(local, closed)) {
      String suffix = address.equals(local) ? "" : "-closed";
      instances.add(
          TestParts.connector(
              "claude-api" + suffix,
              "claude",
              address,
              "{\"anthropic_version\":\"2023-06-01\"}",
              "cred-claude"));
      instances.add(
          TestParts.connector("openai-api" + suffix, "openai", address, "{}", "cred-openai"));
      instances.add(
          TestParts.connector("openai-embed-api" + suffix, "openai", address, "{}", "cred-openai"));
      instances.add(
          TestParts.connector(
              "local-llm" + suffix,
              "local_model",
              address,
              "{\"keep_alive\":-1,\"num_ctx\":16384}",
              null));
      instances.add(
          TestParts.connector(
              "local-embed" + suffix, "local_embedding", address, "{\"keep_alive\":-1}", null));
    }
    instances.add(
        TestParts.connector(
            "claude-version",
            "claude",
            local,
            "{\"anthropic_version\":\"2099-01-01\"}",
            "cred-claude"));
    instances.add(
        TestParts.connector(
            "local-embed-300", "local_embedding", local, "{\"keep_alive\":300}", null));
    ids =
        app.context()
            .getBean(ConnectorImport.class)
            .importPart(
                tenantId,
                new ConnectorsPart(
                    List.of(
                        TestParts.credential("cred-claude", "external_api", "secret:claude"),
                        TestParts.credential("cred-openai", "external_api", "secret:openai")),
                    instances),
                Instant.now(),
                Map.of());
  }

  /** The connector bean of {@code kind}. */
  AiProviderConnector connector(ConnectorKind kind) {
    return app.context().getBeansOfType(AiProviderConnector.class).values().stream()
        .filter(bean -> bean.kind() == kind)
        .findFirst()
        .orElseThrow();
  }

  ConnectorInstanceView instance(String handle) {
    return app.context()
        .getBean(ConnectorService.class)
        .instance(tenantId, ids.get(handle))
        .orElseThrow();
  }

  /** The SL-T-1 output schema P-06 sent. */
  static JsonNode slT1Schema() {
    return Recordings.provider("claude", "0001-sl-t-1.json")
        .request()
        .get("output_config")
        .get("format")
        .get("schema");
  }

  @Override
  public void close() {
    app.close();
    wiremock.stop();
  }
}
