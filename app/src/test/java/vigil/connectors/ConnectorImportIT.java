package vigil.connectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static vigil.connectors.TestParts.instance;
import static vigil.connectors.TestParts.ref;
import static vigil.connectors.TestParts.withAddress;
import static vigil.connectors.TestParts.withCredentialRef;
import static vigil.connectors.TestParts.withHandle;
import static vigil.connectors.TestParts.withKind;
import static vigil.connectors.TestParts.withLocator;
import static vigil.connectors.TestParts.withName;
import static vigil.connectors.TestParts.withPurpose;
import static vigil.connectors.TestParts.withSettings;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import vigil.connectors.api.ConnectorImport;
import vigil.connectors.api.ConnectorInstanceView;
import vigil.connectors.api.ConnectorKind;
import vigil.connectors.api.ConnectorService;
import vigil.connectors.api.ConnectorsPart;
import vigil.connectors.api.ConnectorsPart.CredentialReferencePart;
import vigil.connectors.api.CredentialPurpose;
import vigil.connectors.api.CredentialReferenceView;
import vigil.shared.Ids;
import vigil.shared.TenantId;
import vigil.shared.VigilError;
import vigil.testsupport.TestSecrets;
import vigil.testsupport.VigilDatabase;
import vigil.testsupport.VigilTestApp;

class ConnectorImportIT {

  private static VigilTestApp app;
  private static TestSecrets secrets;
  private static ConnectorImport connectorImport;
  private static ConnectorService connectors;
  private static JdbcClient postgres;
  private static ConnectorsPart part;
  private static TenantId tenantA;
  private static Map<String, UUID> handlesA;

  @BeforeAll
  static void start() {
    VigilDatabase database = VigilDatabase.fresh();
    postgres = database.postgres();
    secrets =
        TestSecrets.create(
            database,
            Map.of(
                "webhook-zabbix", TestSecrets.randomToken(),
                "zabbix-api", TestSecrets.randomToken(),
                "alertmanager-password", TestSecrets.randomToken()));
    app = VigilTestApp.start(database, secrets.folder(), List.of());
    app.awaitStarted();
    connectorImport = app.context().getBean(ConnectorImport.class);
    connectors = app.context().getBean(ConnectorService.class);
    part = TestParts.ddF07();
    tenantA = new TenantId(Ids.newId());
    handlesA = connectorImport.importPart(tenantA, part, Instant.now(), Map.of());
  }

  @AfterAll
  static void stop() {
    app.close();
  }

  private static UUID id(String handle) {
    return handlesA.get(handle);
  }

  private static ConnectorInstanceView instanceA(String handle) {
    return connectors.instance(tenantA, id(handle)).orElseThrow();
  }

  private static CredentialPurpose purposeA(UUID credentialRefId) {
    return connectors.credentialReference(tenantA, credentialRefId).orElseThrow().purpose();
  }

  private static int count(TenantId tenant) {
    return postgres
            .sql("SELECT count(*) FROM connectors.credential_reference WHERE tenant_id = :t")
            .param("t", tenant.value())
            .query(Integer.class)
            .single()
        + postgres
            .sql("SELECT count(*) FROM connectors.connector_instance WHERE tenant_id = :t")
            .param("t", tenant.value())
            .query(Integer.class)
            .single();
  }

  private static void assertThirtyHandles(Map<String, UUID> handles) {
    assertThat(handles).hasSize(30);
    assertThat(new HashSet<>(handles.values())).hasSize(30);
    List<String> expected = new ArrayList<>();
    part.credentialReferences().forEach(r -> expected.add(r.handle()));
    part.connectorInstances().forEach(i -> expected.add(i.handle()));
    assertThat(handles.keySet()).containsExactlyInAnyOrderElementsOf(expected);
  }

  /** The import refused with exactly these problems, then stored nothing and imports the part. */
  private static void refused(
      ConnectorsPart changed, Map<String, UUID> handles, String... problems) {
    TenantId tenant = new TenantId(Ids.newId());
    VigilError error =
        catchThrowableOfType(
            VigilError.class,
            () -> connectorImport.importPart(tenant, changed, Instant.now(), handles));
    assertThat(error).as("refusal for %s", List.of(problems)).isNotNull();
    assertThat(TestParts.problems(error)).containsExactly(problems);
    assertThat(count(tenant)).isZero();
    assertThirtyHandles(connectorImport.importPart(tenant, part, Instant.now(), Map.of()));
  }

  private static void refused(ConnectorsPart changed, String problem) {
    refused(changed, Map.of(), problem);
  }

