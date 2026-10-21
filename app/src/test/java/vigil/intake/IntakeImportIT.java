package vigil.intake;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import vigil.connectors.api.ConnectorImport;
import vigil.intake.api.AlertTypeView;
import vigil.intake.api.Health;
import vigil.intake.api.IntakeImport;
import vigil.intake.api.IntakePart;
import vigil.intake.api.IntakePart.AlertTypePart;
import vigil.intake.api.IntakePart.MappingPart;
import vigil.intake.api.IntakePart.SourcePart;
import vigil.intake.api.IntakeService;
import vigil.intake.api.SourceHealthChanged;
import vigil.intake.api.SourceView;
import vigil.shared.Actor;
import vigil.shared.TenantId;
import vigil.shared.VigilError;
import vigil.tenancy.api.TenantService;
import vigil.testsupport.MutableClock;
import vigil.testsupport.TestSecrets;
import vigil.testsupport.VigilDatabase;
import vigil.testsupport.VigilTestApp;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IntakeImportIT {

  private static final MutableClock CLOCK = new MutableClock(Instant.parse("2026-10-21T08:00:00Z"));
  private static final List<String> TABLES =
      List.of(
          "monitoring_source",
          "alert_type_definition",
          "alert_type_mapping",
          "source_health_change",
          "alert_counter");

  private WireMockServer wiremock;
  private VigilDatabase database;
  private VigilTestApp app;
  private JdbcClient postgres;
  private IntakeService intake;
  private IntakeFixture.Events events;
  private TenantId tenant;
  private Map<String, UUID> handles;
  private final Map<String, List<String>> refusals = new LinkedHashMap<>();

  /** The test clock and the event recorder. */
  static class TestBeans {

    @Bean
    @Primary
    Clock testClock() {
      return CLOCK;
    }

    @Bean
    IntakeFixture.Events intakeEvents() {
      return new IntakeFixture.Events();
    }
  }

  @BeforeAll
  void start() {
    wiremock = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort());
    wiremock.start();
    IntakeFixture.stub(wiremock);
    database = VigilDatabase.fresh();
    TestSecrets secrets = IntakeFixture.secrets(database, "");
    app = VigilTestApp.start(database, secrets.folder(), List.of(), TestBeans.class);
    app.awaitStarted();
    postgres = database.postgres();
    intake = app.context().getBean(IntakeService.class);
    events = app.context().getBean(IntakeFixture.Events.class);
  }

  @AfterAll
  void stop() {
    app.close();
    wiremock.stop();
  }

  private String address() {
    return "http://127.0.0.1:" + wiremock.port();
  }

  /** A new tenant in state {@code provisioning} (not active) with the fixture's connector part. */
  private Map.Entry<TenantId, Map<String, UUID>> provisioningTenant() {
    TenantId created =
        app.context()
            .getBean(TenantService.class)
            .createTenant("intake import", Actor.module("vigil-setup"));
    Map<String, UUID> ids =
        app.context()
            .getBean(ConnectorImport.class)
            .importPart(
                created, IntakeFixture.connectorsPart(address(), ""), CLOCK.instant(), Map.of());
    return Map.entry(created, ids);
  }

  @SuppressWarnings("unchecked")
  private List<String> refused(TenantId into, Map<String, UUID> ids, IntakePart part) {
    VigilError error =
        catchThrowableOfType(
            VigilError.class,
            () ->
                app.context()
                    .getBean(IntakeImport.class)
                    .importPart(into, part, CLOCK.instant(), ids));
    assertThat(error).as("refusal").isNotNull();
    assertThat(error.code()).isEqualTo(VigilError.Code.INVALID);
    assertThat(error.message()).isEqualTo("Part intake refused.");
    return (List<String>) error.details().get("problems");
  }

  /** One fault in the demo part, imported into a fresh tenant: the problems; nothing stored. */
  private List<String> refusedCase(String name, UnaryOperator<IntakePart> fault) {
    Map.Entry<TenantId, Map<String, UUID>> fresh = provisioningTenant();
    List<String> problems =
        refused(fresh.getKey(), fresh.getValue(), fault.apply(IntakeFixture.demoPart()));
    for (String table : TABLES) {
      assertThat(rows(table, fresh.getKey())).as("%s: rows of %s", name, table).isZero();
    }
    refusals.put(name, problems);
    return problems;
  }

  private long rows(String table, TenantId of) {
    return postgres
        .sql("SELECT count(*) FROM intake." + table + " WHERE tenant_id = :tenantId")
        .param("tenantId", of.value())
        .query(Long.class)
        .single();
  }

  private static UnaryOperator<IntakePart> source(String key, UnaryOperator<SourcePart> change) {
    return part -> IntakeFixture.source(part, key, change);
  }

  private static UnaryOperator<IntakePart> type(String type, UnaryOperator<AlertTypePart> change) {
    return part -> IntakeFixture.alertType(part, type, change);
  }

  private static UnaryOperator<IntakePart> mapping(int index, UnaryOperator<MappingPart> change) {
    return part -> IntakeFixture.mapping(part, index, change);
  }

  private static SourcePart name(SourcePart s, String name) {
    return new SourcePart(
        name,
        s.sourceKey(),
        s.sourceKind(),
        s.connectorInstance(),
        s.webhookTokenRef(),
        s.healthCheckIntervalS(),
        s.staleAfterS(),
        s.disconnectedAfterS());
  }

  private static SourcePart key(SourcePart s, String key) {
    return new SourcePart(
        s.name(),
        key,
        s.sourceKind(),
        s.connectorInstance(),
        s.webhookTokenRef(),
        s.healthCheckIntervalS(),
        s.staleAfterS(),
        s.disconnectedAfterS());
  }

  private static SourcePart kind(SourcePart s, String kind) {
    return new SourcePart(
        s.name(),
        s.sourceKey(),
        kind,
        s.connectorInstance(),
        s.webhookTokenRef(),
        s.healthCheckIntervalS(),
        s.staleAfterS(),
        s.disconnectedAfterS());
  }

  private static SourcePart instance(SourcePart s, String instance) {
    return new SourcePart(
        s.name(),
        s.sourceKey(),
        s.sourceKind(),
        instance,
        s.webhookTokenRef(),
        s.healthCheckIntervalS(),
        s.staleAfterS(),
        s.disconnectedAfterS());
  }

  private static SourcePart token(SourcePart s, String token) {
    return new SourcePart(
        s.name(),
        s.sourceKey(),
        s.sourceKind(),
        s.connectorInstance(),
        token,
        s.healthCheckIntervalS(),
        s.staleAfterS(),
        s.disconnectedAfterS());
  }

  private static SourcePart durations(
      SourcePart s, String interval, String stale, String disconnected) {
    return new SourcePart(
        s.name(),
        s.sourceKey(),
        s.sourceKind(),
        s.connectorInstance(),
        s.webhookTokenRef(),
        interval,
        stale,
        disconnected);
  }

  private static MappingPart mappingWith(
      MappingPart m, String kind, String order, String field, String operator, String value) {
    return new MappingPart(kind, order, field, operator, value, m.alertType());
  }

  private static final String S = "monitoring_sources: ";
  private static final String M = "alert_type_mappings: ";

  @Test
  @Order(1)
  @DisplayName(
      "DM-R-57 part 6 is checked by the rules of DM-E-15 to DM-E-17 and refused as a whole")
  void partRefusals() {
    Map.Entry<TenantId, Map<String, UUID>> main = provisioningTenant();
    tenant = main.getKey();
    handles = main.getValue();
    assertThat(
            app.context()
                .getBean(IntakeImport.class)
                .importPart(tenant, IntakeFixture.demoPart(), CLOCK.instant(), handles))
        .isEmpty();
    List<String> second = refused(tenant, handles, IntakeFixture.demoPart());
    assertThat(second).containsExactly("monitoring records already exist");
    assertThat(rows("monitoring_source", tenant)).isEqualTo(2);

    String z = IntakeFixture.ZABBIX;
    String a = IntakeFixture.ALERTMANAGER;
    assertThat(refusedCase("name", source(z, s -> name(s, ""))))
        .containsExactly(S + "name of zabbix-lab: 1 to 100 characters");
    assertThat(refusedCase("key", source(z, s -> key(s, "a b"))))
        .containsExactly(S + "source_key a b: not a valid key");
    assertThat(refusedCase("key twice", source(a, s -> key(s, z))))
        .containsExactly(S + "source_key zabbix-lab appears more than once");
    assertThat(refusedCase("kind", source(z, s -> kind(s, "nagios"))))
        .containsExactly(S + "source_kind of zabbix-lab: unknown kind nagios");
    assertThat(refusedCase("instance", source(z, s -> instance(s, "nope"))))
        .containsExactly(S + "connector_instance of zabbix-lab: unknown connector instance nope");
    assertThat(refusedCase("instance kind", source(a, s -> instance(s, z))))
        .containsExactly(S + "connector_instance of alertmanager-lab: kind must be alertmanager");
    assertThat(refusedCase("token", source(z, s -> token(s, "nope"))))
        .containsExactly(S + "webhook_token_ref of zabbix-lab: unknown credential reference nope");
    assertThat(refusedCase("token purpose", source(z, s -> token(s, "cred-zabbix-api"))))
        .containsExactly(S + "webhook_token_ref of zabbix-lab: purpose must be webhook_token");
    assertThat(refusedCase("token twice", source(a, s -> token(s, "cred-webhook-zabbix"))))
        .containsExactly(
            S + "webhook_token_ref cred-webhook-zabbix is used by more than one source");
    assertThat(refusedCase("stale", source(z, s -> durations(s, "30", "2m", "300"))))
        .containsExactly(S + "stale_after_s of zabbix-lab: not a whole number of seconds");
    assertThat(refusedCase("interval 0", source(z, s -> durations(s, "0", "120", "300"))))
        .containsExactly(S + "health_check_interval_s of zabbix-lab: at least 1");
    assertThat(refusedCase("interval", source(z, s -> durations(s, "120", "120", "300"))))
        .containsExactly(
            S + "health_check_interval_s of zabbix-lab: must be less than stale_after_s");
    assertThat(refusedCase("disconnected", source(z, s -> durations(s, "30", "120", "120"))))
        .containsExactly(
            S + "disconnected_after_s of zabbix-lab: must be greater than stale_after_s");
    String cert = "cert_expiring";
    assertThat(refusedCase("type key", type(cert, t -> new AlertTypePart("x y", t.displayName()))))
        .containsExactly("alert_types: alert_type x y: not a valid key");
    assertThat(
            refusedCase(
                "unclassified",
                type(cert, t -> new AlertTypePart("unclassified", t.displayName()))))
        .containsExactly("alert_types: alert_type unclassified is reserved");
    assertThat(
            refusedCase(
                "type twice",
                type(cert, t -> new AlertTypePart("cpu_saturation", t.displayName()))))
        .containsExactly("alert_types: alert_type cpu_saturation appears more than once");
    assertThat(refusedCase("display", type(cert, t -> new AlertTypePart(cert, "d".repeat(101)))))
        .containsExactly("alert_types: display_name of cert_expiring: 1 to 100 characters");
    assertThat(
            refusedCase(
                "mapping kind",
                mapping(
                    0,
                    m -> mappingWith(m, "nagios", m.order(), m.field(), m.operator(), m.value()))))
        .containsExactly(M + "source_kind of nagios 1: unknown kind nagios");
    assertThat(
            refusedCase(
                "order",
                mapping(
                    0,
                    m ->
                        mappingWith(
                            m, m.sourceKind(), "first", m.field(), m.operator(), m.value()))))
        .containsExactly(M + "order of zabbix first: not a whole number");
    assertThat(
            refusedCase(
                "order twice",
                mapping(
                    1,
                    m -> mappingWith(m, m.sourceKind(), "1", m.field(), m.operator(), m.value()))))
        .containsExactly(M + "order 1 appears more than once for zabbix");
    assertThat(
            refusedCase(
                "field",
                mapping(
                    0,
                    m ->
                        mappingWith(
                            m,
                            m.sourceKind(),
                            m.order(),
                            "label.alertname",
                            m.operator(),
                            m.value()))))
        .containsExactly(M + "field of zabbix 1: not a field of zabbix");
    assertThat(
            refusedCase(
                "field tag.",
                mapping(
                    0,
                    m ->
                        mappingWith(
                            m, m.sourceKind(), m.order(), "tag.", m.operator(), m.value()))))
        .containsExactly(M + "field of zabbix 1: not a field of zabbix");
    assertThat(
            refusedCase(
                "operator",
                mapping(
                    0,
                    m -> mappingWith(m, m.sourceKind(), m.order(), m.field(), "regex", m.value()))))
        .containsExactly(M + "operator of zabbix 1: unknown operator regex");
    assertThat(
            refusedCase(
                "value",
                mapping(
                    0,
                    m ->
                        mappingWith(
                            m,
                            m.sourceKind(),
                            m.order(),
                            m.field(),
                            m.operator(),
                            "v".repeat(201)))))
        .containsExactly(M + "value of zabbix 1: at most 200 characters");
    assertThat(
            refusedCase(
                "mapping type",
                mapping(
                    0,
                    m ->
                        new MappingPart(
                            m.sourceKind(),
                            m.order(),
                            m.field(),
                            m.operator(),
                            m.value(),
                            "nope"))))
        .containsExactly(M + "alert_type of zabbix 1: unknown alert type nope");
    assertThat(
            refusedCase(
                "three",
                part ->
                    mapping(
                            0,
                            m ->
                                mappingWith(
                                    m, m.sourceKind(), m.order(), m.field(), "regex", m.value()))
                        .apply(
                            source(z, s -> durations(name(s, ""), "30", "2m", "300")).apply(part))))
        .containsExactly(
            S + "name of zabbix-lab: 1 to 100 characters",
            S + "stale_after_s of zabbix-lab: not a whole number of seconds",
            M + "operator of zabbix 1: unknown operator regex");
  }

  @Test
  @Order(2)
  @DisplayName("DM-E-15 the monitoring sources of the part with their values")
  void sources() {
    Map<String, Object> row =
        postgres
            .sql(
                "SELECT * FROM intake.monitoring_source WHERE tenant_id = :tenantId"
                    + " AND source_key = 'zabbix-lab'")
            .param("tenantId", tenant.value())
            .query()
            .singleRow();
    assertThat(row.get("name")).isEqualTo("Zabbix — Jazz lab");
    assertThat(row.get("source_kind")).isEqualTo("zabbix");
    assertThat(row.get("health_check_interval")).isEqualTo(30);
    assertThat(row.get("stale_after")).isEqualTo(120);
    assertThat(row.get("disconnected_after")).isEqualTo(300);
    assertThat(row.get("health")).isEqualTo("disconnected");
    assertThat(row.get("connector_instance_id")).isEqualTo(handles.get("zabbix-lab"));
    assertThat(row.get("webhook_token_ref_id")).isEqualTo(handles.get("cred-webhook-zabbix"));
    assertThat(row.get("active")).isEqualTo(true);
    assertThat(row.get("last_contact_at")).isNull();
    assertThat(row.get("last_test_at")).isNull();
    assertThat(row.get("updated_by_kind")).isEqualTo("module");
    assertThat(row.get("updated_by_user_id")).isNull();
    assertThat(row.get("updated_by_module")).isEqualTo("vigil-setup");
    List<SourceView> sources = intake.sources(tenant);
    assertThat(sources.stream().map(SourceView::sourceKey))
        .containsExactly("alertmanager-lab", "zabbix-lab");
    SourceView alertmanager = sources.getFirst();
    assertThat(alertmanager.name()).isEqualTo("Alertmanager — Jazz lab");
    assertThat(alertmanager.connectorInstanceId()).isEqualTo(handles.get("alertmanager-lab"));
    assertThat(alertmanager.health()).isEqualTo(Health.DISCONNECTED);
    assertThat(alertmanager.healthChangedAt()).isEqualTo(CLOCK.instant());
  }

  @Test
  @Order(3)
  @DisplayName("DM-E-16 the alert type definitions: unclassified and the part's 18")
  void alertTypes() {
    List<AlertTypeView> types = intake.alertTypes(tenant);
    assertThat(types).hasSize(19);
    assertThat(types).contains(new AlertTypeView("unclassified", "Unclassified"));
    assertThat(types).contains(new AlertTypeView("disk_full", "Disk full"));
    List<String> keys = types.stream().map(AlertTypeView::alertType).toList();
    assertThat(keys).isSortedAccordingTo(String::compareTo);
    assertThat(rows("alert_type_definition", tenant)).isEqualTo(19);
  }

  @Test
  @Order(4)
  @DisplayName("DM-E-17 the alert type mappings with their values, and the counter row at 0")
  void mappings() {
    List<Map<String, Object>> rows =
        postgres
            .sql(
                "SELECT source_kind, \"order\", field, operator, value, alert_type"
                    + " FROM intake.alert_type_mapping WHERE tenant_id = :tenantId"
                    + " ORDER BY source_kind, \"order\"")
            .param("tenantId", tenant.value())
            .query()
            .listOfRows();
    assertThat(rows).hasSize(9);
    assertThat(rows.get(4))
        .isEqualTo(
            Map.of(
                "source_kind", "alertmanager",
                "order", 5L,
                "field", "label.alertname",
                "operator", "equals",
                "value", "SyntheticUssdSessionFailed",
                "alert_type", "ussd_synthetic_failed"));
    assertThat(rows.get(5))
        .isEqualTo(
            Map.of(
                "source_kind", "zabbix",
                "order", 1L,
                "field", "event_name",
                "operator", "starts_with",
                "value", "Disk full",
                "alert_type", "disk_full"));
    assertThat(
            postgres
                .sql("SELECT last_sequence FROM intake.alert_counter WHERE tenant_id = :tenantId")
                .param("tenantId", tenant.value())
                .query(Long.class)
                .single())
        .isZero();
  }

  @Test
  @Order(5)
  @DisplayName("BP-A-25 unclassified always exists and a part that lists it is refused")
  void unclassified() {
    assertThat(refusals.get("unclassified"))
        .containsExactly("alert_types: alert_type unclassified is reserved");
    assertThat(
            postgres
                .sql(
                    "SELECT display_name FROM intake.alert_type_definition"
                        + " WHERE tenant_id = :tenantId AND alert_type = 'unclassified'")
                .param("tenantId", tenant.value())
                .query(String.class)
                .single())
        .isEqualTo("Unclassified");
  }

  @Test
  @Order(6)
  @DisplayName("DM-R-41 the import refuses a health_check_interval that is not below stale_after")
  void intervalBelowStale() {
    assertThat(refusals.get("interval"))
        .containsExactly(
            S + "health_check_interval_s of zabbix-lab: must be less than stale_after_s");
  }

  @Test
  @Order(7)
  @DisplayName("SL-B-14 one webhook token per source")
  void oneTokenPerSource() {
    assertThat(refusals.get("token twice"))
        .containsExactly(
            S + "webhook_token_ref cred-webhook-zabbix is used by more than one source");
    assertThat(refusals.get("token purpose"))
        .containsExactly(S + "webhook_token_ref of zabbix-lab: purpose must be webhook_token");
  }

  @Test
  @Order(8)
  @DisplayName("DM-SM-03 a new source is disconnected (source_created); its first test follows")
  void sourceCreatedThenFirstTest() {
    for (SourceView source : intake.sources(tenant)) {
      List<Map<String, Object>> changes =
          postgres
              .sql(
                  "SELECT from_health, to_health, cause FROM intake.source_health_change"
                      + " WHERE tenant_id = :tenantId AND source_id = :sourceId ORDER BY at, id")
              .param("tenantId", tenant.value())
              .param("sourceId", source.id())
              .query()
              .listOfRows();
      assertThat(changes)
          .as(source.sourceKey())
          .hasSize(1)
          .first()
          .satisfies(
              change -> {
                assertThat(change.get("from_health")).isNull();
                assertThat(change.get("to_health")).isEqualTo("disconnected");
                assertThat(change.get("cause")).isEqualTo("source_created");
              });
    }
    assertThat(events.of(tenant)).isEmpty();

    app.context().getBean(IntakeImport.class).startFirstTests(tenant);
    IntakeFixture.await(
        () ->
            wiremock
                        .findAll(
                            postRequestedFor(urlEqualTo(IntakeFixture.ZABBIX_PATH))
                                .withRequestBody(containing("\"host.get\"")))
                        .size()
                    == 1
                && wiremock
                        .findAll(getRequestedFor(urlEqualTo(IntakeFixture.ALERTMANAGER_PATH)))
                        .size()
                    == 1);
    IntakeFixture.await(
        () -> intake.sources(tenant).stream().allMatch(s -> s.health() == Health.HEALTHY));
    for (SourceView source : intake.sources(tenant)) {
      assertThat(
              postgres
                  .sql(
                      "SELECT cause FROM intake.source_health_change WHERE tenant_id = :tenantId"
                          + " AND source_id = :sourceId AND to_health = 'healthy'")
                  .param("tenantId", tenant.value())
                  .param("sourceId", source.id())
                  .query(String.class)
                  .list())
          .containsExactly("contact");
    }
    IntakeFixture.await(() -> events.of(tenant).size() == 2);
    assertThat(events.of(tenant))
        .containsExactlyInAnyOrderElementsOf(
            intake.sources(tenant).stream()
                .map(s -> (Object) new SourceHealthChanged(tenant, s.id(), Health.HEALTHY))
                .toList());
  }
}
