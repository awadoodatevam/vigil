package vigil.recovery;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.yaml.YAMLMapper;
import vigil.correlation.CorrelationFixture;
import vigil.incidents.api.IncidentService;
import vigil.incidents.api.IncidentState;
import vigil.intake.IntakeFixture;
import vigil.intake.IntakeFixture.Recording;
import vigil.intake.api.AlertView;
import vigil.intake.api.IntakePart;
import vigil.intake.api.IntakePart.SourcePart;
import vigil.intake.api.IntakeService;
import vigil.recovery.api.RecoveryImport;
import vigil.recovery.api.RecoveryPart;
import vigil.recovery.api.RecoveryPart.DefinitionPart;
import vigil.recovery.api.RecoveryStepHandler;
import vigil.shared.Key;
import vigil.shared.StepCall;
import vigil.shared.TenantId;
import vigil.testsupport.MutableClock;
import vigil.testsupport.TestSecrets;
import vigil.testsupport.VigilDatabase;
import vigil.testsupport.VigilTestApp;

/**
 * The recovery fixture of P-23 spec Section 6.12: a tenant per storyline (the correlation fixture
 * with an intake part whose sources do not turn stale), seed part 13, alerts and their resolves
 * posted through vigil-intake, and Recovery check step calls.
 */
public final class RecoveryFixture {