  @Test
  @DisplayName("DD-F-07 the seed part 3 of the demo data imports with its values")
  void demoPart() {
    assertThirtyHandles(handlesA);
    assertThat(part.credentialReferences()).hasSize(19);
    assertThat(part.connectorInstances()).hasSize(11);
    ConnectorInstanceView zabbix = instanceA("zabbix-lab");
    assertThat(zabbix.tenantId()).isEqualTo(tenantA);
    assertThat(zabbix.id()).isEqualTo(id("zabbix-lab"));
    assertThat(zabbix.name()).isEqualTo("Zabbix — Jazz lab");
    assertThat(zabbix.kind()).isEqualTo(ConnectorKind.ZABBIX);
    assertThat(zabbix.address()).isEqualTo("http://lab-zabbix-web:8080");
    assertThat(zabbix.settings()).isEqualTo(Recordings.tree("{\"api_path\":\"/api_jsonrpc.php\"}"));
    assertThat(zabbix.credentialRefId()).isEqualTo(id("cred-zabbix-api"));
    assertThat(purposeA(zabbix.credentialRefId())).isEqualTo(CredentialPurpose.EXTERNAL_API);
    assertThat(instanceA("alertmanager-lab").settings())
        .isEqualTo(Recordings.tree("{\"username\":\"vigil\"}"));
    ConnectorInstanceView email = instanceA("email-demo");
    assertThat(email.address()).isEqualTo("smtp://smtp.test:1025");
    assertThat(email.settings())
        .isEqualTo(
            Recordings.tree(
                "{\"from_address\":\"techfinity-demo@vigil.test\",\"starttls\":false}"));
    assertThat(email.settings().get("starttls").isBoolean()).isTrue();
    assertThat(purposeA(email.credentialRefId())).isEqualTo(CredentialPurpose.SMTP);
    ConnectorInstanceView linux = instanceA("linux-lab-db-01");
    assertThat(linux.settings().get("port").isIntegralNumber()).isTrue();
    assertThat(linux.settings().get("port").asLong()).isEqualTo(22);
    assertThat(linux.settings().get("host_key_sha256").stringValue()).isEqualTo(TestParts.HOST_KEY);
    assertThat(linux.credentialRefId()).isNull();
    ConnectorInstanceView teams = instanceA("teams-demo");
    assertThat(teams.settings()).isEqualTo(Recordings.tree("{}"));
    assertThat(teams.credentialRefId()).isNull();
    ConnectorInstanceView local = instanceA("local-llm");
    assertThat(local.address()).isEqualTo("http://gpu.test:11434");
    assertThat(local.settings().get("keep_alive").isIntegralNumber()).isTrue();
    assertThat(local.settings().get("keep_alive").asLong()).isEqualTo(-1);
    assertThat(local.settings().get("num_ctx").isIntegralNumber()).isTrue();
    assertThat(local.settings().get("num_ctx").asLong()).isEqualTo(16384);
    for (var instance : part.connectorInstances()) {
      assertThat(instanceA(instance.handle()).active()).isTrue();
    }
    assertThat(purposeA(id("cred-teams-noc-l1"))).isEqualTo(CredentialPurpose.WEBHOOK_URL);
    assertThat(purposeA(id("cred-webhook-zabbix"))).isEqualTo(CredentialPurpose.WEBHOOK_TOKEN);
    assertThat(purposeA(id("cred-lab-db-01-action"))).isEqualTo(CredentialPurpose.TARGET_ACTION);
  }

