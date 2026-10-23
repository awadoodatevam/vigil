package vigil.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vigil.shared.Actor;
import vigil.shared.Ids;
import vigil.shared.TenantId;
import vigil.shared.VigilError;
import vigil.tenancy.api.AliasRevision;
import vigil.tenancy.api.TenantAliasChanged;
import vigil.tenancy.api.TenantImport;
import vigil.tenancy.api.TenantLogo;
import vigil.tenancy.api.TenantService;
import vigil.tenancy.api.TenantSettingsPart;
import vigil.tenancy.api.TenantSettingsView;
import vigil.tenancy.api.TenantState;
import vigil.tenancy.api.TenantView;
import vigil.testsupport.MutableClock;
import vigil.testsupport.VigilDatabase;
import vigil.testsupport.VigilTestApp;

class TenancyIT {

  private static final MutableClock CLOCK = new MutableClock(Instant.parse("2026-10-16T08:00:00Z"));
  private static final TenantSettingsPart PART =
      new TenantSettingsPart("Jazz", "Demo environment", "Techfinity", null);
  private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G'};

  private static VigilDatabase database;
  private static VigilTestApp app;
  private static TenantService tenants;
  private static TenantImport tenantImport;
  private static JdbcClient appJdbc;
  private static TransactionTemplate tx;

  /** The test clock. */
  static class TestClock {

    @Bean
    @Primary
    Clock testClock() {
      return CLOCK;
    }
  }

  @BeforeAll
  static void start() {
    database = VigilDatabase.fresh();
    app = VigilTestApp.start(database, TestClock.class);
    app.awaitStarted();
    tenants = app.context().getBean(TenantService.class);
    tenantImport = app.context().getBean(TenantImport.class);
    appJdbc = app.context().getBean(JdbcClient.class);
    tx = new TransactionTemplate(app.context().getBean(PlatformTransactionManager.class));
  }

  @AfterAll
  static void stop() {
    app.close();
  }

  private static TenantId provisioning() {
    return tenants.createTenant("tenant " + Ids.newId(), Actor.module("vigil-setup"));
  }

  private static TenantId ready() {
    return ready(PART);
  }

  private static TenantId ready(TenantSettingsPart part) {
    TenantId tenant = provisioning();
    tenantImport.importPart(tenant, part, CLOCK.instant(), Map.of());
    tenants.markReady(tenant);
    return tenant;
  }

  private static String host() {
    return "h" + Ids.newId().toString().substring(24) + ".vigil.test";
  }

  @SuppressWarnings("unchecked")
  private static List<String> problems(Runnable call) {
    try {
      call.run();
    } catch (VigilError error) {
      assertThat(error.code()).isEqualTo(VigilError.Code.INVALID);
      assertThat(error.message()).isEqualTo("Part tenant settings refused.");
      return (List<String>) error.details().get("problems");
    }
    throw new AssertionError("the part was not refused");
  }

  private static VigilError.Code code(Runnable call) {
    try {
      call.run();
    } catch (VigilError error) {
      return error.code();
    }
    throw new AssertionError("no VigilError");
  }

  private static String message(Runnable call) {
    try {
      call.run();
    } catch (VigilError error) {
      return error.message();
    }
    throw new AssertionError("no VigilError");
  }

  @Test
  @DisplayName("PM-M01-01 a tenant has its own identifier")
  void tenantIdentifier() {
    TenantId first = provisioning();
    TenantId second = provisioning();
    assertThat(first).isNotEqualTo(second);
    assertThat(tenants.tenant(first)).map(TenantView::id).contains(first);
  }

