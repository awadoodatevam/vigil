package vigil.intake;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.yaml.YAMLMapper;
import vigil.connectors.api.ConnectorImport;
import vigil.connectors.api.ConnectorsPart;
import vigil.connectors.api.ConnectorsPart.ConnectorInstancePart;
import vigil.connectors.api.ConnectorsPart.CredentialReferencePart;
import vigil.intake.api.AlertReceived;
import vigil.intake.api.AlertStateChanged;
import vigil.intake.api.IntakeImport;
import vigil.intake.api.IntakePart;
import vigil.intake.api.IntakePart.AlertTypePart;
import vigil.intake.api.IntakePart.MappingPart;
import vigil.intake.api.IntakePart.SourcePart;
import vigil.intake.api.IntakeService;
import vigil.intake.api.SourceHealthChanged;
import vigil.intake.api.SourceView;
import vigil.shared.TenantId;
import vigil.testsupport.TestSecrets;
import vigil.testsupport.TestTenants;
import vigil.testsupport.VigilDatabase;
import vigil.testsupport.VigilTestApp;

/**
 * The intake fixture of P-13 spec Section 7: the connector part with the webhook and API
 * credentials, the WireMock stubs of the two connection tests, the intake part of {@code
 * 03-monitoring.yaml}, the recordings and an event recorder.
 */
public final class IntakeFixture {

  static final JsonMapper JSON = JsonMapper.builder().build();
  static final String ZABBIX_PATH = "/api_jsonrpc.php";
  static final String ALERTMANAGER_PATH = "/api/v2/status";
  public static final String ZABBIX = "zabbix-lab";
  public static final String ALERTMANAGER = "alertmanager-lab";
  public static final Duration WAIT = Duration.ofSeconds(5);
  private static final Path MONITORING = Path.of("../docs/31-demo-data/03-monitoring.yaml");
  private static final Path RECORDINGS = Path.of("src/test/resources/recordings/monitoring");

  private IntakeFixture() {}

  /**
   * A secret folder with the installation token, the API secrets and the webhook secrets {@code
   * webhook-zabbix{suffix}} and {@code webhook-alertmanager{suffix}} of each suffix ({@code ""},
   * {@code "-b"}, {@code "-c"}).
   */
  public static TestSecrets secrets(VigilDatabase database, String... suffixes) {
    Map<String, String> values = new LinkedHashMap<>();
    values.put("installation-token", TestSecrets.randomToken());
    values.put("zabbix-api", TestSecrets.randomToken());
    values.put("alertmanager-password", TestSecrets.randomToken());
    for (String suffix : suffixes) {
      values.put("webhook-zabbix" + suffix, TestSecrets.randomToken());
      values.put("webhook-alertmanager" + suffix, TestSecrets.randomToken());
    }
    return TestSecrets.create(database, values);
  }