  @Test
  @DisplayName("AR-B-11 each single fault of part 3 refuses it with its one problem")
  void singleFaults() {
    VigilError again =
        catchThrowableOfType(
            VigilError.class,
            () -> connectorImport.importPart(tenantA, part, Instant.now(), Map.of()));
    assertThat(TestParts.problems(again)).containsExactly("connector records already exist");
    assertThat(count(tenantA)).isEqualTo(30);

    String alertmanager = "cred-webhook-alertmanager";
    refused(
        ref(part, alertmanager, r -> withHandle(r, null)),
        "credential_references: a handle is empty");
    refused(
        ref(part, alertmanager, r -> withHandle(r, "")),
        "credential_references: a handle is empty");
    refused(
        instance(part, "zabbix-lab", i -> withHandle(i, "")),
        "connector_instances: a handle is empty");
    refused(
        ref(part, alertmanager, r -> withHandle(r, "cred-webhook-zabbix")),
        "handle cred-webhook-zabbix appears more than once");
    refused(
        instance(part, "teams-demo", i -> withHandle(i, "cred-webhook-zabbix")),
        "handle cred-webhook-zabbix appears more than once");
    List<CredentialReferencePart> withH3 = new ArrayList<>(part.credentialReferences());
    for (int i = 0; i < 3; i++) {
      withH3.add(TestParts.credential("h3", "webhook_token", "secret:h3"));
    }
    refused(
        new ConnectorsPart(withH3, part.connectorInstances()), "handle h3 appears more than once");
    refused(
        part,
        Map.of("cred-webhook-zabbix", Ids.newId()),
        "handle cred-webhook-zabbix is already used by an earlier part");
    String zabbixWebhook = "cred-webhook-zabbix";
    refused(
        ref(part, zabbixWebhook, r -> withPurpose(r, "token")),
        "credential_references: purpose of cred-webhook-zabbix: unknown purpose token");
    for (String locator : List.of("secret:Bad", "file:x", "secret:" + "a".repeat(65))) {
      refused(
          ref(part, zabbixWebhook, r -> withLocator(r, locator)),
          "credential_references: locator of cred-webhook-zabbix: not of the form secret:{name}");
    }
    for (String name : List.of("n".repeat(101), "")) {
      refused(
          instance(part, "zabbix-lab", i -> withName(i, name)),
          "connector_instances: name of zabbix-lab: 1 to 100 characters");
    }
    refused(
        instance(part, "zabbix-lab", i -> withKind(i, "zabbix7")),
        "connector_instances: kind of zabbix-lab: unknown kind zabbix7");
    for (String address : List.of("a".repeat(501), "")) {
      refused(
          instance(part, "zabbix-lab", i -> withAddress(i, address)),
          "connector_instances: address of zabbix-lab: 1 to 500 characters");
    }
    for (String settings : new String[] {null, "[]"}) {
      refused(
          instance(part, "zabbix-lab", i -> withSettings(i, settings)),
          "connector_instances: settings of zabbix-lab: not an object");
    }
    refused(
        instance(part, "zabbix-lab", i -> withSettings(i, "{}")),
        "connector_instances: settings of zabbix-lab: missing api_path");
    refused(
        instance(part, "zabbix-lab", i -> withSettings(i, "{\"api_path\":3}")),
        "connector_instances: settings of zabbix-lab: api_path is not a string");
    refused(
        instance(part, "zabbix-lab", i -> withSettings(i, "{\"api_path\":\"/x\",\"x\":1}")),
        "connector_instances: settings of zabbix-lab: unknown setting x");
    refused(
        instance(
            part,
            "linux-lab-db-01",
            i ->
                withSettings(
                    i, "{\"port\":\"22\",\"host_key_sha256\":\"" + TestParts.HOST_KEY + "\"}")),
        "connector_instances: settings of linux-lab-db-01: port is not an integer");
    refused(
        instance(
            part,
            "email-demo",
            i ->
                withSettings(
                    i, "{\"from_address\":\"techfinity-demo@vigil.test\",\"starttls\":\"false\"}")),
        "connector_instances: settings of email-demo: starttls is not a boolean");
    refused(
        instance(part, "zabbix-lab", i -> withCredentialRef(i, null)),
        "connector_instances: credential_ref of zabbix-lab: required for kind zabbix");
    refused(
        instance(part, "local-llm", i -> withCredentialRef(i, "cred-claude")),
        "connector_instances: credential_ref of local-llm: not allowed for kind local_model");
    refused(
        instance(part, "claude-api", i -> withCredentialRef(i, "cred-nowhere")),
        "connector_instances: credential_ref of claude-api: unknown credential reference"
            + " cred-nowhere");
    refused(
        instance(part, "claude-api", i -> withCredentialRef(i, "cred-webhook-zabbix")),
        "connector_instances: credential_ref of claude-api: purpose must be external_api");
    assertThat(count(tenantA)).isEqualTo(30);
  }

  @Test
  @DisplayName("AR-B-11 every fault of part 3 at once gives every problem in order")
  void everyFault() {
    ConnectorsPart changed = ref(part, "cred-webhook-alertmanager", r -> withHandle(r, null));
    changed = instance(changed, "zabbix-lab", i -> withHandle(i, ""));
    changed = instance(changed, "teams-demo", i -> withHandle(i, "cred-webhook-zabbix"));
    changed = ref(changed, "cred-webhook-zabbix", r -> withPurpose(r, "token"));
    changed = ref(changed, "cred-claude", r -> withLocator(r, "file:x"));
    changed = instance(changed, "alertmanager-lab", i -> withName(i, ""));
    changed = instance(changed, "openai-api", i -> withKind(i, "openai7"));
    changed = instance(changed, "local-embed", i -> withAddress(i, ""));
    changed =
        instance(
            changed,
            "linux-lab-db-01",
            i ->
                withSettings(
                    i, "{\"port\":\"22\",\"host_key_sha256\":\"" + TestParts.HOST_KEY + "\"}"));
    changed = instance(changed, "jira-demo", i -> withCredentialRef(i, "cred-nowhere"));
    changed = instance(changed, "local-llm", i -> withCredentialRef(i, "cred-openai"));
    changed = instance(changed, "openai-embed-api", i -> withCredentialRef(i, "cred-teams-noc-l1"));
    refused(
        changed,
        Map.of("cred-jira", Ids.newId()),
        "credential_references: a handle is empty",
        "connector_instances: a handle is empty",
        "handle cred-webhook-zabbix appears more than once",
        "handle cred-jira is already used by an earlier part",
        "credential_references: purpose of cred-webhook-zabbix: unknown purpose token",
        "credential_references: locator of cred-claude: not of the form secret:{name}",
        "connector_instances: name of alertmanager-lab: 1 to 100 characters",
        "connector_instances: kind of openai-api: unknown kind openai7",
        "connector_instances: address of local-embed: 1 to 500 characters",
        "connector_instances: settings of linux-lab-db-01: port is not an integer",
        "connector_instances: credential_ref of jira-demo: unknown credential reference"
            + " cred-nowhere",
        "connector_instances: credential_ref of local-llm: not allowed for kind local_model",
        "connector_instances: credential_ref of openai-embed-api: purpose must be external_api");
  }

