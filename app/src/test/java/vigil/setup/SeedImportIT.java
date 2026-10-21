package vigil.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vigil.audit.api.AuditEventType;
import vigil.audit.api.AuditEventView;
import vigil.audit.api.AuditQuery;
import vigil.audit.api.AuditService;
import vigil.identity.api.IdentityService;
import vigil.servicemap.api.Counts;
import vigil.servicemap.api.GenerationView;
import vigil.servicemap.api.ServiceMapImported;
import vigil.servicemap.api.ServiceMapService;
import vigil.setup.api.Installation;
import vigil.setup.api.PartOutcome;
import vigil.setup.api.PartView;
import vigil.setup.api.SeedImportState;
import vigil.setup.api.SeedImportView;
import vigil.setup.api.SeedImports;
import vigil.shared.Actor;
import vigil.shared.Ids;
import vigil.shared.TenantId;
import vigil.shared.VigilError;
import vigil.tenancy.api.TenantLogo;
import vigil.tenancy.api.TenantService;
import vigil.tenancy.api.TenantSettingsView;
import vigil.tenancy.api.TenantState;
import vigil.testsupport.SeedBundles;
import vigil.testsupport.TestSecrets;
import vigil.testsupport.VigilDatabase;
import vigil.testsupport.VigilTestApp;

class SeedImportIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String IDENTITY = "01-tenant-identity.yaml";
  private static final String SETTINGS = "07-settings.yaml";
  private static final List<String> MODULES =
      List.of(
          "vigil-tenancy",
          "vigil-identity",
          "vigil-connectors",
          "vigil-runner",
          "vigil-servicemap",
          "vigil-intake",
          "vigil-ai-gateway",
          "vigil-knowledge",
          "vigil-incidents",
          "vigil-correlation",
          "vigil-routing",
          "vigil-diagnostics",
          "vigil-recovery",
          "vigil-actions",
          "vigil-notifications",
          "vigil-workflows",
          "vigil-reporting");
  private static final String ADMIN =
      "  - {username: admin,          display_name: \"Platform Administrator\"}";
  private static final UnaryOperator<String> ADD_GHOST =
      SeedTests.replacing(ADMIN, ADMIN + "\n  - {username: ghost, display_name: \"Ghost\"}");
  private static final byte[] PIXEL_PNG =
      Base64.getDecoder()
          .decode(
              "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==");

  private static final RecordingCheck RECORDING_CHECK = new RecordingCheck();
  private static final List<ServiceMapImported> SERVICE_MAP_EVENTS = new CopyOnWriteArrayList<>();
  private static final LatchImporter LATCH = new LatchImporter();

  private static App main;
  private static App latch;
  private static App failingCheck;

  /** One app on its own fresh database. */
  private record App(
      VigilDatabase database,
      TestSecrets secrets,
      VigilTestApp app,
      SeedImports seedImports,
      Installation installation,
      TenantService tenants) {

    static App start(Class<?> configuration) {
      VigilDatabase database = VigilDatabase.fresh();
      TestSecrets secrets = SeedTests.secrets(database);
      VigilTestApp app = VigilTestApp.start(database, secrets.folder(), List.of(), configuration);
      app.awaitStarted();
      return new App(
          database,
          secrets,
          app,
          app.context().getBean(SeedImports.class),
          app.context().getBean(Installation.class),
          app.context().getBean(TenantService.class));
    }

    TenantId tenant() {
      return installation.createTenant("seed import test " + Ids.newId());
    }

    /** A new tenant, the bundle imported into it and awaited. */
    SeedImportView imported(byte[] bundle) {
      TenantId tenant = tenant();
      return await(seedImports.start(tenant, bundle));
    }

    SeedImportView await(SeedImportView started) {
      return SeedTests.await(seedImports, started.id());
    }

    TenantState state(TenantId tenant) {
      return tenants.tenant(tenant).orElseThrow().state();
    }

    JdbcClient postgres() {
      return database.postgres();
    }
  }

  /** The key check of the main app: referring part 3, target part 6; records its calls. */
  static final class RecordingCheck implements SeedKeyCheck {

    final List<TenantId> calls = new CopyOnWriteArrayList<>();

    @Override
    public int referringPart() {
      return 3;
    }

    @Override
    public int targetPart() {
      return 6;
    }

    @Override
    public List<String> problems(
        TenantId tenantId,
        SeedBundle bundle,
        Map<String, JsonNode> files,
        Map<String, UUID> handles) {
      calls.add(tenantId);
      return List.of();
    }
  }

  /** The latch importer: part 4; a held tenant waits until its latch is released. */
  static final class LatchImporter implements SeedPartImporter {

    final Map<TenantId, CountDownLatch> latches = new ConcurrentHashMap<>();
    final Map<TenantId, CountDownLatch> entered = new ConcurrentHashMap<>();
    final Map<TenantId, Thread> threads = new ConcurrentHashMap<>();
    final Map<TenantId, Map<String, UUID>> received = new ConcurrentHashMap<>();

    CountDownLatch hold(TenantId tenantId) {
      CountDownLatch release = new CountDownLatch(1);
      latches.put(tenantId, release);
      entered.put(tenantId, new CountDownLatch(1));
      return release;
    }

    void awaitEntered(TenantId tenantId) throws InterruptedException {
      assertThat(entered.get(tenantId).await(60, TimeUnit.SECONDS)).as("part 4 reached").isTrue();
    }

    @Override
    public int part() {
      return 4;
    }

    @Override
    public PartResult importPart(
        TenantId tenantId,
        SeedBundle bundle,
        Map<String, JsonNode> files,
        Instant importStartedAt,
        Map<String, UUID> handles) {
      CountDownLatch release = latches.get(tenantId);
      if (release != null) {
        threads.put(tenantId, Thread.currentThread());
        received.put(tenantId, Map.copyOf(handles));
        entered.get(tenantId).countDown();
        try {
          release.await(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      return new PartResult(Map.of(), Map.of());
    }
  }

  /** The beans of the main app. */
  static class MainBeans {

    @Bean
    SeedKeyCheck recordingKeyCheck() {
      return RECORDING_CHECK;
    }

    @Bean
    ServiceMapListener serviceMapListener() {
      return new ServiceMapListener();
    }
  }

  /**
   * Records every {@code ServiceMapImported} the main app publishes, in or out of a transaction.
   */
  static class ServiceMapListener {

    @EventListener
    void on(ServiceMapImported event) {
      SERVICE_MAP_EVENTS.add(event);
    }
  }

  /** The beans of the latch app. */
  static class LatchBeans {

    @Bean
    SeedPartImporter latchImporter() {
      return LATCH;
    }
  }

  /** The beans of the failing-check app. */
  static class FailingCheckBeans {

    @Bean
    SeedKeyCheck failingKeyCheck() {
      return new SeedKeyCheck() {
        @Override
        public int referringPart() {
          return 3;
        }

        @Override
        public int targetPart() {
          return 2;
        }

        @Override
        public List<String> problems(
            TenantId tenantId,
            SeedBundle bundle,
            Map<String, JsonNode> files,
            Map<String, UUID> handles) {
          return List.of("x is unknown");
        }
      };
    }
  }

  @BeforeAll
  static void start() {
    main = App.start(MainBeans.class);
    latch = App.start(LatchBeans.class);
    failingCheck = App.start(FailingCheckBeans.class);
  }

  @AfterAll
  static void stop() {
    for (App app : new App[] {main, latch, failingCheck}) {
      if (app != null) {
        app.app().close();
      }
    }
  }

  private static PartView part(SeedImportView view, int number) {
    return view.parts().get(number - 1);
  }

  private static void assertFailedPart(SeedImportView view, int number, String error) {
    assertThat(view.state()).isEqualTo(SeedImportState.FAILED);
    assertThat(part(view, number).outcome()).isEqualTo(PartOutcome.FAILED);
    assertThat(part(view, number).error()).isEqualTo(error);
    assertThat(part(view, number).counts()).isEmpty();
  }

  private static long rows(App app, String table, TenantId tenant) {
    return app.postgres()
        .sql("SELECT count(*) FROM " + table + " WHERE tenant_id = :tenantId")
        .param("tenantId", tenant.value())
        .query(Long.class)
        .single();
  }

  private static List<AuditEventView> events(App app, TenantId tenant) {
    return app.app()
        .context()
        .getBean(AuditService.class)
        .query(tenant, new AuditQuery(null, null, null, null, null, null, null, 50, null))
        .items();
  }

  @Test
  @DisplayName("AR-Q-10 the bundle of docs/31-demo-data imports into an empty tenant")
  void demoBundleImports() {
    assertThat(main.imported(SeedTests.demoBundle()).state()).isEqualTo(SeedImportState.SUCCEEDED);
  }

  @Test
  @DisplayName("PM-M22-03 the registered parts pass to their modules' import interfaces")
  void registeredPartsImport() {
    SeedImportView view = main.imported(SeedTests.demoBundle());
    assertThat(view.state()).isEqualTo(SeedImportState.SUCCEEDED);
    assertThat(view.parts().stream().map(PartView::module).toList()).isEqualTo(MODULES);
    for (int number = 1; number <= 17; number++) {
      assertThat(part(view, number).outcome())
          .as("part %s", number)
          .isEqualTo(number <= 3 || number == 5 ? PartOutcome.SUCCEEDED : PartOutcome.NOT_STARTED);
    }
    assertThat(main.state(view.tenantId())).isEqualTo(TenantState.READY);
    TenantSettingsView settings = main.tenants().settings(view.tenantId());
    assertThat(settings.customerName()).isEqualTo("Jazz");
    assertThat(settings.productDisplayName()).isEqualTo("Techfinity");
    assertThat(settings.environmentLabel()).isEqualTo("Demo environment — fictional data");
    assertThat(settings.hasLogo()).isFalse();
    List<String> names =
        main.postgres()
            .sql("SELECT name FROM connectors.connector_instance WHERE tenant_id = :tenantId")
            .param("tenantId", view.tenantId().value())
            .query(String.class)
            .list();
    assertThat(names).hasSize(11).contains("Zabbix — Jazz lab");
  }

  @Test
  @DisplayName("PM-M22-05 each seeded user logs in with its seed-password secret")
  void seededUsersLogIn() {
    SeedImportView view = main.imported(SeedTests.demoBundle());
    IdentityService identity = main.app().context().getBean(IdentityService.class);
    for (String user : SeedTests.USERS) {
      assertThat(
              identity.login(view.tenantId(), user, main.secrets().value("seed-password-" + user)))
          .as("login of %s", user)
          .isPresent();
    }
  }

  @Test
  @DisplayName("AR-B-11 the handle map of the earlier parts reaches the later part")
  void handleMapIsPassedOn() throws InterruptedException {
    List<Map.Entry<String, byte[]>> entries = SeedTests.demoEntries();
    TenantId tenant = latch.tenant();
    CountDownLatch release = LATCH.hold(tenant);
    SeedImportView started = latch.seedImports().start(tenant, SeedBundles.zip(entries));
    LATCH.awaitEntered(tenant);
    Map<String, UUID> received = LATCH.received.get(tenant);
    JsonNode settings = SeedYaml.read(SeedTests.text(entries, SETTINGS));
    assertThat(received).hasSize(30);
    for (JsonNode reference : settings.get("credential_references")) {
      UUID stored =
          latch
              .postgres()
              .sql(
                  "SELECT id FROM connectors.credential_reference"
                      + " WHERE tenant_id = :tenantId AND locator = :locator")
              .param("tenantId", tenant.value())
              .param("locator", reference.get("locator").stringValue())
              .query(UUID.class)
              .single();
      assertThat(received.get(reference.get("handle").stringValue())).isEqualTo(stored);
    }
    for (JsonNode instance : settings.get("connector_instances")) {
      UUID stored =
          latch
              .postgres()
              .sql(
                  "SELECT id FROM connectors.connector_instance"
                      + " WHERE tenant_id = :tenantId AND name = :name")
              .param("tenantId", tenant.value())
              .param("name", instance.get("name").stringValue())
              .query(UUID.class)
              .single();
      assertThat(received.get(instance.get("handle").stringValue())).isEqualTo(stored);
    }
    release.countDown();
    SeedImportView view = latch.await(started);
    assertThat(view.state()).isEqualTo(SeedImportState.SUCCEEDED);
    assertThat(part(view, 4).outcome()).isEqualTo(PartOutcome.SUCCEEDED);
    assertThat(part(view, 4).counts()).isEmpty();
  }

  @Test
  @DisplayName("DM-E-82 the seed import record")
  void seedImportRecord() {
    SeedImportView view = main.imported(SeedTests.demoBundle());
    assertThat(view.bundleName()).isEqualTo("jazz-demo");
    assertThat(view.bundleVersion()).isEqualTo(SeedTests.version());
    assertThat(view.finishedAt()).isNotNull().isAfterOrEqualTo(view.startedAt());
    Map<String, Object> row =
        main.postgres()
            .sql(
                "SELECT tenant_id, started_by_kind, started_by_user_id, started_by_module"
                    + " FROM setup.seed_import WHERE id = :id")
            .param("id", view.id())
            .query()
            .singleRow();
    assertThat(row.get("tenant_id")).isEqualTo(view.tenantId().value());
    assertThat(row.get("started_by_kind")).isEqualTo("module");
    assertThat(row.get("started_by_user_id")).isNull();
    assertThat(row.get("started_by_module")).isEqualTo("vigil-setup");
    assertThat(part(view, 1).counts()).isEqualTo(Map.of("tenant_settings", 1));
    assertThat(part(view, 2).counts())
        .isEqualTo(Map.of("teams", 9, "users", 5, "memberships", 3, "permission_grants", 5));
    assertThat(part(view, 3).counts())
        .isEqualTo(Map.of("credential_references", 19, "connector_instances", 11));
    assertThat(part(view, 5).counts())
        .isEqualTo(Map.of("services", 4, "components", 12, "identity_keys", 15, "dependencies", 6));
    assertThat(view.parts()).allSatisfy(part -> assertThat(part.error()).isNull());
  }

  @Test
  @DisplayName("DM-SM-16 seed import record: running, then succeeded or failed")
  void stateMachine() throws InterruptedException {
    TenantId tenant = latch.tenant();
    CountDownLatch release = LATCH.hold(tenant);
    SeedImportView started = latch.seedImports().start(tenant, SeedTests.demoBundle());
    assertThat(started.state()).isEqualTo(SeedImportState.RUNNING);
    assertThat(latch.seedImports().seedImport(started.id()).orElseThrow().state())
        .isEqualTo(SeedImportState.RUNNING);
    LATCH.awaitEntered(tenant);
    assertThat(latch.seedImports().seedImport(started.id()).orElseThrow().state())
        .isEqualTo(SeedImportState.RUNNING);
    release.countDown();
    SeedImportView succeeded = latch.await(started);
    assertThat(succeeded.state()).isEqualTo(SeedImportState.SUCCEEDED);

    SeedImportView failed = latch.imported(SeedTests.demoBundleWith(IDENTITY, ADD_GHOST));
    assertThat(failed.state()).isEqualTo(SeedImportState.FAILED);
    Thread.sleep(Duration.ofSeconds(2));
    assertThat(latch.seedImports().seedImport(succeeded.id()).orElseThrow()).isEqualTo(succeeded);
    assertThat(latch.seedImports().seedImport(failed.id()).orElseThrow()).isEqualTo(failed);
  }

  @Test
  @DisplayName("DM-E08-R3 one configuration_changed event, written by vigil-setup, on success only")
  void oneAuditEventOnSuccess() {
    SeedImportView view = main.imported(SeedTests.demoBundle());
    List<AuditEventView> events = events(main, view.tenantId());
    assertThat(events).hasSize(1);
    AuditEventView event = events.getFirst();
    assertThat(event.eventType()).isEqualTo(AuditEventType.CONFIGURATION_CHANGED);
    assertThat(event.actor()).isEqualTo(Actor.module("vigil-setup"));
    assertThat(event.subjectType()).isEqualTo("SeedImport");
    assertThat(event.subjectId()).isEqualTo(view.id());
    assertThat(event.incidentId()).isNull();
    assertThat(event.summary())
        .isEqualTo("Seed bundle jazz-demo " + SeedTests.version() + " imported.");

    SeedImportView failed = main.imported(SeedTests.demoBundleWith(IDENTITY, ADD_GHOST));
    assertThat(failed.state()).isEqualTo(SeedImportState.FAILED);
    assertThat(events(main, failed.tenantId())).isEmpty();
  }

  @Test
  @DisplayName("DM-C-07 the seed import event's details: area seed_import, before and after")
  void auditEventDetails() {
    SeedImportView view = main.imported(SeedTests.demoBundle());
    AuditEventView event = events(main, view.tenantId()).getFirst();
    StringBuilder parts = new StringBuilder();
    for (PartView part : view.parts()) {
      parts.append(parts.isEmpty() ? "" : ",");
      parts.append("{\"module\":\"").append(part.module()).append("\",\"outcome\":\"");
      parts.append(part.outcome().key()).append("\",\"counts\":");
      parts.append(JSON.writeValueAsString(part.counts())).append('}');
    }
    String expected =
        "{\"area\":\"seed_import\",\"before\":null,\"after\":{\"bundle_name\":\"jazz-demo\","
            + "\"bundle_version\":\""
            + SeedTests.version()
            + "\",\"parts\":["
            + parts
            + "]}}";
    assertThat(JSON.readTree(event.detailsJson())).isEqualTo(JSON.readTree(expected));
    assertThat(JSON.readTree(event.detailsJson()).get("after").get("parts")).hasSize(17);
  }

  @Test
  @DisplayName("BP-R-29 sections of parts that are not registered are skipped")
  void unregisteredSectionsAreSkipped() {
    SeedImportView view =
        main.imported(
            SeedTests.demoBundleWith(
                "03-monitoring.yaml",
                SeedTests.section("monitoring_sources", "monitoring_sources: 3")));
    assertThat(view.state()).isEqualTo(SeedImportState.SUCCEEDED);
    assertThat(part(view, 6).outcome()).isEqualTo(PartOutcome.NOT_STARTED);
  }

  @Test
  @DisplayName("AR-B-05 an unreadable file or unknown section refuses the first part that reads it")
  void fileChecksRefuseTheirFirstPart() {
    SeedImportView unreadable =
        main.imported(SeedTests.demoBundleWith("03-monitoring.yaml", text -> "a: ["));
    assertThat(unreadable.state()).isEqualTo(SeedImportState.FAILED);
    assertThat(part(unreadable, 6).outcome()).isEqualTo(PartOutcome.FAILED);
    assertThat(part(unreadable, 6).error())
        .startsWith("Part intake refused. 03-monitoring.yaml could not be read: ");
    assertThat(part(unreadable, 4).outcome()).isEqualTo(PartOutcome.NOT_STARTED);
    assertThat(part(unreadable, 5).outcome()).isEqualTo(PartOutcome.SUCCEEDED);
    assertThat(main.state(unreadable.tenantId())).isEqualTo(TenantState.FAILED);

    SeedImportView unknown =
        main.imported(SeedTests.demoBundleWith(SETTINGS, text -> text + "\nai_settings: {}\n"));
    assertFailedPart(
        unknown, 3, "Part connectors refused. 07-settings.yaml: unknown section ai_settings");
    assertThat(part(unknown, 1).outcome()).isEqualTo(PartOutcome.SUCCEEDED);
    assertThat(part(unknown, 2).outcome()).isEqualTo(PartOutcome.SUCCEEDED);
    assertThat(main.state(unknown.tenantId())).isEqualTo(TenantState.FAILED);

    SeedImportView unregistered =
        main.imported(
            SeedTests.demoBundleWith(
                "04-operations-actions-recovery.yaml", text -> text + "\nparsers: []\n"));
    assertFailedPart(
        unregistered,
        4,
        "Part runner refused. 04-operations-actions-recovery.yaml: unknown section parsers");

    SeedImportView duplicate =
        main.imported(
            SeedTests.demoBundleWith(
                "05-routing-escalation.yaml",
                text -> text + "\nrouting_change_for_sl_a2_06: {}\n"));
    assertThat(duplicate.state()).isEqualTo(SeedImportState.FAILED);
    assertThat(part(duplicate, 11).outcome()).isEqualTo(PartOutcome.FAILED);
    assertThat(part(duplicate, 11).error())
        .startsWith("Part routing refused. 05-routing-escalation.yaml could not be read: ");
  }

  @Test
  @DisplayName(
      "DM-E82-R1 only into a new tenant; the first failed part fails the record and tenant")
  void onlyIntoNewTenantsAndFirstFailureFails() {
    SeedImportView ready = main.imported(SeedTests.demoBundle());
    SeedImportView failed =
        main.imported(SeedTests.demoBundleWith(SETTINGS, text -> text + "\nai_settings: {}\n"));
    for (TenantId tenant : List.of(ready.tenantId(), failed.tenantId())) {
      VigilError refused =
          catchThrowableOfType(
              VigilError.class, () -> main.seedImports().start(tenant, SeedTests.demoBundle()));
      assertThat(refused.code()).isEqualTo(VigilError.Code.REFUSED);
      assertThat(refused.message())
          .isEqualTo("A seed bundle can be imported only into a new tenant.");
      assertThat(refused.details()).isEqualTo(Map.of("refusal", "tenant_not_new"));
      assertThat(rows(main, "setup.seed_import", tenant)).isEqualTo(1);
    }
    VigilError unknown =
        catchThrowableOfType(
            VigilError.class,
            () -> main.seedImports().start(new TenantId(Ids.newId()), SeedTests.demoBundle()));
    assertThat(unknown.code()).isEqualTo(VigilError.Code.NOT_FOUND);

    SeedImportView ghost = main.imported(SeedTests.demoBundleWith(IDENTITY, ADD_GHOST));
    assertThat(part(ghost, 1).outcome()).isEqualTo(PartOutcome.SUCCEEDED);
    assertFailedPart(ghost, 2, "Part identity refused. users: no password for ghost");
    assertThat(part(ghost, 3).outcome()).isEqualTo(PartOutcome.NOT_STARTED);
    assertThat(main.state(ghost.tenantId())).isEqualTo(TenantState.FAILED);
    assertThat(rows(main, "connectors.connector_instance", ghost.tenantId())).isZero();
    assertThat(rows(main, "connectors.credential_reference", ghost.tenantId())).isZero();
  }

  @Test
  @DisplayName("DM-R-57 a part its module refuses fails the import with the module's problems")
  void moduleRefusals() {
    SeedImportView kind =
        main.imported(
            SeedTests.demoBundleWith(
                SETTINGS, SeedTests.replacing("kind: zabbix,", "kind: zabbix7,")));
    assertThat(kind.state()).isEqualTo(SeedImportState.FAILED);
    assertThat(part(kind, 3).outcome()).isEqualTo(PartOutcome.FAILED);
    assertThat(part(kind, 3).error())
        .startsWith("Part connectors refused. connector_instances: kind of");

    SeedImportView colour =
        main.imported(
            SeedTests.demoBundleWith(
                IDENTITY,
                SeedTests.replacing(
                    "{team_key: noc-l1,             name: \"NOC L1\"}",
                    "{team_key: noc-l1, name: \"NOC L1\", colour: red}")));
    assertFailedPart(colour, 2, "Part identity refused. teams[0]: unknown field colour");
    assertThat(rows(main, "identity.team", colour.tenantId())).isZero();

    SeedImportView noLogo =
        main.imported(
            SeedTests.demoBundleWith(
                IDENTITY, SeedTests.replacing("customer_logo: null", "customer_logo: logo.png")));
    assertFailedPart(
        noLogo,
        1,
        "Part tenant settings refused. tenant_settings.customer_logo: no entry logo.png");

    List<Map.Entry<String, byte[]>> entries =
        new ArrayList<>(
            SeedTests.changed(
                SeedTests.demoEntries(),
                IDENTITY,
                SeedTests.replacing("customer_logo: null", "customer_logo: logo.png")));
    entries.add(Map.entry("logo.png", PIXEL_PNG));
    SeedImportView logo = main.imported(SeedBundles.zip(entries));
    assertThat(logo.state()).isEqualTo(SeedImportState.SUCCEEDED);
    TenantLogo stored = main.tenants().logo(logo.tenantId()).orElseThrow();
    assertThat(stored.bytes()).isEqualTo(PIXEL_PNG);
    assertThat(stored.mediaType()).isEqualTo("image/png");
  }

  @Test
  @DisplayName("DM-R-60 a key check with problems fails its referring part and the import")
  void keyCheckFails() {
    SeedImportView view = failingCheck.imported(SeedTests.demoBundle());
    assertFailedPart(view, 3, "Key check failed: x is unknown");
    assertThat(failingCheck.state(view.tenantId())).isEqualTo(TenantState.FAILED);
  }

  @Test
  @DisplayName("PM-M22-04 a key check whose target part did not succeed is skipped")
  void keyCheckSkipped() {
    SeedImportView view = main.imported(SeedTests.demoBundle());
    assertThat(view.state()).isEqualTo(SeedImportState.SUCCEEDED);
    assertThat(RECORDING_CHECK.calls).doesNotContain(view.tenantId()).isEmpty();
  }

  @Test
  @DisplayName("AR-R-31 each accepted import runs on its own virtual thread")
  void virtualThread() throws InterruptedException {
    TenantId tenant = latch.tenant();
    CountDownLatch release = LATCH.hold(tenant);
    SeedImportView started = latch.seedImports().start(tenant, SeedTests.demoBundle());
    assertThat(release.getCount()).as("start returned before the release").isEqualTo(1);
    LATCH.awaitEntered(tenant);
    Thread worker = LATCH.threads.get(tenant);
    assertThat(worker.isVirtual()).isTrue();
    assertThat(worker.getName()).isEqualTo("seed-import-" + started.id());
    release.countDown();
    assertThat(latch.await(started).state()).isEqualTo(SeedImportState.SUCCEEDED);
  }

  private static final String SERVICE_MAP = "02-service-map.yaml";

  @Test
  @DisplayName("DD-F-02 the service map file is imported as seed part 5, generation 1")
  void serviceMapPart() {
    SeedImportView view = main.imported(SeedTests.demoBundle());
    assertThat(view.state()).isEqualTo(SeedImportState.SUCCEEDED);
    GenerationView generation =
        main.app()
            .context()
            .getBean(ServiceMapService.class)
            .currentGeneration(view.tenantId())
            .orElseThrow();
    assertThat(generation.generation()).isEqualTo(1);
    assertThat(generation.fileName()).isEqualTo(SERVICE_MAP);
    assertThat(generation.importedBy()).isEqualTo(Actor.module("vigil-setup"));
    assertThat(generation.counts()).isEqualTo(new Counts(4, 12, 15, 6));
    List<AuditEventView> events = events(main, view.tenantId());
    assertThat(events).hasSize(1);
    assertThat(events.getFirst().detailsJson()).doesNotContain("\"area\":\"service_map\"");
    assertThat(SERVICE_MAP_EVENTS).noneMatch(event -> event.tenantId().equals(view.tenantId()));
  }

  @Test
  @DisplayName("AR-B-11 the service map part is refused with the module's problems")
  void serviceMapPartRefused() {
    SeedImportView view =
        main.imported(
            SeedTests.demoBundleWith(
                SERVICE_MAP,
                text -> {
                  String line =
                      List.of(text.split("\n")).stream()
                          .filter(l -> l.startsWith("  - {component_key: srv-db-01,"))
                          .findFirst()
                          .orElseThrow();
                  return SeedTests.replacing(line, line + "\n" + line).apply(text);
                }));
    assertFailedPart(
        view, 5, "Part service map refused. components: key 'srv-db-01' appears more than once.");
    for (int number = 1; number <= 3; number++) {
      assertThat(part(view, number).outcome()).isEqualTo(PartOutcome.SUCCEEDED);
    }
    assertThat(main.state(view.tenantId())).isEqualTo(TenantState.FAILED);
  }

  @Test
  @DisplayName("DM-R-60 an owner team of the service map that part 2 does not define fails part 5")
  void serviceMapTeamCheck() {
    SeedImportView view =
        main.imported(
            SeedTests.demoBundleWith(
                SERVICE_MAP,
                SeedTests.replacing(
                    "\"Charging API server\",    kind: server, criticality: high,     owner_team_key:"
                        + " it-infra}",
                    "\"Charging API server\",    kind: server, criticality: high,     owner_team_key:"
                        + " ghost-team}")));
    assertFailedPart(
        view, 5, "Key check failed: components 'srv-api-01': team 'ghost-team' does not exist");
    assertThat(main.state(view.tenantId())).isEqualTo(TenantState.FAILED);
  }

  @Test
  @DisplayName("BP-R-29 part 5 is registered; its check against part 4 waits for P-18")
  void serviceMapTargetCheckWaits() {
    SeedImportView view = main.imported(SeedTests.demoBundle());
    assertThat(part(view, 5).outcome()).isEqualTo(PartOutcome.SUCCEEDED);
    assertThat(part(view, 4).outcome()).isEqualTo(PartOutcome.NOT_STARTED);
    assertThat(main.app().context().getBeansOfType(SeedKeyCheck.class).values())
        .noneMatch(check -> check.referringPart() == 5 && check.targetPart() == 4);
  }
}