  @Test
  @DisplayName(
      "PM-M01-03 createTenant makes a provisioning tenant with its name; 101 characters refused")
  void createTenant() {
    Instant now = CLOCK.instant();
    TenantId tenant = tenants.createTenant("jazz-demo 2026-10-16", Actor.module("vigil-setup"));
    TenantView view = tenants.tenant(tenant).orElseThrow();
    assertThat(view.name()).isEqualTo("jazz-demo 2026-10-16");
    assertThat(view.state()).isEqualTo(TenantState.PROVISIONING);
    assertThat(view.createdAt()).isEqualTo(now);
    assertThat(view.readyAt()).isNull();
    VigilError error =
        org.junit.jupiter.api.Assertions.assertThrows(
            VigilError.class,
            () -> tenants.createTenant("x".repeat(101), Actor.module("vigil-setup")));
    assertThat(error.code()).isEqualTo(VigilError.Code.BAD_REQUEST);
    assertThat(error.message()).isEqualTo("name: 1 to 100 characters");
    assertThat(code(() -> tenants.createTenant(null, Actor.module("vigil-setup"))))
        .isEqualTo(VigilError.Code.BAD_REQUEST);
    assertThat(code(() -> tenants.createTenant("", Actor.module("vigil-setup"))))
        .isEqualTo(VigilError.Code.BAD_REQUEST);
    tenants.createTenant("x".repeat(100), Actor.module("vigil-setup"));
  }

  @Test
  @DisplayName("DM-E-01 a tenant record: name, state, created_at, created_by, ready_at")
  void tenantRecord() {
    TenantId tenant = provisioning();
    Instant readyTime = CLOCK.advance(Duration.ofSeconds(1));
    tenantImport.importPart(tenant, PART, readyTime, Map.of());
    tenants.markReady(tenant);
    TenantView view = tenants.tenant(tenant).orElseThrow();
    assertThat(view.state()).isEqualTo(TenantState.READY);
    assertThat(view.readyAt()).isEqualTo(readyTime);
    assertThat(tenants.tenant(new TenantId(Ids.newId()))).isEmpty();
  }

  @Test
  @DisplayName(
      "DM-SM-01 provisioning to ready or failed; ready and failed are final; unknown NOT_FOUND")
  void tenantStateMachine() {
    TenantId readyTenant = ready();
    assertThat(tenants.tenant(readyTenant).orElseThrow().readyAt()).isNotNull();
    TenantId failed = provisioning();
    tenants.markFailed(failed);
    assertThat(tenants.tenant(failed).orElseThrow().state()).isEqualTo(TenantState.FAILED);
    for (TenantId tenant : List.of(readyTenant, failed)) {
      String state = tenants.tenant(tenant).orElseThrow().state().key();
      for (Runnable call :
          List.<Runnable>of(() -> tenants.markReady(tenant), () -> tenants.markFailed(tenant))) {
        assertThat(code(call)).isEqualTo(VigilError.Code.CONFLICT);
        assertThat(message(call)).isEqualTo("Tenant " + tenant.value() + " is " + state + ".");
      }
    }
    TenantId unknown = new TenantId(Ids.newId());
    assertThat(code(() -> tenants.markReady(unknown))).isEqualTo(VigilError.Code.NOT_FOUND);
    assertThat(code(() -> tenants.markFailed(unknown))).isEqualTo(VigilError.Code.NOT_FOUND);
  }

  @Test
  @DisplayName("DM-C-03 created_by (user and module forms) and the settings' updated_by are stored")
  void actors() {
    UUID user = Ids.newId();
    TenantId byUser = tenants.createTenant("by user", Actor.user(user));
    TenantId byModule = tenants.createTenant("by module", Actor.module("vigil-setup"));
    assertThat(tenants.tenant(byUser).orElseThrow().createdBy()).isEqualTo(Actor.user(user));
    assertThat(tenants.tenant(byModule).orElseThrow().createdBy())
        .isEqualTo(Actor.module("vigil-setup"));
    tenantImport.importPart(byModule, PART, CLOCK.instant(), Map.of());
    Map<String, Object> row =
        database
            .postgres()
            .sql(
                "SELECT updated_by_kind, updated_by_user_id, updated_by_module"
                    + " FROM tenancy.tenant_settings WHERE tenant_id = ?")
            .param(byModule.value())
            .query()
            .singleRow();
    assertThat(row.get("updated_by_kind")).isEqualTo("module");
    assertThat(row.get("updated_by_user_id")).isNull();
    assertThat(row.get("updated_by_module")).isEqualTo("vigil-setup");
  }