  @Test
  @DisplayName("DM-E-10 stored instances are active and updated by vigil-setup")
  void storedInstances() {
    List<Map<String, Object>> rows =
        postgres
            .sql("SELECT * FROM connectors.connector_instance WHERE tenant_id = :t")
            .param("t", tenantA.value())
            .query()
            .listOfRows();
    assertThat(rows).hasSize(11);
    for (Map<String, Object> row : rows) {
      assertThat(row.get("updated_by_kind")).isEqualTo("module");
      assertThat(row.get("updated_by_module")).isEqualTo("vigil-setup");
      assertThat(row.get("updated_by_user_id")).isNull();
      assertThat(row.get("created_at")).isEqualTo(row.get("updated_at"));
      assertThat(row.get("active")).isEqualTo(true);
    }
  }

  @Test
  @DisplayName("DM-E-11 stored locators are those of DD-F-07 and do not leave the module")
  void storedLocators() {
    Map<UUID, String> expected = new LinkedHashMap<>();
    part.credentialReferences().forEach(r -> expected.put(id(r.handle()), r.locator()));
    Map<UUID, String> stored = new LinkedHashMap<>();
    postgres
        .sql("SELECT id, locator FROM connectors.credential_reference WHERE tenant_id = :t")
        .param("t", tenantA.value())
        .query((rs, row) -> stored.put(rs.getObject("id", UUID.class), rs.getString("locator")))
        .list();
    assertThat(stored).isEqualTo(expected);
    assertThat(stored).containsEntry(id("cred-webhook-zabbix"), "secret:webhook-zabbix");
    assertThat(CredentialReferenceView.class.getRecordComponents()).hasSize(2);
  }

  @Test
  @DisplayName("PM-M04-03 connector records hold locators only, no secret value")
  void locatorsOnly() {
    assertNoSecretInAnyColumn();
  }

  @Test
  @DisplayName("SL-B-14 no secret value in a column of the connectors schema")
  void noSecretInRecords() {
    assertNoSecretInAnyColumn();
  }

  private static void assertNoSecretInAnyColumn() {
    List<Map<String, Object>> columns =
        postgres
            .sql(
                "SELECT table_name, column_name FROM information_schema.columns"
                    + " WHERE table_schema = 'connectors'"
                    + " AND data_type IN ('character varying', 'text', 'jsonb')")
            .query()
            .listOfRows();
    assertThat(columns).isNotEmpty();
    for (String name : List.of("webhook-zabbix", "zabbix-api", "alertmanager-password")) {
      String value = secrets.value(name);
      for (Map<String, Object> column : columns) {
        int hits =
            postgres
                .sql(
                    "SELECT count(*) FROM connectors.\""
                        + column.get("table_name")
                        + "\" WHERE strpos(\""
                        + column.get("column_name")
                        + "\"::text, :value) > 0")
                .param("value", value)
                .query(Integer.class)
                .single();
        assertThat(hits).as("%s in %s", name, column).isZero();
      }
    }
  }

  @Test
  @DisplayName("DM-C-10 connector records are read per tenant")
  void perTenant() {
    TenantId tenantB = new TenantId(Ids.newId());
    assertThirtyHandles(connectorImport.importPart(tenantB, part, Instant.now(), Map.of()));
    for (UUID id : handlesA.values()) {
      assertThat(connectors.instance(tenantB, id)).isEmpty();
      assertThat(connectors.credentialReference(tenantB, id)).isEmpty();
    }
    assertThatThrownBy(() -> connectors.testConnection(tenantB, id("zabbix-lab")))
        .isInstanceOfSatisfying(
            VigilError.class, e -> assertThat(e.code()).isEqualTo(VigilError.Code.NOT_FOUND));
  }
}
