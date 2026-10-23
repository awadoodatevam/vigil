package vigil.correlation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import vigil.correlation.api.CorrelationImport;
import vigil.correlation.api.CorrelationScheduling;
import vigil.correlation.api.CorrelationService;
import vigil.correlation.api.CorrelationSettingsPart;
import vigil.incidents.api.DataCategory;
import vigil.incidents.api.EvidenceKind;
import vigil.incidents.api.IncidentImport;
import vigil.incidents.api.IncidentService;
import vigil.incidents.api.IncidentSettingsPart;
import vigil.incidents.api.IncidentState;
import vigil.intake.IntakeFixture;
import vigil.intake.IntakeFixture.Recording;
import vigil.intake.api.AlertView;
import vigil.intake.api.IntakeService;
import vigil.routing.RoutingFixture;
import vigil.routing.api.RoutingImport;
import vigil.servicemap.api.ServiceMapService;
import vigil.shared.Actor;
import vigil.shared.Ids;
import vigil.shared.TenantId;
import vigil.testsupport.TestSecrets;
import vigil.testsupport.TestTenants;
import vigil.testsupport.VigilTestApp;

/**
 * The correlation fixture of P-16 spec Section 7: an active tenant with the nine teams of {@code
 * 01-tenant-identity.yaml}, the intake fixture, the demo service map, the demo routing part (unless
 * left out), incident settings and correlation settings, its worker running; and helpers that post
 * alerts through vigil-intake.
 */
public final class CorrelationFixture {