  @Test
  @DisplayName("PM-M01-02 settings and logo return what was imported")
  void settingsReturned() {
    TenantId withLogo =
        ready(new TenantSettingsPart("Jazz", "", "Techfinity", new TenantLogo(PNG, "image/png")));
    assertThat(tenants.settings(withLogo))
        .isEqualTo(new TenantSettingsView("Jazz", true, null, "Techfinity"));
    TenantLogo logo = tenants.logo(withLogo).orElseThrow();
    assertThat(logo.bytes()).isEqualTo(PNG);
    assertThat(logo.mediaType()).isEqualTo("image/png");
    TenantId withoutLogo = ready();
    assertThat(tenants.settings(withoutLogo))
        .isEqualTo(new TenantSettingsView("Jazz", false, "Demo environment", "Techfinity"));
    assertThat(tenants.logo(withoutLogo)).isEmpty();
  }

  @Test
  @DisplayName("DM-E-02 tenant settings: customer name, logo with media type, label, product name")
  void settingsRecord() {
    TenantId tenant =
        ready(
            new TenantSettingsPart(
                "Customer",
                null,
                "Product",
                new TenantLogo(new byte[] {'<', 's'}, "image/svg+xml")));
    assertThat(tenants.settings(tenant))
        .isEqualTo(new TenantSettingsView("Customer", true, null, "Product"));
    assertThat(tenants.logo(tenant).orElseThrow().mediaType()).isEqualTo("image/svg+xml");
    assertThat(code(() -> tenants.settings(provisioning()))).isEqualTo(VigilError.Code.NOT_FOUND);
  }

  @Test
  @DisplayName("DM-E02-R1 one settings record per tenant; ready only with settings")
  void oneSettingsRecord() {
    TenantId tenant = provisioning();
    assertThat(code(() -> tenants.markReady(tenant))).isEqualTo(VigilError.Code.CONFLICT);
    assertThat(message(() -> tenants.markReady(tenant)))
        .isEqualTo("Tenant " + tenant.value() + " has no settings.");
    tenantImport.importPart(tenant, PART, CLOCK.instant(), Map.of());
    assertThat(problems(() -> tenantImport.importPart(tenant, PART, CLOCK.instant(), Map.of())))
        .containsExactly("tenant settings already exist");
    tenants.markReady(tenant);
    assertThat(problems(() -> tenantImport.importPart(tenant, PART, CLOCK.instant(), Map.of())))
        .containsExactly("tenant is not provisioning", "tenant settings already exist");
  }