  static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Path OPERATIONS =
      Path.of("../docs/31-demo-data/04-operations-actions-recovery.yaml");
  private static final DateTimeFormatter ZABBIX_DATE =
      DateTimeFormatter.ofPattern("uuuu.MM.dd").withZone(ZoneOffset.UTC);
  private static final DateTimeFormatter ZABBIX_TIME =
      DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC);
  private static final Duration SETTLE = Duration.ofSeconds(41);

  public final App servers;
  public final CorrelationFixture correlation;
  public final TenantId tenant;
  public final VigilTestApp app;
  public final MutableClock clock;
  public final IntakeService intake;
  public final IncidentService incidents;
  public final RecoveryStepHandler handler;

  private RecoveryFixture(App servers, CorrelationFixture correlation) {
    this.servers = servers;
    this.correlation = correlation;
    this.tenant = correlation.tenant;
    this.app = servers.app;
    this.clock = servers.clock;
    this.intake = app.context().getBean(IntakeService.class);
    this.incidents = app.context().getBean(IncidentService.class);
    this.handler = app.context().getBean(RecoveryStepHandler.class);
  }

  /**
   * The application of a test class: WireMock answering the connection tests, vigil-app with {@code
   * beans} (giving the class's {@link MutableClock} as the {@code Clock} bean) and no test step
   * handlers, and the secrets of the suffixes {@code -1} to {@code -60}.
   */
  public static final class App implements AutoCloseable {

    public final WireMockServer wiremock;
    public final VigilTestApp app;
    public final MutableClock clock;
    private final TestSecrets secrets;
    private final String address;
    private int tenants;

    private App(MutableClock clock, Class<?> beans) {
      this.clock = clock;
      wiremock = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort());
      wiremock.start();
      IntakeFixture.stub(wiremock);
      VigilDatabase database = VigilDatabase.fresh();
      String[] suffixes =
          IntStream.rangeClosed(1, 60).mapToObj(n -> "-" + n).toArray(String[]::new);
      secrets = IntakeFixture.secrets(database, suffixes);
      app = VigilTestApp.start(database, secrets.folder(), List.of(), beans);
      app.awaitStarted();
      address = "http://127.0.0.1:" + wiremock.port();
    }

    public static App start(MutableClock clock, Class<?> beans) {
      return new App(clock, beans);
    }

    /** A new tenant with the fixture's default intake part. */
    public RecoveryFixture tenant() {
      return tenant(defaultPart());
    }

    /** A new tenant with this intake part. */
    public synchronized RecoveryFixture tenant(IntakePart part) {
      tenants++;
      if (tenants > 60) {
        throw new IllegalStateException("no secret suffix left");
      }
      CorrelationFixture correlation =
          CorrelationFixture.create(
              app,
              secrets,
              "Recovery " + tenants,
              "r" + tenants + ".recovery.test",
              address,
              "-" + tenants,
              part);
      return new RecoveryFixture(this, correlation);
    }

    @Override
    public void close() {
      app.close();
      wiremock.stop();
    }
  }

  /** The demo intake part with both sources stale after 3,600 s and disconnected after 7,200 s. */
  public static IntakePart defaultPart() {
    IntakePart demo = IntakeFixture.demoPart();
    List<SourcePart> sources = new ArrayList<>();
    for (SourcePart s : demo.sources()) {
      sources.add(
          new SourcePart(
              s.name(),
              s.sourceKey(),
              s.sourceKind(),
              s.connectorInstance(),
              s.webhookTokenRef(),
              s.healthCheckIntervalS(),
              "3600",
              "7200"));
    }
    return new IntakePart(sources, demo.alertTypes(), demo.mappings());
  }

  /** The section {@code recovery_check_definitions} of the demo data as seed part 13 reads it. */
  public static RecoveryPart demoPart() {
    JsonNode file;
    try {
      file =
          YAMLMapper.builder()
              .build()
              .readTree(Files.readString(OPERATIONS, StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    List<DefinitionPart> definitions = new ArrayList<>();
    for (JsonNode d : file.get("recovery_check_definitions")) {
      definitions.add(
          new DefinitionPart(
              d.get("recovery_check_key").asString(),
              d.get("name").asString(),
              d.get("conditions"),
              d.get("observation_window_s").asString(),
              d.get("is_default").asString()));
    }
    return new RecoveryPart(List.copyOf(definitions));
  }

  /** Imports the demo definitions. */
  public RecoveryFixture withDemoDefinitions() {
    return withDefinitions(demoPart());
  }

  public RecoveryFixture withDefinitions(RecoveryPart part) {
    app.context().getBean(RecoveryImport.class).importPart(tenant, part, Instant.now(), Map.of());
    return this;
  }

  // Alerts.

  /** Posts a Zabbix alert and waits until it is decided. */
  public AlertView zabbix(String component, String host, String eventName) {
    AlertView alert = correlation.postZabbix(component, host, eventName);
    correlation.awaitDecided();
    return alert;
  }

  /** Posts an Alertmanager alert and waits until it is decided. */
  public AlertView alertmanager(String component, String instance, String alertname) {
    AlertView alert = correlation.postAlertmanager(component, instance, alertname);
    correlation.awaitDecided();
    return alert;
  }

  /** Posts the resolve of a firing alert, made from its source's first firing recording. */
  public void resolve(AlertView alert) {
    Instant now = clock.instant();
    if (alert.sourceKind().key().equals("zabbix")) {
      ObjectNode message = (ObjectNode) first(IntakeFixture.ZABBIX).tree();
      message.put("event_value", "0");
      message.put("event_id", alert.alertKey());
      message.put("recovery_date", ZABBIX_DATE.format(now));
      message.put("recovery_time", ZABBIX_TIME.format(now));
      correlation.post(IntakeFixture.ZABBIX, JSON.writeValueAsBytes(message));
    } else {
      ObjectNode message = (ObjectNode) first(IntakeFixture.ALERTMANAGER).tree();
      ObjectNode first = (ObjectNode) message.get("alerts").get(0);
      first.put("status", "resolved");
      first.put("fingerprint", alert.alertKey());
      first.put("endsAt", now.toString());
      correlation.post(IntakeFixture.ALERTMANAGER, JSON.writeValueAsBytes(message));
    }
  }

  private static Recording first(String sourceKey) {
    return CorrelationFixture.storm().stream()
        .filter(r -> r.sourceKey().equals(sourceKey))
        .findFirst()
        .orElseThrow();
  }

  /** The alert as stored now. */
  public AlertView alert(AlertView alert) {
    return intake.alert(tenant, alert.id()).orElseThrow();
  }

  /** The incident of the alert, after the settle period, {@code open}. */
  public UUID open(AlertView alert) {
    UUID incident = incidents.incidentOfAlert(tenant, alert.id()).orElseThrow();
    clock.advance(SETTLE);
    correlation.awaitState(incident, IncidentState.OPEN);
    return incident;
  }

  // Step calls.

  /** A call of the step {@code s-recover} with {@code max_wait}; {@code key} may be null. */
  public StepCall call(
      UUID run, UUID incident, UUID stepExecution, Object maxWait, long count, String key) {
    return call(run, incident, stepExecution, "{\"max_wait\": " + maxWait + "}", count, key);
  }

  public StepCall call(
      UUID run, UUID incident, UUID stepExecution, String parameters, long count, String key) {
    return new StepCall(
        tenant,
        run,
        incident,
        stepExecution,
        new Key("s-recover"),
        StepCall.StepType.RECOVERY_CHECK,
        parameters,
        count,
        null,
        new StepCall.WakeReason(count == 1 ? "start" : "timer"),
        key == null ? null : new Key(key),
        List.of());
  }
}