  /** The two connection tests pass. */
  public static void stub(WireMockServer wiremock) {
    wiremock.stubFor(
        post(urlEqualTo(ZABBIX_PATH))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withBody("{\"jsonrpc\":\"2.0\",\"result\":[],\"id\":1}")));
    wiremock.stubFor(get(urlEqualTo(ALERTMANAGER_PATH)).willReturn(aResponse().withStatus(200)));
  }

  /** The connector part of the fixture; the webhook secrets carry {@code suffix}. */
  public static ConnectorsPart connectorsPart(String address, String suffix) {
    return new ConnectorsPart(
        List.of(
            new CredentialReferencePart(
                "cred-webhook-zabbix", "webhook_token", "secret:webhook-zabbix" + suffix),
            new CredentialReferencePart(
                "cred-webhook-alertmanager",
                "webhook_token",
                "secret:webhook-alertmanager" + suffix),
            new CredentialReferencePart("cred-zabbix-api", "external_api", "secret:zabbix-api"),
            new CredentialReferencePart(
                "cred-alertmanager-api", "external_api", "secret:alertmanager-password")),
        List.of(
            new ConnectorInstancePart(
                ZABBIX,
                "Zabbix lab",
                "zabbix",
                address,
                JSON.readTree("{\"api_path\": \"/api_jsonrpc.php\"}"),
                "cred-zabbix-api"),
            new ConnectorInstancePart(
                ALERTMANAGER,
                "Alertmanager lab",
                "alertmanager",
                address,
                JSON.readTree("{\"username\": \"vigil\"}"),
                "cred-alertmanager-api")));
  }

  /**
   * The intake part of {@code 03-monitoring.yaml}, each value's text as the seed import reads it.
   */
  public static IntakePart demoPart() {
    JsonNode file;
    try {
      file =
          YAMLMapper.builder()
              .build()
              .readTree(Files.readString(MONITORING, StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    List<SourcePart> sources = new ArrayList<>();
    for (JsonNode s : file.get("monitoring_sources")) {
      sources.add(
          new SourcePart(
              text(s, "name"),
              text(s, "source_key"),
              text(s, "source_kind"),
              text(s, "connector_instance"),
              text(s, "webhook_token_ref"),
              text(s, "health_check_interval_s"),
              text(s, "stale_after_s"),
              text(s, "disconnected_after_s")));
    }
    List<AlertTypePart> types = new ArrayList<>();
    for (JsonNode t : file.get("alert_types")) {
      types.add(new AlertTypePart(text(t, "alert_type"), text(t, "display_name")));
    }
    List<MappingPart> mappings = new ArrayList<>();
    for (JsonNode m : file.get("alert_type_mappings")) {
      mappings.add(
          new MappingPart(
              text(m, "source_kind"),
              text(m, "order"),
              text(m, "field"),
              text(m, "operator"),
              text(m, "value"),
              text(m, "alert_type")));
    }
    return new IntakePart(sources, types, mappings);
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    return value.isString() ? value.stringValue() : value.asString();
  }

  /** The part with the source {@code sourceKey} changed. */
  static IntakePart source(IntakePart part, String sourceKey, UnaryOperator<SourcePart> change) {
    List<SourcePart> sources = new ArrayList<>();
    boolean found = false;
    for (SourcePart source : part.sources()) {
      boolean match = !found && sourceKey.equals(source.sourceKey());
      found |= match;
      sources.add(match ? change.apply(source) : source);
    }
    assertThat(found).as("source %s", sourceKey).isTrue();
    return new IntakePart(sources, part.alertTypes(), part.mappings());
  }

  /** The part with the alert type {@code alertType} changed. */
  static IntakePart alertType(
      IntakePart part, String alertType, UnaryOperator<AlertTypePart> change) {
    List<AlertTypePart> types = new ArrayList<>();
    boolean found = false;
    for (AlertTypePart type : part.alertTypes()) {
      boolean match = !found && alertType.equals(type.alertType());
      found |= match;
      types.add(match ? change.apply(type) : type);
    }
    assertThat(found).as("alert type %s", alertType).isTrue();
    return new IntakePart(part.sources(), types, part.mappings());
  }

  /** The part with mapping number {@code index} (from 0) changed. */
  static IntakePart mapping(IntakePart part, int index, UnaryOperator<MappingPart> change) {
    List<MappingPart> mappings = new ArrayList<>(part.mappings());
    mappings.set(index, change.apply(mappings.get(index)));
    return new IntakePart(part.sources(), part.alertTypes(), mappings);
  }

  /** Imports the connector part and {@code part} into the tenant; returns the connector handles. */
  public static Map<String, UUID> importInto(
      VigilTestApp app, TenantId tenant, String address, String suffix, IntakePart part) {
    Map<String, UUID> handles =
        app.context()
            .getBean(ConnectorImport.class)
            .importPart(tenant, connectorsPart(address, suffix), Instant.now(), Map.of());
    app.context().getBean(IntakeImport.class).importPart(tenant, part, Instant.now(), handles);
    return handles;
  }

  /** A {@code ready} tenant with an alias for {@code host} (so active) and the fixture imported. */
  public static TenantId create(
      VigilTestApp app, String name, String host, String address, String suffix) {
    TenantId tenant = new TestTenants(app).tenant(name).host(host).create().id();
    importInto(app, tenant, address, suffix, demoPart());
    return tenant;
  }

  /** Waits until each source of the tenant has a recorded test. */
  static void awaitFirstTests(IntakeService intake, TenantId tenant) {
    await(
        () -> {
          List<SourceView> sources = intake.sources(tenant);
          return !sources.isEmpty() && sources.stream().allMatch(s -> s.lastTestAt() != null);
        });
  }

  public static void await(BooleanSupplier condition) {
    long deadline = System.nanoTime() + WAIT.toNanos();
    while (!condition.getAsBoolean()) {
      assertThat(System.nanoTime()).as("condition met within %s", WAIT).isLessThan(deadline);
      sleep(Duration.ofMillis(50));
    }
  }

  public static void sleep(Duration duration) {
    try {
      Thread.sleep(duration);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  /** One recorded call: its file name, source key, content type and body. */
  public record Recording(String name, String sourceKey, String contentType, String body) {

    public byte[] bytes() {
      return body.getBytes(StandardCharsets.UTF_8);
    }

    public JsonNode tree() {
      return JSON.readTree(body);
    }
  }

  /** The 254 recordings in name order. */
  public static List<Recording> recordings() {
    try (Stream<Path> files = Files.list(RECORDINGS)) {
      List<Recording> recordings = new ArrayList<>();
      for (Path file :
          files.filter(f -> f.getFileName().toString().endsWith(".json")).sorted().toList()) {
        JsonNode call = JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
        recordings.add(
            new Recording(
                file.getFileName().toString(),
                call.get("source_key").stringValue(),
                call.get("headers").get("Content-Type").stringValue(),
                call.get("body").stringValue()));
      }
      return List.copyOf(recordings);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static Recording recording(String name) {
    return recordings().stream().filter(r -> r.name().equals(name)).findFirst().orElseThrow();
  }

  /** A recorded Alertmanager message whose one alert gets {@code fingerprint}. */
  public static byte[] withFingerprint(Recording recording, String fingerprint) {
    ObjectNode message = (ObjectNode) recording.tree();
    ((ObjectNode) message.get("alerts").get(0)).put("fingerprint", fingerprint);
    return JSON.writeValueAsBytes(message);
  }

  /** Records every intake event after its transaction committed. */
  static class Events {

    final List<Object> received = new CopyOnWriteArrayList<>();

    @TransactionalEventListener
    void on(AlertReceived event) {
      received.add(event);
    }

    @TransactionalEventListener
    void on(AlertStateChanged event) {
      received.add(event);
    }

    @TransactionalEventListener
    void on(SourceHealthChanged event) {
      received.add(event);
    }

    /** The events of one tenant, in order. */
    List<Object> of(TenantId tenant) {
      return received.stream()
          .filter(
              event ->
                  switch (event) {
                    case AlertReceived e -> e.tenantId().equals(tenant);
                    case AlertStateChanged e -> e.tenantId().equals(tenant);
                    case SourceHealthChanged e -> e.tenantId().equals(tenant);
                    default -> false;
                  })
          .toList();
    }
  }
}
