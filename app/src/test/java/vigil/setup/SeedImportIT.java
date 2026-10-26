package vigil.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
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
import vigil.correlation.CorrelationFixture;
import vigil.correlation.api.CorrelationService;
import vigil.correlation.api.CorrelationSettingsView;
import vigil.diagnostics.api.DiagnosticDefinitionView;
import vigil.diagnostics.api.DiagnosticsService;
import vigil.identity.api.IdentityService;
import vigil.incidents.api.AffectedServiceCopy;
import vigil.incidents.api.AlertCopy;
import vigil.incidents.api.FinalReason;
import vigil.incidents.api.GroupingReason;
import vigil.incidents.api.HypothesisCopy;
import vigil.incidents.api.IdentityPairCopy;
import vigil.incidents.api.IncidentAcknowledged;
import vigil.incidents.api.IncidentChanged;
import vigil.incidents.api.IncidentCreated;
import vigil.incidents.api.IncidentService;
import vigil.incidents.api.IncidentSettingsView;
import vigil.incidents.api.IncidentStateChanged;
import vigil.incidents.api.IncidentView;
import vigil.incidents.api.MembershipView;
import vigil.incidents.api.Severity;
import vigil.intake.api.AlertReceived;
import vigil.intake.api.AlertStateChanged;
import vigil.intake.api.AlertTypeView;
import vigil.intake.api.IntakeService;
import vigil.intake.api.SourceHealthChanged;
import vigil.intake.api.SourceView;
import vigil.routing.RoutingFixture;
import vigil.routing.api.EscalationLevel;
import vigil.routing.api.EvaluationView;
import vigil.routing.api.NotAppliedReason;
import vigil.routing.api.RequestedBy;
import vigil.routing.api.RoutingResult;
import vigil.routing.api.RoutingRule;
import vigil.routing.api.RoutingService;
import vigil.routing.api.RuleSetView;
import vigil.runner.api.GrantView;
import vigil.runner.api.OperationView;
import vigil.runner.api.PermissionKind;
import vigil.runner.api.RunnerService;
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
import vigil.shared.Key;
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
import vigil.workflows.api.WorkflowService;
import vigil.workflows.api.WorkflowVersionView;
import vigil.workflows.api.WorkflowView;

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
  private static final List<Object> INTAKE_EVENTS = new CopyOnWriteArrayList<>();
  private static final List<Object> INCIDENT_EVENTS = new CopyOnWriteArrayList<>();
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

  /** The key check of the main app: referring part 3, target part 7; records its calls. */
  static final class RecordingCheck implements SeedKeyCheck {

    final List<TenantId> calls = new CopyOnWriteArrayList<>();

    @Override
    public int referringPart() {
      return 3;
    }

    @Override
    public int targetPart() {
      return 7;
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

  /**
   * The latch importer: part 17, which no package has registered yet; a held tenant waits until its
   * latch is released.
   */
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
      assertThat(entered.get(tenantId).await(60, TimeUnit.SECONDS)).as("part 17 reached").isTrue();
    }

    @Override
    public int part() {
      return 17;
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
   * Records every {@code ServiceMapImported}, intake and incident event the main app publishes, in
   * or out of a transaction.
   */
  static class ServiceMapListener {

    @EventListener
    void on(ServiceMapImported event) {
      SERVICE_MAP_EVENTS.add(event);
    }

    @EventListener
    void on(AlertReceived event) {
      INTAKE_EVENTS.add(event);
    }

    @EventListener
    void on(AlertStateChanged event) {
      INTAKE_EVENTS.add(event);
    }

    @EventListener
    void on(SourceHealthChanged event) {
      INTAKE_EVENTS.add(event);
    }

    @EventListener
    void on(IncidentCreated event) {
      INCIDENT_EVENTS.add(event);
    }

    @EventListener
    void on(IncidentAcknowledged event) {
      INCIDENT_EVENTS.add(event);
    }

    @EventListener
    void on(IncidentStateChanged event) {
      INCIDENT_EVENTS.add(event);
    }

    @EventListener
    void on(IncidentChanged event) {
      INCIDENT_EVENTS.add(event);
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
          .isEqualTo(
              number <= 6 || (number >= 9 && number <= 12) || number == 16
                  ? PartOutcome.SUCCEEDED
                  : PartOutcome.NOT_STARTED);
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
    assertThat(part(view, 17).outcome()).isEqualTo(PartOutcome.SUCCEEDED);
    assertThat(part(view, 17).counts()).isEmpty();
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
    assertThat(part(view, 4).counts())
        .isEqualTo(Map.of("operations", 5, "target_access_grants", 2));
    assertThat(part(view, 12).counts()).isEqualTo(Map.of("diagnostic_definitions", 3));
    assertThat(part(view, 5).counts())
        .isEqualTo(Map.of("services", 4, "components", 12, "identity_keys", 15, "dependencies", 6));
    assertThat(part(view, 6).counts())
        .isEqualTo(Map.of("monitoring_sources", 2, "alert_types", 18, "alert_type_mappings", 9));
    assertThat(part(view, 9).counts()).isEqualTo(Map.of("incident_settings", 1));
    assertThat(part(view, 10).counts()).isEqualTo(Map.of("correlation_settings", 1));
    assertThat(part(view, 11).counts()).isEqualTo(Map.of("rules", 3, "escalation_levels", 2));
    assertThat(part(view, 16).counts()).isEqualTo(Map.of("workflows", 1, "workflow_versions", 1));
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
                SETTINGS, SeedTests.section("task_assignments", "task_assignments: 3")));
    assertThat(view.state()).isEqualTo(SeedImportState.SUCCEEDED);
    assertThat(part(view, 7).outcome()).isEqualTo(PartOutcome.NOT_STARTED);
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
    assertThat(part(unreadable, 4).outcome()).isEqualTo(PartOutcome.SUCCEEDED);
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

  private static final String MONITORING = "03-monitoring.yaml";

  @Test
  @DisplayName("DD-F-03 03-monitoring.yaml is imported as seed part 6, with its first tests")
  void monitoringPart() {
    SeedImportView view = main.imported(SeedTests.demoBundle());
    assertThat(view.state()).isEqualTo(SeedImportState.SUCCEEDED);
    IntakeService intake = main.app().context().getBean(IntakeService.class);
    List<SourceView> sources = intake.sources(view.tenantId());
    assertThat(sources.stream().map(s -> s.sourceKey() + " " + s.sourceKind().key()))
        .containsExactly("alertmanager-lab alertmanager", "zabbix-lab zabbix");
    assertThat(sources)
        .allSatisfy(
            source -> {
              assertThat(source.healthCheckInterval()).isEqualTo(Duration.ofSeconds(30));
              assertThat(source.staleAfter()).isEqualTo(Duration.ofSeconds(120));
              assertThat(source.disconnectedAfter()).isEqualTo(Duration.ofSeconds(300));
            });
    List<AlertTypeView> types = intake.alertTypes(view.tenantId());
    assertThat(types).hasSize(19);
    long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    while (intake.sources(view.tenantId()).stream().anyMatch(s -> s.lastTestAt() == null)) {
      assertThat(System.nanoTime()).as("first tests within 15 s").isLessThan(deadline);
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }
    assertThat(intake.sources(view.tenantId()))
        .allSatisfy(
            source -> {
              assertThat(source.lastTestOutcome().key()).isEqualTo("failed");
              assertThat(source.health().key()).isEqualTo("disconnected");
            });
    assertThat(INTAKE_EVENTS)
        .noneMatch(
            event ->
                event instanceof SourceHealthChanged changed
                    && changed.tenantId().equals(view.tenantId()));
  }

  @Test
  @DisplayName("AR-B-11 the intake part is refused with the module's problems")
  void monitoringPartRefused() {
    String line =
        "cred-webhook-zabbix,       health_check_interval_s: 30, stale_after_s: 120,"
            + " disconnected_after_s: 300";
    SeedImportView view =
        main.imported(
            SeedTests.demoBundleWith(
                MONITORING,
                SeedTests.replacing(
                    line, line.replace("disconnected_after_s: 300", "disconnected_after_s: 100"))));
    assertFailedPart(
        view,
        6,
        "Part intake refused. monitoring_sources: disconnected_after_s of zabbix-lab: must be"
            + " greater than stale_after_s");
    for (int number : List.of(1, 2, 3, 5)) {
      assertThat(part(view, number).outcome()).isEqualTo(PartOutcome.SUCCEEDED);
    }
    assertThat(main.state(view.tenantId())).isEqualTo(TenantState.FAILED);
  }

  @Test
  @DisplayName("DD-F-07 incident_settings of 07-settings.yaml is imported as seed part 9")
  void incidentsPart() {
    SeedImportView view = main.imported(SeedTests.demoBundle());
    assertThat(view.state()).isEqualTo(SeedImportState.SUCCEEDED);
    IncidentService incidents = main.app().context().getBean(IncidentService.class);
    assertThat(incidents.settings(view.tenantId()))
        .contains(new IncidentSettingsView(Duration.ofSeconds(300), Duration.ofSeconds(30)));
    assertThat(INCIDENT_EVENTS)
        .noneMatch(
            event ->
                switch (event) {
                  case IncidentCreated e -> e.tenantId().equals(view.tenantId());
                  case IncidentAcknowledged e -> e.tenantId().equals(view.tenantId());
                  case IncidentStateChanged e -> e.tenantId().equals(view.tenantId());
                  case IncidentChanged e -> e.tenantId().equals(view.tenantId());
                  default -> false;
                });
  }

  @Test
  @DisplayName("AR-B-11 the incidents part is refused with the module's problems")
  void incidentsPartRefused() {
    SeedImportView view =
        main.imported(
            SeedTests.demoBundleWith(
                SETTINGS,
                SeedTests.replacing(
                    "closure_check_interval_s: 30", "closure_check_interval_s: 90")));
    assertFailedPart(
        view, 9, "Part incidents refused. incident_settings: closure_check_interval_s: 1 to 60");
    for (int number : List.of(1, 2, 3, 5, 6)) {
      assertThat(part(view, number).outcome()).isEqualTo(PartOutcome.SUCCEEDED);
    }
    assertThat(main.state(view.tenantId())).isEqualTo(TenantState.FAILED);
  }

  @Test
  @DisplayName("BP-R-29 part 9 is registered; parts 7, 8, 13 to 15 and 17 are not started")
  void incidentsPartRegistered() {
    SeedImportView view = main.imported(SeedTests.demoBundle());
    assertThat(part(view, 9).outcome()).isEqualTo(PartOutcome.SUCCEEDED);
    for (int number : List.of(7, 8, 13, 14, 15, 17)) {
      assertThat(part(view, number).outcome())
          .as("part %s", number)
          .isEqualTo(PartOutcome.NOT_STARTED);
    }
  }

  private static final String ROUTING = "05-routing-escalation.yaml";
  private static final String LEVEL_2 = "- {level: 2, team_keys: [noc-management]}";
  private static final String HIGH_CONDITION =
      "{field: highest_service_criticality, operator: at_least, value: high}";
  private static final Actor CORRELATION = Actor.module("vigil-correlation");

  @Test
  @DisplayName("DD-F-05 05-routing-escalation.yaml is imported as seed part 11")
  void routingPart() {
    SeedImportView view = main.imported(SeedTests.demoBundle());
    assertThat(view.state()).isEqualTo(SeedImportState.SUCCEEDED);
    RoutingService routing = main.app().context().getBean(RoutingService.class);
    RuleSetView ruleSet = routing.ruleSet(view.tenantId()).orElseThrow();
    assertThat(ruleSet.revision()).isEqualTo(1);
    assertThat(ruleSet.rules()).isEqualTo(RoutingFixture.demoRules());
    assertThat(routing.escalationPolicy(view.tenantId()).orElseThrow().levels())
        .containsExactly(
            new EscalationLevel(1, List.of("noc-shift-lead")),
            new EscalationLevel(2, List.of("noc-management")));
    List<AuditEventView> events = events(main, view.tenantId());
    assertThat(events).hasSize(1);
    assertThat(events.getFirst().actor()).isEqualTo(Actor.module("vigil-setup"));
  }

  /** An incident from vigil-correlation with the hypothesis and services given. */
  private static UUID incident(
      TenantId tenant, HypothesisCopy hypothesis, List<AffectedServiceCopy> services) {
    IncidentService incidents = main.app().context().getBean(IncidentService.class);
    String component = hypothesis.componentKey();
    UUID id =
        incidents.create(
            tenant,
            CORRELATION,
            Ids.newId(),
            new AlertCopy(
                Ids.newId(),
                "zabbix",
                "k-" + UUID.randomUUID(),
                "disk_full",
                "Disk full",
                "Disk full on " + component,
                component,
                new IdentityPairCopy("component", component),
                Instant.now()),
            new GroupingReason("created_incident", null, null, null, null));
    incidents.setHypothesisAndImpact(tenant, CORRELATION, id, hypothesis, Ids.newId(), services);
    return id;
  }

  private static UUID storm(TenantId tenant) {
    return incident(
        tenant,
        new HypothesisCopy(1, "srv-db-01", "Subscriber DB server", "server", "critical"),
        List.of(
            new AffectedServiceCopy(
                "svc-balance-check", "Balance Check (USSD)", "critical", List.of("db-subscriber")),
            new AffectedServiceCopy(
                "svc-bundle-purchase", "Bundle Purchase", "critical", List.of("db-subscriber")),
            new AffectedServiceCopy(
                "svc-sms-notifications", "SMS Notifications", "high", List.of("sms-queue"))));
  }

  private static void result(
      EvaluationView view, String rule, Severity severity, String owner, String... informed) {
    assertThat(view.matchedRuleKey()).isEqualTo(rule);
    assertThat(view.result()).isEqualTo(new RoutingResult(severity, owner, List.of(informed)));
  }

  @Test
  @DisplayName("DD-K-05 the routing expectations of the demo data, before and after SL-A2-06")
  void routingExpectations() {
    SeedImportView view = main.imported(SeedTests.demoBundle());
    TenantId tenant = view.tenantId();
    RoutingService routing = main.app().context().getBean(RoutingService.class);
    IncidentService incidents = main.app().context().getBean(IncidentService.class);
    UUID storm = storm(tenant);
    result(
        routing.evaluate(tenant, CORRELATION, storm, RequestedBy.CORRELATION),
        "critical-service",
        Severity.P1,
        "db-ops",
        "vas-service-owners");
    UUID report =
        incident(
            tenant,
            new HypothesisCopy(1, "report-export", "Report export job", "application", "low"),
            List.of(
                new AffectedServiceCopy(
                    "svc-mgmt-reports", "Management Reports", null, List.of("report-export"))));
    result(
        routing.evaluate(tenant, CORRELATION, report, RequestedBy.CORRELATION),
        "default",
        Severity.P4,
        "it-apps");

    Map<UUID, FinalReason> reasons = new LinkedHashMap<>();
    for (MembershipView member : incidents.members(tenant, storm)) {
      reasons.put(member.alertId(), new FinalReason(null, FinalReason.ONLY_ALERT, null, null));
    }
    incidents.open(tenant, CORRELATION, storm, reasons);
    IncidentView opened = incidents.incident(tenant, storm).orElseThrow();
    List<RoutingRule> rules = new ArrayList<>(routing.ruleSet(tenant).orElseThrow().rules());
    RoutingRule critical = rules.getFirst();
    rules.set(
        0,
        new RoutingRule(
            critical.ruleKey(),
            critical.name(),
            critical.conditions(),
            critical.severity(),
            critical.ownerMode(),
            critical.ownerTeamKey(),
            List.of("customer-care"),
            critical.informAffectedServiceOwners()));
    UUID admin =
        main.app()
            .context()
            .getBean(IdentityService.class)
            .userByUsername(tenant, new Key("admin"))
            .orElseThrow()
            .id();
    assertThat(routing.saveRuleSet(tenant, admin, 1, rules).revision()).isEqualTo(2);
    result(
        routing.evaluate(tenant, CORRELATION, storm(tenant), RequestedBy.CORRELATION),
        "critical-service",
        Severity.P1,
        "db-ops",
        "customer-care",
        "vas-service-owners");
    EvaluationView frozen =
        routing.evaluate(tenant, Actor.module("vigil-workflows"), storm, RequestedBy.WORKFLOWS);
    assertThat(frozen.ruleSetRevision()).isEqualTo(2);
    result(
        frozen, "critical-service", Severity.P1, "db-ops", "customer-care", "vas-service-owners");
    assertThat(frozen.applied()).isFalse();
    assertThat(frozen.notAppliedReason()).isEqualTo(NotAppliedReason.ASSIGNMENT_FROZEN);
    IncidentView after = incidents.incident(tenant, storm).orElseThrow();
    assertThat(after.severity()).isEqualTo(Severity.P1);
    assertThat(after.ownerTeamKey()).isEqualTo("db-ops");
    assertThat(after.informedTeamKeys()).containsExactly("vas-service-owners");
    assertThat(after.assignmentEvaluationId()).isEqualTo(opened.assignmentEvaluationId());
  }

  @Test
  @DisplayName("AR-B-11 the routing part is refused with the module's and the adapter's problems")
  void routingPartRefused() {
    SeedImportView view =
        main.imported(
            SeedTests.demoBundleWith(
                ROUTING,
                SeedTests.replacing(
                    "owner_team_key: noc-l1                   # used only",
                    "owner_team_key: noc-l9                   # used only")));
    assertFailedPart(
        view,
        11,
        "Part routing refused. routing_rule_set_revision_1: Rule 'Critical service affected':"
            + " 'noc-l9' is not an active team.");
    for (int number : List.of(1, 2, 3, 5, 6, 9, 10)) {
      assertThat(part(view, number).outcome()).isEqualTo(PartOutcome.SUCCEEDED);
    }
    assertThat(main.state(view.tenantId())).isEqualTo(TenantState.FAILED);
    SeedImportView shape =
        main.imported(
            SeedTests.demoBundleWith(
                ROUTING, SeedTests.replacing(LEVEL_2, "- {level: 2, team_keys: noc-management}")));
    assertFailedPart(
        shape,
        11,
        "Part routing refused. escalation_policy_revision_1.levels[1].team_keys: not a list");
    assertThat(main.state(shape.tenantId())).isEqualTo(TenantState.FAILED);
  }

  @Test
  @DisplayName("DM-R-60 an escalation team that part 2 does not define fails part 11")
  void routingTeamCheck() {
    SeedImportView view =
        main.imported(
            SeedTests.demoBundleWith(
                ROUTING, SeedTests.replacing(LEVEL_2, "- {level: 2, team_keys: [ghost-team]}")));
    assertFailedPart(
        view, 11, "Key check failed: escalation level 2: team 'ghost-team' does not exist");
    assertThat(main.state(view.tenantId())).isEqualTo(TenantState.FAILED);
  }

  @Test
  @DisplayName("DM-R-60 a service compared in a routing rule that part 5 does not define fails")
  void routingServiceCheck() {
    SeedImportView view =
        main.imported(
            SeedTests.demoBundleWith(
                ROUTING,
                SeedTests.replacing(
                    HIGH_CONDITION,
                    "{field: affected_services, operator: contains, value: svc-ghost}")));
    assertFailedPart(
        view,
        11,
        "Key check failed: routing rule 'high-service': service 'svc-ghost' does not exist");
    assertThat(main.state(view.tenantId())).isEqualTo(TenantState.FAILED);
  }

  @Test
  @DisplayName("DM-R-60 a component compared in a routing rule that part 5 does not define fails")
  void routingComponentCheck() {
    SeedImportView view =
        main.imported(
            SeedTests.demoBundleWith(
                ROUTING,
                SeedTests.replacing(
                    HIGH_CONDITION,
                    "{field: hypothesis_component, operator: equals, value: srv-ghost}")));
    assertFailedPart(
        view,
        11,
        "Key check failed: routing rule 'high-service': component 'srv-ghost' does not exist");
    assertThat(main.state(view.tenantId())).isEqualTo(TenantState.FAILED);
  }

  @Test
  @DisplayName("BP-R-29 part 11 is registered, with its key checks against parts 2 and 5")
  void routingPartRegistered() {
    SeedImportView view = main.imported(SeedTests.demoBundle());
    assertThat(part(view, 11).outcome()).isEqualTo(PartOutcome.SUCCEEDED);
    assertThat(main.app().context().getBeansOfType(SeedKeyCheck.class).values())
        .anyMatch(check -> check.referringPart() == 11 && check.targetPart() == 2)
        .anyMatch(check -> check.referringPart() == 11 && check.targetPart() == 5);
  }

  @Test
  @DisplayName("DD-F-07 correlation_settings of 07-settings.yaml is imported as seed part 10")
  void correlationPart() {
    SeedImportView view = main.imported(SeedTests.demoBundle());
    assertThat(view.state()).isEqualTo(SeedImportState.SUCCEEDED);
    CorrelationService correlation = main.app().context().getBean(CorrelationService.class);
    assertThat(correlation.settings(view.tenantId()))
        .contains(
            new CorrelationSettingsView(
                Duration.ofSeconds(300),
                Duration.ofSeconds(40),
                Duration.ofSeconds(300),
                Duration.ofSeconds(5)));
    assertThat(CorrelationFixture.isRunning(main.app(), view.tenantId())).isFalse();
  }

  @Test
  @DisplayName("AR-B-11 the correlation part is refused with the module's problems")
  void correlationPartRefused() {
    SeedImportView view =
        main.imported(
            SeedTests.demoBundleWith(
                SETTINGS,
                SeedTests.replacing(
                    "stability_check_interval_s: 5", "stability_check_interval_s: 6")));
    assertFailedPart(
        view,
        10,
        "Part correlation refused. correlation_settings: stability_check_interval_s: 1 to 5");
    for (int number : List.of(1, 2, 3, 5, 6, 9)) {
      assertThat(part(view, number).outcome()).isEqualTo(PartOutcome.SUCCEEDED);
    }
    assertThat(main.state(view.tenantId())).isEqualTo(TenantState.FAILED);
  }

  @Test
  @DisplayName("BP-R-29 part 10 is registered")
  void correlationPartRegistered() {
    SeedImportView view = main.imported(SeedTests.demoBundle());
    assertThat(part(view, 10).outcome()).isEqualTo(PartOutcome.SUCCEEDED);
  }

  private static final String WORKFLOW = "06-workflow.yaml";
  private static final String APPROVE =
      "label: \"DB Ops approval\",            position: {x: 1980, y: 100},"
          + " parameters: {eligible_teams: [db-ops], expiry_s: 900}}";

  @Test
  @DisplayName("DD-F-06 06-workflow.yaml is imported as seed part 16")
  void workflowPart() {
    SeedImportView view = main.imported(SeedTests.demoBundle());
    assertThat(view.state()).isEqualTo(SeedImportState.SUCCEEDED);
    WorkflowService workflows = main.app().context().getBean(WorkflowService.class);
    WorkflowView workflow = workflows.workflow(view.tenantId()).orElseThrow();
    assertThat(workflow.name()).isEqualTo("Incident handling");
    WorkflowVersionView version = workflows.activeVersion(view.tenantId()).orElseThrow();
    assertThat(version.id()).isEqualTo(workflow.activeVersionId());
    assertThat(version.number()).isEqualTo(1);
    assertThat(JSON.readTree(version.contentJson()).get("steps")).hasSize(19);
    assertThat(events(main, view.tenantId()))
        .noneMatch(event -> event.actor().equals(Actor.module("vigil-workflows")));
  }

  @Test
  @DisplayName("AR-B-11 the workflows part is refused with the module's problems")
  void workflowPartRefused() {
    SeedImportView view =
        main.imported(
            SeedTests.demoBundleWith(
                WORKFLOW,
                SeedTests.replacing(
                    APPROVE, APPROVE.replace("expiry_s: 900}}", "expiry_s: 30}}"))));
    assertFailedPart(
        view,
        16,
        "Part workflows refused. workflow.version_1.steps[9].parameters.expiry_s:"
            + " 60 to 86400 seconds");
    for (int number : List.of(1, 2, 3, 4, 5, 6, 9, 10, 11, 12)) {
      assertThat(part(view, number).outcome()).isEqualTo(PartOutcome.SUCCEEDED);
    }
    assertThat(main.state(view.tenantId())).isEqualTo(TenantState.FAILED);
  }

  @Test
  @DisplayName("DM-R-60 a team of a workflow step that part 2 does not define fails part 16")
  void workflowTeamCheck() {
    SeedImportView view =
        main.imported(
            SeedTests.demoBundleWith(
                WORKFLOW, SeedTests.replacing("named_teams: [noc-l1]", "named_teams: [noc-l9]")));
    assertFailedPart(
        view, 16, "Key check failed: workflow step 's-notify': team 'noc-l9' does not exist");
    assertThat(main.state(view.tenantId())).isEqualTo(TenantState.FAILED);
  }

  @Test
  @DisplayName("DM-R-60 a component compared in a workflow step that part 5 does not define fails")
  void workflowComponentCheck() {
    SeedImportView view =
        main.imported(
            SeedTests.demoBundleWith(
                WORKFLOW,
                SeedTests.replacing(
                    "{label: p1, comparisons: [{field: severity, operator: equals, value: P1}]}",
                    "{label: p1, comparisons: [{field: hypothesis_component, operator: equals,"
                        + " value: srv-nowhere}]}")));
    assertFailedPart(
        view,
        16,
        "Key check failed: workflow step 's-severity': component 'srv-nowhere' does not exist");
    assertThat(main.state(view.tenantId())).isEqualTo(TenantState.FAILED);
  }

  @Test
  @DisplayName("BP-R-29 part 16 is registered, with its key checks against parts 2, 5 and 12")
  void workflowPartRegistered() {
    SeedImportView view = main.imported(SeedTests.demoBundle());
    assertThat(part(view, 16).outcome()).isEqualTo(PartOutcome.SUCCEEDED);
    assertThat(main.app().context().getBeansOfType(SeedKeyCheck.class).values())
        .anyMatch(check -> check.referringPart() == 16 && check.targetPart() == 2)
        .anyMatch(check -> check.referringPart() == 16 && check.targetPart() == 5)
        .anyMatch(check -> check.referringPart() == 16 && check.targetPart() == 12);
  }

  private static final String OPERATIONS = "04-operations-actions-recovery.yaml";

  @Test
  @DisplayName("DD-F-04 seed parts 4 and 12 are imported from 04-operations-actions-recovery.yaml")
  void operationsParts() {
    SeedImportView view = main.imported(SeedTests.demoBundle());
    assertThat(view.state()).isEqualTo(SeedImportState.SUCCEEDED);
    TenantId tenant = view.tenantId();
    RunnerService runner = main.app().context().getBean(RunnerService.class);
    for (String key : List.of("fs_usage", "largest_dirs", "recent_growth", "fs_used_pct")) {
      OperationView operation = runner.operation(tenant, key).orElseThrow();
      assertThat(operation.active()).isTrue();
      assertThat(operation.permissionKind()).isEqualTo(PermissionKind.DIAGNOSTIC);
    }
    OperationView purge = runner.operation(tenant, "purge_old_archives").orElseThrow();
    assertThat(purge.active()).isTrue();
    assertThat(purge.permissionKind()).isEqualTo(PermissionKind.ACTION);
    UUID instance =
        main.postgres()
            .sql(
                "SELECT id FROM connectors.connector_instance"
                    + " WHERE tenant_id = :t AND name = 'Subscriber DB server (lab)'")
            .param("t", tenant.value())
            .query(UUID.class)
            .single();
    List<GrantView> grants = runner.grants(tenant);
    assertThat(grants)
        .extracting(GrantView::targetKey, GrantView::permissionKind, GrantView::connectorInstanceId)
        .containsExactly(
            tuple("lab-db-01", PermissionKind.ACTION, instance),
            tuple("lab-db-01", PermissionKind.DIAGNOSTIC, instance));
    assertThat(grants.get(0).credentialRefId()).isEqualTo(credential(tenant, "lab-db-01-action"));
    assertThat(grants.get(1).credentialRefId()).isEqualTo(credential(tenant, "lab-db-01-diag"));
    assertThat(
            main.app().context().getBean(DiagnosticsService.class).definitions(tenant).stream()
                .map(DiagnosticDefinitionView::diagnosticKey))
        .containsExactly("disk_growth", "disk_usage", "largest_folders");
    assertThat(events(main, tenant))
        .noneMatch(
            event ->
                event.actor().equals(Actor.module("vigil-runner"))
                    || event.actor().equals(Actor.module("vigil-diagnostics")));
  }

  private UUID credential(TenantId tenant, String secret) {
    return main.postgres()
        .sql(
            "SELECT id FROM connectors.credential_reference"
                + " WHERE tenant_id = :t AND locator = :locator")
        .param("t", tenant.value())
        .param("locator", "secret:" + secret)
        .query(UUID.class)
        .single();
  }

  @Test
  @DisplayName("AR-B-11 the runner part is refused with the module's problems")
  void runnerPartRefused() {
    SeedImportView view =
        main.imported(
            SeedTests.demoBundleWith(
                OPERATIONS, SeedTests.replacing("    timeout_s: 120", "    timeout_s: 0")));
    assertFailedPart(view, 4, "Part runner refused. operations[4].timeout_s: 1 to 3600 seconds");
    for (int number = 1; number <= 3; number++) {
      assertThat(part(view, number).outcome()).isEqualTo(PartOutcome.SUCCEEDED);
    }
    assertThat(part(view, 5).outcome()).isEqualTo(PartOutcome.NOT_STARTED);
  }

  @Test
  @DisplayName("AR-B-11 the diagnostics part is refused with the module's problems")
  void diagnosticsPartRefused() {
    SeedImportView view =
        main.imported(
            SeedTests.demoBundleWith(
                OPERATIONS,
                SeedTests.replacing("    name: \"Disk usage of /data\"", "    name: \"\"")));
    assertFailedPart(
        view, 12, "Part diagnostics refused. diagnostic_definitions[0].name: 1 to 100 characters");
    for (int number : List.of(1, 2, 3, 4, 5, 6, 9, 10, 11)) {
      assertThat(part(view, number).outcome()).isEqualTo(PartOutcome.SUCCEEDED);
    }
  }

  @Test
  @DisplayName("DM-R-60 a target of the service map that part 4 does not grant fails part 5")
  void serviceMapTargetCheck() {
    SeedImportView view =
        main.imported(
            SeedTests.demoBundleWith(
                SERVICE_MAP,
                SeedTests.replacing("target_key: lab-db-01}", "target_key: lab-db-09}")));
    assertFailedPart(
        view, 5, "Key check failed: components 'srv-db-01': target 'lab-db-09' does not exist");
  }

  @Test
  @DisplayName("DM-R-60 an operation of a diagnostic definition that part 4 does not define fails")
  void diagnosticOperationMissing() {
    SeedImportView view =
        main.imported(
            SeedTests.demoBundleWith(
                OPERATIONS,
                SeedTests.replacing(
                    "    operation_key: fs_usage", "    operation_key: fs_nowhere")));
    assertFailedPart(
        view,
        12,
        "Key check failed: diagnostic definition 'disk_usage': operation 'fs_nowhere' does not"
            + " exist");
  }

  @Test
  @DisplayName("DM-R-60 a diagnostic definition on an action operation fails part 12")
  void diagnosticOperationAction() {
    SeedImportView view =
        main.imported(
            SeedTests.demoBundleWith(
                OPERATIONS,
                SeedTests.replacing(
                    "    operation_key: fs_usage", "    operation_key: purge_old_archives")));
    assertFailedPart(
        view,
        12,
        "Key check failed: diagnostic definition 'disk_usage': operation 'purge_old_archives'"
            + " is not a diagnostic operation");
  }

  @Test
  @DisplayName(
      "DM-R-60 parameter values of a diagnostic definition the runner refuses fail part 12")
  void diagnosticParameterValues() {
    SeedImportView view =
        main.imported(
            SeedTests.demoBundleWith(OPERATIONS, SeedTests.replacing("count: 4}", "count: 12}")));
    assertFailedPart(
        view,
        12,
        "Key check failed: diagnostic definition 'largest_folders': parameter count: 2 to 11");
  }

  @Test
  @DisplayName("DM-R-60 a diagnostic of a workflow step that part 12 does not define fails part 16")
  void workflowDiagnosticCheck() {
    SeedImportView view =
        main.imported(
            SeedTests.demoBundleWith(
                WORKFLOW,
                SeedTests.replacing(
                    "diagnostics: [disk_usage, largest_folders, disk_growth]",
                    "diagnostics: [disk_usage, largest_folders, disk_wear]")));
    assertFailedPart(
        view,
        16,
        "Key check failed: workflow step 's-diagnose': diagnostic 'disk_wear' does not exist");
  }

  @Test
  @DisplayName("BP-R-29 parts 4 and 12 are registered, with the three key checks")
  void operationsPartsRegistered() {
    SeedImportView view = main.imported(SeedTests.demoBundle());
    assertThat(part(view, 4).outcome()).isEqualTo(PartOutcome.SUCCEEDED);
    assertThat(part(view, 12).outcome()).isEqualTo(PartOutcome.SUCCEEDED);
    assertThat(main.app().context().getBeansOfType(SeedKeyCheck.class).values())
        .anyMatch(check -> check.referringPart() == 5 && check.targetPart() == 4)
        .anyMatch(check -> check.referringPart() == 12 && check.targetPart() == 4)
        .anyMatch(check -> check.referringPart() == 16 && check.targetPart() == 12);
  }
}
