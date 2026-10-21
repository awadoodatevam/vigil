package vigil.servicemap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vigil.audit.api.AuditEventType;
import vigil.audit.api.AuditEventView;
import vigil.audit.api.AuditQuery;
import vigil.audit.api.AuditService;
import vigil.servicemap.api.ComponentView;
import vigil.servicemap.api.Counts;
import vigil.servicemap.api.GenerationView;
import vigil.servicemap.api.IdentityKey;
import vigil.servicemap.api.ImportResult;
import vigil.servicemap.api.Match;
import vigil.servicemap.api.RelationKind;
import vigil.servicemap.api.ServiceMapImported;
import vigil.servicemap.api.ServiceMapService;
import vigil.servicemap.api.ServiceView;
import vigil.servicemap.api.SourceKind;
import vigil.setup.api.Installation;
import vigil.shared.Actor;
import vigil.shared.CanonicalJson;
import vigil.shared.Ids;
import vigil.shared.TenantId;
import vigil.shared.VigilError;
import vigil.testsupport.TestSecrets;
import vigil.testsupport.TestTenants;
import vigil.testsupport.VigilDatabase;
import vigil.testsupport.VigilTestApp;

class ServiceMapIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final List<String> TABLES =
      List.of(
          "servicemap.generation",
          "servicemap.business_service",
          "servicemap.component",
          "servicemap.component_identity_key",
          "servicemap.dependency");
  private static final String SMALL_FILE =
      "services: [{service_key: svc-x, name: X, component_keys: [app-x]}]\n"
          + "components: [{component_key: app-x, name: App X, kind: application, runs_on_key:"
          + " srv-x}, {component_key: srv-x, name: Server X, kind: server}]\n"
          + "identity_keys: [{component_key: app-x, source_kind: zabbix, name: component, value:"
          + " app-x}]\n"
          + "dependencies: [{from_component_key: app-x, to_component_key: srv-x}]\n";
  private static final Listener LISTENER = new Listener();

  private static VigilTestApp app;
  private static JdbcClient postgres;
  private static ServiceMapService serviceMap;
  private static Installation installation;
  private static Actor user;
  private static TenantId tenantA;
  private static TenantId tenantB;
  private static TenantId refusedTenant;
  private static TenantId once;
  private static byte[] data;
  private static ImportResult first;
  private static ImportResult second;
  private static Map<String, List<String>> generation1Before;
  private static VigilError refused;

  /** Records every {@code ServiceMapImported} after its transaction committed. */
  static class Listener {

    final List<ServiceMapImported> received = new CopyOnWriteArrayList<>();

    @TransactionalEventListener
    void on(ServiceMapImported event) {
      received.add(event);
    }
  }

  /** The beans of the test app. */
  static class Beans {

    @Bean
    Listener serviceMapListener() {
      return LISTENER;
    }
  }

  @BeforeAll
  static void start() {
    VigilDatabase database = VigilDatabase.fresh();
    postgres = database.postgres();
    Map<String, String> values = new LinkedHashMap<>();
    values.put("installation-token", TestSecrets.randomToken());
    for (String username :
        List.of("presenter", "noc-l1", "dbops-engineer", "dbops-lead", "admin")) {
      values.put("seed-password-" + username, TestTenants.randomPassword());
    }
    TestSecrets secrets = TestSecrets.create(database, values);
    app = VigilTestApp.start(database, secrets.folder(), List.of(), Beans.class);
    app.awaitStarted();
    serviceMap = app.context().getBean(ServiceMapService.class);
    installation = app.context().getBean(Installation.class);
    user = Actor.user(Ids.newId());
    data = ServiceMapFileTest.data().getBytes(StandardCharsets.UTF_8);

    tenantA = tenant();
    first = serviceMap.importFile(tenantA, "service-map-1.yaml", data, user);
    generation1Before = rows(tenantA, 1);
    second = serviceMap.importFile(tenantA, "service-map-2.yaml", withoutUssdProbe(), user);
    tenantB = tenant();
    serviceMap.importFile(tenantB, "small.yaml", SMALL_FILE.getBytes(StandardCharsets.UTF_8), user);
    refusedTenant = tenant();
    refused =
        catchThrowableOfType(
            VigilError.class,
            () ->
                serviceMap.importFile(
                    refusedTenant,
                    "refused.yaml",
                    ServiceMapFileTest.change(13, "kind: server", "kind: router")
                        .getBytes(StandardCharsets.UTF_8),
                    user));
    once = tenant();
    serviceMap.importFile(once, "service-map.yaml", data, user);
  }

  @AfterAll
  static void stop() {
    app.close();
  }

  private static TenantId tenant() {
    return installation.createTenant("service map test " + Ids.newId());
  }

  /** The data file without {@code ussd-probe}: its component, identity key and dependency. */
  private static byte[] withoutUssdProbe() {
    List<String> lines = new ArrayList<>(List.of(ServiceMapFileTest.data().split("\n", -1)));
    for (int line : new int[] {52, 43, 24}) {
      assertThat(lines.get(line - 1)).contains("ussd-probe");
      lines.remove(line - 1);
    }
    return String.join("\n", lines).getBytes(StandardCharsets.UTF_8);
  }

  /** Every row of the five tables of the tenant's generation, as JSON text, in id order. */
  private static Map<String, List<String>> rows(TenantId tenant, long generation) {
    Map<String, List<String>> rows = new LinkedHashMap<>();
    for (String table : TABLES) {
      rows.put(
          table,
          postgres
              .sql(
                  "SELECT to_jsonb(t)::text FROM "
                      + table
                      + " t WHERE tenant_id = :tenantId AND generation = :generation ORDER BY id")
              .param("tenantId", tenant.value())
              .param("generation", generation)
              .query(String.class)
              .list());
    }
    return rows;
  }

  private static List<AuditEventView> events(TenantId tenant) {
    return app.context()
        .getBean(AuditService.class)
        .query(tenant, new AuditQuery(null, null, null, null, null, null, null, 50, null))
        .items()
        .reversed();
  }

  @Test
  @DisplayName("PM-M07-01 a file import creates the next generation, which answers every question")
  void importsGenerations() {
    assertThat(first).isEqualTo(new ImportResult(1, new Counts(4, 12, 15, 6)));
    assertThat(second).isEqualTo(new ImportResult(2, new Counts(4, 11, 14, 5)));
    assertThat(serviceMap.component(tenantA, "ussd-probe")).isEmpty();
    assertThat(serviceMap.component(tenantA, "ussd-gw")).isPresent();
  }

  @Test
  @DisplayName("DM-E-24 the generation record: file name, file hash, actor and counts")
  void generationRecord() {
    Map<String, Object> row =
        postgres
            .sql(
                "SELECT * FROM servicemap.generation WHERE tenant_id = :tenantId AND generation"
                    + " = 1")
            .param("tenantId", tenantA.value())
            .query()
            .singleRow();
    assertThat(row.get("file_name")).isEqualTo("service-map-1.yaml");
    assertThat(row.get("file_hash")).isEqualTo(CanonicalJson.sha256Hex(data));
    assertThat(row.get("imported_by_kind")).isEqualTo("user");
    assertThat(row.get("imported_by_user_id")).isEqualTo(user.userId());
    assertThat(row.get("imported_by_module")).isNull();
    assertThat(JSON.readTree(row.get("counts").toString()))
        .isEqualTo(
            JSON.readTree(
                "{\"services\":4,\"components\":12,\"identity_keys\":15,\"dependencies\":6}"));
    GenerationView view = serviceMap.currentGeneration(once).orElseThrow();
    assertThat(view.generation()).isEqualTo(1);
    assertThat(view.fileName()).isEqualTo("service-map.yaml");
    assertThat(view.importedBy()).isEqualTo(user);
    assertThat(view.counts()).isEqualTo(new Counts(4, 12, 15, 6));
  }

  @Test
  @DisplayName("DM-E-25 the business services of the file, component keys stored sorted")
  void businessServices() {
    Map<String, List<String>> rows = rows(tenantA, 1);
    assertThat(rows.get("servicemap.business_service")).hasSize(4);
    Map<String, Object> row =
        postgres
            .sql(
                "SELECT name, criticality, owner_team_key, component_keys::text AS keys FROM"
                    + " servicemap.business_service WHERE tenant_id = :tenantId AND generation = 1"
                    + " AND service_key = 'svc-balance-check'")
            .param("tenantId", tenantA.value())
            .query()
            .singleRow();
    assertThat(row.get("name")).isEqualTo("Balance Check (USSD)");
    assertThat(row.get("criticality")).isEqualTo("critical");
    assertThat(row.get("owner_team_key")).isEqualTo("vas-service-owners");
    assertThat(row.get("keys")).isEqualTo("[\"api-balance\", \"db-subscriber\", \"ussd-gw\"]");
    assertThat(
            postgres
                .sql(
                    "SELECT criticality FROM servicemap.business_service WHERE tenant_id ="
                        + " :tenantId AND generation = 1 AND service_key = 'svc-mgmt-reports'")
                .param("tenantId", tenantA.value())
                .query()
                .singleRow()
                .get("criticality"))
        .isNull();
  }

  @Test
  @DisplayName("DM-E-26 the components of the file with their values")
  void components() {
    assertThat(rows(tenantA, 1).get("servicemap.component")).hasSize(12);
    Map<String, Object> row =
        postgres
            .sql(
                "SELECT * FROM servicemap.component WHERE tenant_id = :tenantId AND generation = 1"
                    + " AND component_key = 'db-subscriber'")
            .param("tenantId", tenantA.value())
            .query()
            .singleRow();
    assertThat(row.get("name")).isEqualTo("Subscriber database");
    assertThat(row.get("kind")).isEqualTo("database");
    assertThat(row.get("criticality")).isEqualTo("critical");
    assertThat(row.get("owner_team_key")).isEqualTo("db-ops");
    assertThat(row.get("runs_on_key")).isEqualTo("srv-db-01");
    assertThat(row.get("target_key")).isNull();
  }

  @Test
  @DisplayName("DM-E-27 the identity keys of the file")
  void identityKeys() {
    assertThat(rows(tenantA, 1).get("servicemap.component_identity_key")).hasSize(15);
    assertThat(
            postgres
                .sql(
                    "SELECT component_key FROM servicemap.component_identity_key WHERE tenant_id ="
                        + " :tenantId AND generation = 1 AND source_kind = 'alertmanager' AND"
                        + " pair_name = 'instance' AND pair_value = 'lab-db-01:9100'")
                .param("tenantId", tenantA.value())
                .query(String.class)
                .single())
        .isEqualTo("srv-db-01");
  }

  @Test
  @DisplayName("DM-E-28 the listed dependencies; generation 1 unchanged after generation 2")
  void dependencies() {
    List<String> pairs =
        postgres
            .sql(
                "SELECT from_component_key || '>' || to_component_key FROM servicemap.dependency"
                    + " WHERE tenant_id = :tenantId AND generation = 1 ORDER BY 1")
            .param("tenantId", tenantA.value())
            .query(String.class)
            .list();
    assertThat(pairs)
        .containsExactly(
            "api-balance>db-subscriber",
            "api-bundle>db-subscriber",
            "sms-queue>db-subscriber",
            "ussd-gw>api-balance",
            "ussd-gw>api-bundle",
            "ussd-probe>ussd-gw");
    assertThat(rows(tenantA, 1)).isEqualTo(generation1Before);
    assertThat(rows(tenantA, 2).get("servicemap.dependency")).hasSize(5);
  }

  @Test
  @DisplayName("DM-E24-R1 a file that breaks a rule creates no generation, no event")
  void refusedFile() {
    assertThat(refused.code()).isEqualTo(VigilError.Code.INVALID);
    assertThat(refused.message()).isEqualTo("The file was not imported.");
    assertThat(refused.details())
        .isEqualTo(
            Map.of("problems", List.of("components 'srv-db-01': 'router' is not a valid kind.")));
    for (String table : TABLES) {
      assertThat(
              postgres
                  .sql("SELECT count(*) FROM " + table + " WHERE tenant_id = :tenantId")
                  .param("tenantId", refusedTenant.value())
                  .query(Long.class)
                  .single())
          .as(table)
          .isZero();
    }
    assertThat(events(refusedTenant)).isEmpty();
    assertThat(LISTENER.received).noneMatch(e -> e.tenantId().equals(refusedTenant));
    VigilError longName =
        catchThrowableOfType(
            VigilError.class,
            () -> serviceMap.importFile(refusedTenant, "n".repeat(256), data, user));
    assertThat(longName.code()).isEqualTo(VigilError.Code.BAD_REQUEST);
    assertThat(serviceMap.currentGeneration(refusedTenant)).isEmpty();
  }

  @Test
  @DisplayName("DM-R-06 answers come from the current generation and the tenant's own")
  void currentGenerationAnswers() {
    assertThat(serviceMap.relation(tenantA, "ussd-probe", "srv-db-01").kind())
        .isEqualTo(RelationKind.NONE);
    assertThat(serviceMap.dependents(tenantA, "ussd-probe")).isEmpty();
    assertThat(serviceMap.target(tenantA, "ussd-probe")).isEmpty();
    assertThat(serviceMap.affectedServices(tenantA, List.of("ussd-probe"))).isEmpty();
    assertThat(serviceMap.component(tenantB, "app-x")).isPresent();
    assertThat(serviceMap.component(tenantA, "app-x")).isEmpty();
    assertThat(serviceMap.component(tenantB, "srv-db-01")).isEmpty();
    assertThat(serviceMap.currentGeneration(tenantB).orElseThrow().generation()).isEqualTo(1);
    assertThat(serviceMap.currentGeneration(tenantA).orElseThrow().generation()).isEqualTo(2);
  }

  @Test
  @DisplayName(
      "DM-C-07 each file import writes one configuration_changed event with before and after")
  void configurationChanged() {
    List<AuditEventView> events = events(tenantA);
    assertThat(events).hasSize(2);
    for (AuditEventView event : events) {
      assertThat(event.eventType()).isEqualTo(AuditEventType.CONFIGURATION_CHANGED);
      assertThat(event.actor()).isEqualTo(user);
      assertThat(event.subjectType()).isEqualTo("ServiceMapGeneration");
      assertThat(JSON.readTree(event.detailsJson()).get("area").stringValue())
          .isEqualTo("service_map");
    }
    String one =
        "{\"generation\":1,\"file_name\":\"service-map-1.yaml\",\"counts\":{\"services\":4,"
            + "\"components\":12,\"identity_keys\":15,\"dependencies\":6}}";
    String two =
        "{\"generation\":2,\"file_name\":\"service-map-2.yaml\",\"counts\":{\"services\":4,"
            + "\"components\":11,\"identity_keys\":14,\"dependencies\":5}}";
    JsonNode firstDetails = JSON.readTree(events.get(0).detailsJson());
    assertThat(firstDetails.get("before").isNull()).isTrue();
    assertThat(firstDetails.get("after")).isEqualTo(JSON.readTree(one));
    JsonNode secondDetails = JSON.readTree(events.get(1).detailsJson());
    assertThat(secondDetails.get("before")).isEqualTo(JSON.readTree(one));
    assertThat(secondDetails.get("after")).isEqualTo(JSON.readTree(two));
  }

  @Test
  @DisplayName("DC-TX-10 the summary of a file import, with singular counts")
  void summaries() {
    assertThat(events(tenantA).stream().map(AuditEventView::summary))
        .containsExactly(
            "Service map imported as generation 1 (4 services, 12 components, 15 identity keys,"
                + " 6 dependencies).",
            "Service map imported as generation 2 (4 services, 11 components, 14 identity keys,"
                + " 5 dependencies).");
    assertThat(events(tenantB).stream().map(AuditEventView::summary))
        .containsExactly(
            "Service map imported as generation 1 (1 service, 2 components, 1 identity key,"
                + " 1 dependency).");
  }

  @Test
  @DisplayName("AR-E-04 ServiceMapImported with the generation, after the commit")
  void serviceMapImported() {
    assertThat(LISTENER.received.stream().filter(e -> e.tenantId().equals(tenantA)))
        .containsExactly(new ServiceMapImported(tenantA, 1), new ServiceMapImported(tenantA, 2));
  }

  @Test
  @DisplayName("PM-M07-02 the component an alert's identity pairs match, or unknown")
  void matching() {
    assertThat(
            serviceMap.match(
                tenantA,
                SourceKind.ZABBIX,
                List.of(
                    new IdentityKey("component", "db-subscriber"),
                    new IdentityKey("host", "lab-db-01"))))
        .isEqualTo(new Match(2, "db-subscriber"));
    assertThat(
            serviceMap.match(
                tenant(), SourceKind.ZABBIX, List.of(new IdentityKey("host", "lab-db-01"))))
        .isEqualTo(new Match(0, null));
  }

  @Test
  @DisplayName("PM-M07-06 the target server of a component")
  void targets() {
    assertThat(serviceMap.target(tenantA, "srv-db-01")).contains("lab-db-01");
    assertThat(serviceMap.target(tenantA, "db-subscriber")).contains("lab-db-01");
    assertThat(serviceMap.target(tenantA, "api-balance")).isEmpty();
    assertThat(serviceMap.target(tenantA, "nope")).isEmpty();
  }

  @Test
  @DisplayName("PM-M07-05 every service and component with owners and criticality")
  void servicesAndComponents() {
    assertThat(serviceMap.services(once).stream().map(ServiceView::serviceKey))
        .containsExactly(
            "svc-balance-check",
            "svc-bundle-purchase",
            "svc-mgmt-reports",
            "svc-sms-notifications");
    List<ComponentView> components = serviceMap.components(once);
    assertThat(components.stream().map(ComponentView::componentKey))
        .containsExactly(
            "api-balance",
            "api-bundle",
            "db-subscriber",
            "report-export",
            "sms-queue",
            "srv-api-01",
            "srv-db-01",
            "srv-probe-01",
            "srv-rpt-01",
            "srv-vas-01",
            "ussd-gw",
            "ussd-probe");
    for (ComponentView component : components) {
      assertThat(serviceMap.component(once, component.componentKey())).contains(component);
    }
    TenantId empty = tenant();
    assertThat(serviceMap.services(empty)).isEmpty();
    assertThat(serviceMap.components(empty)).isEmpty();
  }
}