  static final JsonMapper JSON = JsonMapper.builder().build();
  static final Actor RECOVERY = Actor.module("vigil-recovery");
  static final Duration WAIT = Duration.ofSeconds(10);
  static final Path SERVICE_MAP = Path.of("../docs/31-demo-data/02-service-map.yaml");
  private static final DateTimeFormatter ZABBIX_DATE =
      DateTimeFormatter.ofPattern("uuuu.MM.dd").withZone(ZoneOffset.UTC);
  private static final DateTimeFormatter ZABBIX_TIME =
      DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC);
  private static final AtomicLong EVENT_IDS = new AtomicLong(900000);

  public final VigilTestApp app;
  public final TenantId tenant;
  final TestSecrets secrets;
  final String suffix;
  final IntakeService intake;
  final IncidentService incidents;
  final CorrelationService correlation;
  final Clock clock;

  private CorrelationFixture(
      VigilTestApp app, TestSecrets secrets, TenantId tenant, String suffix) {
    this.app = app;
    this.secrets = secrets;
    this.tenant = tenant;
    this.suffix = suffix;
    this.intake = app.context().getBean(IntakeService.class);
    this.incidents = app.context().getBean(IncidentService.class);
    this.correlation = app.context().getBean(CorrelationService.class);
    this.clock = app.context().getBean(Clock.class);
  }

  public static CorrelationFixture create(
      VigilTestApp app,
      TestSecrets secrets,
      String name,
      String host,
      String address,
      String suffix) {
    return create(app, secrets, name, host, address, suffix, true);
  }

  public static CorrelationFixture create(
      VigilTestApp app,
      TestSecrets secrets,
      String name,
      String host,
      String address,
      String suffix,
      boolean withRouting) {
    TenantId tenant = teams(new TestTenants(app).tenant(name)).host(host).create().id();
    IntakeFixture.importInto(app, tenant, address, suffix, IntakeFixture.demoPart());
    importServiceMap(app, tenant, serviceMap());
    if (withRouting) {
      app.context()
          .getBean(RoutingImport.class)
          .importPart(tenant, RoutingFixture.demoPart(), Instant.now(), Map.of());
    }
    app.context()
        .getBean(IncidentImport.class)
        .importPart(tenant, new IncidentSettingsPart("300", "60"), Instant.now(), Map.of());
    importSettings(app, tenant, "300", "40", "300", "1");
    app.context().getBean(CorrelationScheduling.class).matchActiveTenants();
    await(() -> work(app).runningTenants().contains(tenant), WAIT);
    return new CorrelationFixture(app, secrets, tenant, suffix);
  }

  /** The fixture of a tenant created earlier, for an application started again. */
  static CorrelationFixture of(
      VigilTestApp app, TestSecrets secrets, TenantId tenant, String suffix) {
    return new CorrelationFixture(app, secrets, tenant, suffix);
  }

  /** The nine teams of {@code 01-tenant-identity.yaml} with their names. */
  static TestTenants.Builder teams(TestTenants.Builder builder) {
    return builder
        .team("noc-l1", "NOC L1")
        .team("db-ops", "Database Operations")
        .team("it-apps", "IT Applications")
        .team("it-infra", "IT Infrastructure")
        .team("vas-ops", "VAS Operations")
        .team("vas-service-owners", "VAS Service Owners")
        .team("noc-shift-lead", "NOC Shift Lead")
        .team("noc-management", "NOC Management")
        .team("customer-care", "Customer Care");
  }

  static String serviceMap() {
    try {
      return Files.readString(SERVICE_MAP, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static void importServiceMap(VigilTestApp app, TenantId tenant, String content) {
    app.context()
        .getBean(ServiceMapService.class)
        .importFile(
            tenant,
            "02-service-map.yaml",
            content.getBytes(StandardCharsets.UTF_8),
            Actor.module("vigil-setup"));
  }

  static void importSettings(
      VigilTestApp app,
      TenantId tenant,
      String window,
      String settle,
      String maxForming,
      String interval) {
    app.context()
        .getBean(CorrelationImport.class)
        .importPart(
            tenant,
            new CorrelationSettingsPart(window, settle, maxForming, interval),
            Instant.now(),
            Map.of());
  }

  /** Whether the tenant has a running correlation worker (for tests in other packages). */
  public static boolean isRunning(VigilTestApp app, TenantId tenant) {
    return work(app).runningTenants().contains(tenant);
  }

  static CorrelationWork work(VigilTestApp app) {
    return app.context().getBean(CorrelationWork.class);
  }

  // Posting alerts through vigil-intake.

  int post(Recording recording) {
    return post(recording.sourceKey(), recording.bytes());
  }

  int post(String sourceKey, byte[] body) {
    String secret =
        sourceKey.equals(IntakeFixture.ZABBIX) ? "webhook-zabbix" : "webhook-alertmanager";
    return intake.receiveWebhook(
        tenant, sourceKey, secrets.value(secret + suffix), "application/json", body);
  }

  /** The 127 firing recordings: the 126 storm alerts and the SL-F-3 alert. */
  static List<Recording> storm() {
    return IntakeFixture.recordings().stream().filter(CorrelationFixture::firing).toList();
  }

  private static boolean firing(Recording recording) {
    JsonNode body = recording.tree();
    return recording.sourceKey().equals(IntakeFixture.ZABBIX)
        ? body.get("event_value").stringValue().equals("1")
        : body.get("alerts").get(0).get("status").stringValue().equals("firing");
  }

  private static Recording first(String sourceKey) {
    return storm().stream().filter(r -> r.sourceKey().equals(sourceKey)).findFirst().orElseThrow();
  }

  /** An Alertmanager message with one new alert on {@code component} fired now. */
  byte[] alertmanager(String component, String instance, String alertname) {
    ObjectNode message = (ObjectNode) first(IntakeFixture.ALERTMANAGER).tree();
    ObjectNode alert = (ObjectNode) message.get("alerts").get(0);
    alert.put("fingerprint", UUID.randomUUID().toString());
    ObjectNode labels = (ObjectNode) alert.get("labels");
    labels.put("component", component);
    labels.put("instance", instance);
    labels.put("alertname", alertname);
    ((ObjectNode) alert.get("annotations")).put("summary", alertname + " on " + component);
    alert.put("startsAt", clock.instant().toString());
    return JSON.writeValueAsBytes(message);
  }

  /** A Zabbix message with a new event on {@code component} and {@code host} fired now. */
  byte[] zabbix(String component, String host, String eventName) {
    ObjectNode message = (ObjectNode) first(IntakeFixture.ZABBIX).tree();
    message.put("event_id", Long.toString(EVENT_IDS.getAndIncrement()));
    message.put("host", host);
    message.put("event_name", eventName);
    for (JsonNode tag : (ArrayNode) message.get("tags")) {
      if (tag.get("tag").stringValue().equals("component")) {
        ((ObjectNode) tag).put("value", component);
      }
    }
    Instant now = clock.instant();
    message.put("event_date", ZABBIX_DATE.format(now));
    message.put("event_time", ZABBIX_TIME.format(now));
    return JSON.writeValueAsBytes(message);
  }

  /** Posts an Alertmanager alert and returns it once created. */
  AlertView postAlertmanager(String component, String instance, String alertname) {
    long before = highestSequence();
    post(IntakeFixture.ALERTMANAGER, alertmanager(component, instance, alertname));
    return alertAt(before + 1);
  }

  /** Posts a Zabbix alert and returns it once created. */
  AlertView postZabbix(String component, String host, String eventName) {
    long before = highestSequence();
    post(IntakeFixture.ZABBIX, zabbix(component, host, eventName));
    return alertAt(before + 1);
  }

  private AlertView alertAt(long sequence) {
    List<AlertView> alerts = intake.alertsAfter(tenant, sequence - 1, 1);
    assertThat(alerts).hasSize(1);
    return alerts.getFirst();
  }

  /** The tenant's alerts in sequence order. */
  List<AlertView> alerts() {
    List<AlertView> all = new ArrayList<>();
    long after = 0;
    while (true) {
      List<AlertView> page = intake.alertsAfter(tenant, after, 100);
      all.addAll(page);
      if (page.size() < 100) {
        return List.copyOf(all);
      }
      after = page.getLast().sequence();
    }
  }

  long highestSequence() {
    List<AlertView> alerts = alerts();
    return alerts.isEmpty() ? 0 : alerts.getLast().sequence();
  }

  /** Waits until the cursor is the tenant's highest alert sequence. */
  void awaitDecided(Duration wait) {
    long highest = highestSequence();
    await(() -> correlation.cursor(tenant) == highest, wait);
  }

  void awaitDecided() {
    awaitDecided(WAIT);
  }

  void awaitState(UUID incidentId, IncidentState state) {
    await(() -> incidents.incident(tenant, incidentId).orElseThrow().state() == state, WAIT);
  }

  /** Resolves the open incident with a new verification's evidence entry, from vigil-recovery. */
  void resolve(UUID incidentId) {
    UUID verificationId = Ids.newId();
    incidents.addEvidence(
        tenant,
        RECOVERY,
        incidentId,
        EvidenceKind.RECOVERY_VERIFICATION,
        verificationId,
        "Monitoring: Zabbix lab",
        Instant.now(),
        "Recovered.",
        "{\"outcome\": \"recovered\"}",
        DataCategory.INCIDENT_RECORD);
    incidents.resolve(tenant, RECOVERY, incidentId, verificationId);
  }

  static void await(BooleanSupplier condition, Duration wait) {
    long deadline = System.nanoTime() + wait.toNanos();
    while (!condition.getAsBoolean()) {
      assertThat(System.nanoTime()).as("condition met within %s", wait).isLessThan(deadline);
      IntakeFixture.sleep(Duration.ofMillis(50));
    }
  }
}