  @Test
  @DisplayName("AR-B-11 the import of part 1: every problem text, all in order, nothing stored")
  void importPartOne() {
    String long101 = "x".repeat(101);
    Map<TenantSettingsPart, String> single =
        Map.of(
            new TenantSettingsPart(null, null, "P", null), "customer_name: 1 to 100 characters",
            new TenantSettingsPart(long101, null, "P", null), "customer_name: 1 to 100 characters",
            new TenantSettingsPart("C", long101, "P", null),
                "environment_label: at most 100 characters",
            new TenantSettingsPart("C", null, "", null), "product_display_name: 1 to 60 characters",
            new TenantSettingsPart("C", null, "x".repeat(61), null),
                "product_display_name: 1 to 60 characters",
            new TenantSettingsPart("C", null, "P", new TenantLogo(PNG, "image/gif")),
                "customer_logo: media type image/png or image/svg+xml",
            new TenantSettingsPart("C", null, "P", new TenantLogo(new byte[0], "image/png")),
                "customer_logo: 1 byte to 512 KB",
            new TenantSettingsPart("C", null, "P", new TenantLogo(new byte[524_289], "image/png")),
                "customer_logo: 1 byte to 512 KB");
    for (Map.Entry<TenantSettingsPart, String> bad : single.entrySet()) {
      TenantId tenant = provisioning();
      assertThat(
              problems(
                  () -> tenantImport.importPart(tenant, bad.getKey(), CLOCK.instant(), Map.of())))
          .as("problems of %s", bad.getKey())
          .containsExactly(bad.getValue());
      assertThat(settingsRows(tenant)).isZero();
    }
    TenantId ready = ready();
    TenantSettingsPart everything =
        new TenantSettingsPart(null, long101, null, new TenantLogo(new byte[0], "image/jpeg"));
    assertThat(
            problems(() -> tenantImport.importPart(ready, everything, CLOCK.instant(), Map.of())))
        .containsExactly(
            "tenant is not provisioning",
            "tenant settings already exist",
            "customer_name: 1 to 100 characters",
            "environment_label: at most 100 characters",
            "product_display_name: 1 to 60 characters",
            "customer_logo: media type image/png or image/svg+xml",
            "customer_logo: 1 byte to 512 KB");
    assertThat(
            problems(
                () ->
                    tenantImport.importPart(
                        new TenantId(Ids.newId()), PART, CLOCK.instant(), Map.of())))
        .containsExactly("tenant is not provisioning");
    TenantId tenant = provisioning();
    assertThat(
            tenantImport.importPart(
                tenant,
                new TenantSettingsPart(
                    "C",
                    "x".repeat(100),
                    "x".repeat(60),
                    new TenantLogo(new byte[524_288], "image/png")),
                CLOCK.instant(),
                Map.of("unused", Ids.newId())))
        .isEmpty();
    assertThat(settingsRows(tenant)).isEqualTo(1);
    assertThatThrownBy(
            () -> tenantImport.importPart(provisioning(), null, CLOCK.instant(), Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static int settingsRows(TenantId tenant) {
    return database
        .postgres()
        .sql("SELECT count(*) FROM tenancy.tenant_settings WHERE tenant_id = ?")
        .param(tenant.value())
        .query(Integer.class)
        .single();
  }

  @Test
  @DisplayName(
      "PM-M01-04 aliases: revisions, host resolution, ready tenants only, public host name")
  void aliases() {
    TenantId a = ready();
    String host = host();
    AliasRevision first = tenants.setAlias(host.toUpperCase(), a, "installation token");
    assertThat(first.hostName()).isEqualTo(host);
    assertThat(tenants.resolveHost(host.toUpperCase())).contains(a);
    assertThat(tenants.resolveHost("unknown." + host)).isEmpty();
    assertThat(tenants.resolveHost("bad_host")).isEmpty();
    assertThat(tenants.publicHostName(a)).contains(host);
    for (String bad : List.of("bad_host.test", "with space.test", "", "x".repeat(254))) {
      VigilError error =
          org.junit.jupiter.api.Assertions.assertThrows(
              VigilError.class, () -> tenants.setAlias(bad, a, "installation token"));
      assertThat(error.code()).isEqualTo(VigilError.Code.BAD_REQUEST);
      assertThat(error.message()).isEqualTo("host name: 1 to 253 characters of a-z, 0-9, . and -");
    }
    TenantId notReady = provisioning();
    VigilError refused =
        org.junit.jupiter.api.Assertions.assertThrows(
            VigilError.class, () -> tenants.setAlias(host(), notReady, "installation token"));
    assertThat(refused.code()).isEqualTo(VigilError.Code.REFUSED);
    assertThat(refused.message()).isEqualTo("The tenant is not ready.");
    assertThat(refused.details()).isEqualTo(Map.of("refusal", "tenant_not_ready"));
    assertThat(
            code(() -> tenants.setAlias(host(), new TenantId(Ids.newId()), "installation token")))
        .isEqualTo(VigilError.Code.NOT_FOUND);
    assertThat(tenants.publicHostName(provisioning())).isEmpty();
  }

  @Test
  @DisplayName("DM-E-83 alias revisions keep host name, revision, tenant, set_at and set_by")
  void aliasRecord() {
    TenantId a = ready();
    String host = host();
    Instant now = CLOCK.advance(Duration.ofSeconds(1));
    AliasRevision revision = tenants.setAlias(host, a, "installation token");
    assertThat(revision).isEqualTo(new AliasRevision(host, 1, a, now, "installation token"));
    Map<String, Object> row =
        database
            .postgres()
            .sql("SELECT revision, tenant_id, set_by FROM tenancy.tenant_alias WHERE host_name = ?")
            .param(host)
            .query()
            .singleRow();
    assertThat(row.get("revision")).isEqualTo(1L);
    assertThat(row.get("tenant_id")).isEqualTo(a.value());
    assertThat(row.get("set_by")).isEqualTo("installation token");
  }

  @Test
  @DisplayName("DM-E83-R1 next revision per host, highest wins, earlier kept; 20 concurrent calls")
  void aliasRevisions() throws Exception {
    TenantId a = ready();
    TenantId b = ready();
    String host = host();
    assertThat(tenants.setAlias(host, a, "installation token").revision()).isEqualTo(1);
    assertThat(tenants.setAlias(host, b, "installation token").revision()).isEqualTo(2);
    assertThat(tenants.resolveHost(host)).contains(b);
    assertThat(tenants.setAlias(host, b, "installation token").revision()).isEqualTo(3);
    assertThat(tenants.resolveHost(host)).contains(b);
    String other = host();
    assertThat(tenants.setAlias(other, a, "installation token").revision()).isEqualTo(1);
    assertThat(tenants.resolveHost(other)).contains(a);
    assertThat(
            database
                .postgres()
                .sql("SELECT count(*) FROM tenancy.tenant_alias WHERE host_name = ?")
                .param(host)
                .query(Integer.class)
                .single())
        .isEqualTo(3);

    String concurrent = host();
    ExecutorService pool = Executors.newFixedThreadPool(20);
    try {
      CountDownLatch go = new CountDownLatch(1);
      List<Future<Long>> calls = new ArrayList<>();
      for (int i = 0; i < 20; i++) {
        calls.add(
            pool.submit(
                () -> {
                  go.await();
                  return tenants.setAlias(concurrent, a, "installation token").revision();
                }));
      }
      go.countDown();
      List<Long> revisions = new ArrayList<>();
      for (Future<Long> call : calls) {
        revisions.add(call.get());
      }
      assertThat(revisions)
          .containsExactlyInAnyOrderElementsOf(
              java.util.stream.LongStream.rangeClosed(1, 20).boxed().toList());
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName(
      "PM-M01-04 public host name: latest set_at, then higher revision, then smaller host name")
  void publicHostName() {
    Instant same = CLOCK.advance(Duration.ofMinutes(1));
    TenantId t1 = ready();
    tenants.setAlias("b-" + host(), t1, "installation token");
    String smaller = "a-" + host();
    tenants.setAlias(smaller, t1, "installation token");
    assertThat(CLOCK.instant()).isEqualTo(same);
    assertThat(tenants.publicHostName(t1)).contains(smaller);

    TenantId t2 = ready();
    TenantId other = ready();
    String higher = "z-" + host();
    tenants.setAlias(higher, other, "installation token");
    tenants.setAlias(higher, t2, "installation token");
    tenants.setAlias("a-" + host(), t2, "installation token");
    assertThat(tenants.publicHostName(t2)).contains(higher);

    CLOCK.advance(Duration.ofSeconds(1));
    String latest = "m-" + host();
    tenants.setAlias(latest, t2, "installation token");
    assertThat(tenants.publicHostName(t2)).contains(latest);
  }

  @Test
  @DisplayName("AR-R-19 active tenants follow the highest revision of every host")
  void activeTenants() {
    TenantId a = ready();
    TenantId b = ready();
    assertThat(tenants.isActive(a)).isFalse();
    String first = host();
    tenants.setAlias(first, a, "installation token");
    assertThat(tenants.activeTenants()).contains(a).doesNotContain(b);
    assertThat(tenants.isActive(a)).isTrue();
    String second = host();
    tenants.setAlias(second, a, "installation token");
    tenants.setAlias(first, b, "installation token");
    assertThat(tenants.activeTenants()).contains(a, b);
    tenants.setAlias(second, b, "installation token");
    assertThat(tenants.activeTenants()).contains(b).doesNotContain(a);
    assertThat(tenants.isActive(a)).isFalse();
    assertThat(tenants.isActive(b)).isTrue();
  }

  @Test
  @DisplayName(
      "AR-E-14 setAlias stores the TenantAliasChanged publication for identity in its transaction")
  void aliasChangedPublication() {
    TenantId a = ready();
    TenantId b = ready();
    String host = host();
    JsonNode first = publicationIn(host, a);
    assertThat(first.get("hostName").asString()).isEqualTo(host);
    assertThat(first.get("tenantId").get("value").asString()).isEqualTo(a.value().toString());
    assertThat(first.get("previousTenantId").isNull()).isTrue();
    JsonNode second = publicationIn(host, b);
    assertThat(second.get("tenantId").get("value").asString()).isEqualTo(b.value().toString());
    assertThat(second.get("previousTenantId").get("value").asString())
        .isEqualTo(a.value().toString());
  }

  /** Sets the alias and reads, inside the same transaction, the publication it stored. */
  private static JsonNode publicationIn(String host, TenantId tenant) {
    return tx.execute(
        status -> {
          List<Map<String, Object>> before = publications(host);
          tenants.setAlias(host, tenant, "installation token");
          List<Map<String, Object>> added = new ArrayList<>(publications(host));
          added.removeAll(before);
          assertThat(added).hasSize(4);
          assertThat(added)
              .allSatisfy(
                  row ->
                      assertThat(row.get("event_type"))
                          .isEqualTo(TenantAliasChanged.class.getName()));
          assertThat(added.stream().map(row -> (String) row.get("listener_id")))
              .anySatisfy(id -> assertThat(id).contains("vigil.identity.TenantAliasListener"))
              .anySatisfy(id -> assertThat(id).contains("vigil.intake.IntakeAliasListener"))
              .anySatisfy(id -> assertThat(id).contains("vigil.incidents.IncidentsAliasListener"))
              .anySatisfy(
                  id -> assertThat(id).contains("vigil.correlation.CorrelationAliasListener"));
          assertThat(added)
              .allSatisfy(
                  publication ->
                      assertThat(publication.get("serialized_event"))
                          .isEqualTo(added.get(0).get("serialized_event")));
          Map<String, Object> row = added.getFirst();
          return JsonMapper.builder().build().readTree((String) row.get("serialized_event"));
        });
  }

  private static List<Map<String, Object>> publications(String host) {
    return appJdbc
        .sql(
            "SELECT id, listener_id, event_type, serialized_event FROM platform.event_publication"
                + " WHERE serialized_event LIKE ?")
        .param("%" + host + "%")
        .query()
        .listOfRows();
  }

  @Test
  @DisplayName("SL-B-01 two tenants each read back their own settings and logo")
  void tenantSeparation() {
    TenantId a =
        ready(
            new TenantSettingsPart("A", "Label A", "Product A", new TenantLogo(PNG, "image/png")));
    TenantId b = ready(new TenantSettingsPart("B", null, "Product B", null));
    assertThat(tenants.settings(a))
        .isEqualTo(new TenantSettingsView("A", true, "Label A", "Product A"));
    assertThat(tenants.settings(b))
        .isEqualTo(new TenantSettingsView("B", false, null, "Product B"));
    assertThat(tenants.logo(a).orElseThrow().bytes()).isEqualTo(PNG);
    assertThat(tenants.logo(b)).isEmpty();
  }
}
