package vigil.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.yaml.YAMLMapper;
import vigil.identity.api.Permission;
import vigil.incidents.api.IncidentService;
import vigil.incidents.api.NotifiedPurpose;
import vigil.knowledge.KnowledgeFixture;
import vigil.notifications.NotificationsFixture;
import vigil.shared.Actor;
import vigil.shared.CanonicalJson;
import vigil.shared.Ids;
import vigil.shared.Key;
import vigil.shared.TenantId;
import vigil.tenancy.api.TenantService;
import vigil.testsupport.ApiClient;
import vigil.testsupport.ApiClient.Request;
import vigil.testsupport.ApiClient.Response;
import vigil.testsupport.SeedBundles;
import vigil.testsupport.SeedBundles.MultipartBody;
import vigil.testsupport.TestSecrets;
import vigil.testsupport.TestTenants;
import vigil.testsupport.VigilDatabase;
import vigil.testsupport.VigilTestApp;

class ContractIT {

  private static final String FILE = "openapi/vigil-api.yaml";
  private static final String FILE_IRI = "https://vigil.invalid/vigil-api.yaml";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String HOOKS = "hooks.test";
  private static final String API_HOST = "api.contract.test";

  private static VigilTestApp app;
  private static ApiClient client;
  private static TestSecrets secrets;
  private static TestTenants.Tenant tenantA;
  private static String readyTenant;
  private static String readyImport;
  private static String provisioningTenant;
  private static JsonNode contract;
  private static NotificationsFixture contractApi;
  private static UUID openIncident;
  private static UUID mergedIncident;
  private static UUID resolvedIncident;
  private static UUID pendingRequest;
  private static String pendingHash;
  private static UUID cancelledRequest;
  private static String cancelledHash;
  private static SchemaRegistry schemas;

  @BeforeAll
  static void start() throws IOException {
    String text = new ClassPathResource(FILE).getContentAsString(StandardCharsets.UTF_8);
    contract = YAMLMapper.builder().build().readTree(text);
    schemas =
        SchemaRegistry.withDefaultDialect(
            SpecificationVersion.DRAFT_2020_12, builder -> builder.schemas(Map.of(FILE_IRI, text)));
    VigilDatabase database = VigilDatabase.fresh();
    Map<String, String> values = new LinkedHashMap<>();
    values.put("installation-token", TestSecrets.randomToken());
    InstallationIT.USERS.forEach(
        user -> values.put("seed-password-" + user, TestTenants.randomPassword()));
    values.put("webhook-zabbix", TestSecrets.randomToken());
    values.put("webhook-alertmanager", TestSecrets.randomToken());
    secrets = TestSecrets.create(database, values);
    app = VigilTestApp.start(database, secrets.folder(), List.of());
    app.awaitStarted();
    client = new ApiClient(app);
    byte[] png = new byte[64];
    new SecureRandom().nextBytes(png);
    TestTenants tenants = new TestTenants(app);
    tenantA =
        tenants
            .tenant("Tenant A")
            .host("a.test")
            .logo(png, "image/png")
            .user("alice", "Alice", Permission.VIEW)
            .create();
    tenants.tenant("Tenant B").host("b.test").create();
    readyTenant = createTenant();
    readyImport = startImport(readyTenant).json().get("seed_import_id").stringValue();
    awaitImport(readyImport);
    app.context()
        .getBean(TenantService.class)
        .setAlias(HOOKS, new TenantId(UUID.fromString(readyTenant)), "installation token");
    provisioningTenant = createTenant();
    String closedPort = KnowledgeFixture.closedAddress();
    contractApi =
        NotificationsFixture.create(
            app,
            "Contract API",
            API_HOST,
            KnowledgeFixture.closedAddress(),
            Integer.parseInt(closedPort.substring(closedPort.lastIndexOf(':') + 1)));
    openIncident = contractApi.openIncident();
    mergedIncident = contractApi.ticketing.investigation.mergedIncident();
    approvalRecords();
  }

  /**
   * The records of the P-43 scenarios (P-43 spec Section 6.11): {@code admin} without {@code view},
   * a resolved incident, {@code db-ops} notified of the open incident, and for a new run of the
   * open incident a proposal with a {@code pending} request 1 and a {@code cancelled} request 2,
   * inserted as postgres.
   */
  private static void approvalRecords() {
    JdbcClient postgres = contractApi.postgres();
    TenantId tenant = contractApi.tenant;
    postgres
        .sql(
            "UPDATE identity.permission_grant SET active = false WHERE tenant_id = :t"
                + " AND user_id = :u AND permission = 'view'")
        .param("t", tenant.value())
        .param("u", contractApi.userId("admin"))
        .update();
    resolvedIncident = contractApi.openIncident();
    contractApi.resolve(resolvedIncident);
    IncidentService incidents = app.context().getBean(IncidentService.class);
    incidents.recordNotifiedTeam(
        tenant,
        Actor.module("vigil-notifications"),
        openIncident,
        new Key("db-ops"),
        NotifiedPurpose.INCIDENT_NOTIFICATION);
    UUID run = Ids.newId();
    UUID proposal = Ids.newId();
    Instant now = Instant.now();
    postgres
        .sql(
            "INSERT INTO actions.action_proposal (id, tenant_id, created_at, incident_id, run_id,"
                + " action_key, action_snapshot, component_key, target_key, similarity_result_id,"
                + " past_incident_key, score, success_count) VALUES (:id, :t, :now, :incident,"
                + " :run, 'purge-archived-txlogs', CAST(:snapshot AS jsonb), 'srv-db-01',"
                + " 'lab-db-01', :result, 'PI-1001', 1.00, 4)")
        .param("id", proposal)
        .param("t", tenant.value())
        .param("now", Timestamp.from(now))
        .param("incident", openIncident)
        .param("run", run)
        .param("snapshot", JSON.writeValueAsString(actionSnapshot()))
        .param("result", Ids.newId())
        .update();
    ObjectNode pending = approvalSnapshot(now.plus(Duration.ofHours(1)));
    pendingHash = CanonicalJson.sha256HexOfCanonical(JSON.writeValueAsString(pending));
    pendingRequest =
        insertRequest(postgres, run, proposal, 1, "pending", pending, pendingHash, now, null);
    ObjectNode cancelled = approvalSnapshot(now.plus(Duration.ofHours(1)));
    cancelledHash = CanonicalJson.sha256HexOfCanonical(JSON.writeValueAsString(cancelled));
    cancelledRequest =
        insertRequest(postgres, run, proposal, 2, "cancelled", cancelled, cancelledHash, now, now);
  }

  /** The action snapshot of the demo action as {@code Snapshots.action} writes it. */
  private static ObjectNode actionSnapshot() {
    ObjectNode node = JSON.createObjectNode();
    node.put("action_key", "purge-archived-txlogs");
    node.put("name", "Purge archived transaction logs");
    node.put("operation_key", "purge_old_archives");
    node.putObject("parameter_values")
        .put("path", "/data/txlog_archive")
        .put("older_than_minutes", 1440)
        .put("max_bytes", 12884901888L);
    node.putArray("limits").add("Deletes only *.arc files directly under /data/txlog_archive");
    node.put("attempt_limit", 1);
    node.putObject("precondition")
        .put("operation_key", "fs_used_pct")
        .put("result_field", "value")
        .put("operator", "greater_or_equal")
        .put("value", "90")
        .putObject("parameters")
        .put("mount", "/data");
    node.put("recovery_check_key", "rc-subscriber-db");
    return node;
  }

  /** An approval snapshot with every key of DM-V-12 and fixed test values. */
  private static ObjectNode approvalSnapshot(Instant expiresAt) {
    ObjectNode node = JSON.createObjectNode();
    node.put("action_key", "purge-archived-txlogs");
    node.put("action_name", "Purge archived transaction logs");
    node.set("parameters", actionSnapshot().get("parameter_values"));
    node.put("target_key", "lab-db-01");
    node.put("target_name", "Subscriber DB server");
    node.putArray("limits").add("Deletes only *.arc files directly under /data/txlog_archive");
    node.put("attempt_number", 1);
    node.put("attempt_limit", 1);
    node.put("expires_at", expiresAt.toString());
    node.putArray("evidence")
        .addObject()
        .put("evidence_entry_id", Ids.newId().toString())
        .put("kind", "diagnostic_result")
        .put("summary", "/data is 100% full")
        .put("collected_at", Instant.now().toString());
    node.putObject("based_on")
        .put("past_incident_key", "PI-1001")
        .put("past_incident_title", "Subscriber DB /data full")
        .put("record_label", "Fictional record")
        .put("score", "1.00")
        .put("success_count", 4);
    ObjectNode checks = node.putObject("recovery_checks");
    checks.put("recovery_check_key", "rc-subscriber-db");
    checks.put("name", "Subscriber DB services recovered");
    checks.putArray("conditions").add("Every alert of the incident is resolved at its source");
    checks.put("observation_window", 60);
    return node;
  }

  private static UUID insertRequest(
      JdbcClient postgres,
      UUID run,
      UUID proposal,
      long sequence,
      String state,
      ObjectNode snapshot,
      String hash,
      Instant now,
      Instant decidedAt) {
    UUID id = Ids.newId();
    postgres
        .sql(
            "INSERT INTO actions.approval_request (id, tenant_id, created_at, updated_at,"
                + " incident_id, run_id, step_execution_id, proposal_id, sequence_in_run,"
                + " policy_outcome, eligible_team_keys, excluded_user_ids, snapshot,"
                + " snapshot_hash, expires_at, state, refusal_reason, decided_at, decided_by)"
                + " VALUES (:id, :t, :now, :now, :incident, :run, :step, :proposal, :sequence,"
                + " 'approval_required', CAST('[\"db-ops\"]' AS jsonb), CAST('[]' AS jsonb),"
                + " CAST(:snapshot AS jsonb), :hash, :expiresAt, :state, NULL, :decidedAt, NULL)")
        .param("id", id)
        .param("t", contractApi.tenant.value())
        .param("now", Timestamp.from(now))
        .param("incident", openIncident)
        .param("run", run)
        .param("step", Ids.newId())
        .param("proposal", proposal)
        .param("sequence", sequence)
        .param("snapshot", JSON.writeValueAsString(snapshot))
        .param("hash", hash)
        .param("expiresAt", Timestamp.from(Instant.parse(snapshot.get("expires_at").stringValue())))
        .param("state", state)
        .param("decidedAt", decidedAt == null ? null : Timestamp.from(decidedAt))
        .update();
    return id;
  }

  private static Request installation(Request request) {
    return request.header("Authorization", "Bearer " + secrets.value("installation-token"));
  }

  private static Request createTenantRequest(String body) {
    return client.post("/api/v1/installation/tenants").host("nowhere.test").json(body);
  }

  private static String createTenant() {
    return installation(createTenantRequest("{\"name\":\"contract\"}"))
        .send()
        .json()
        .get("tenant_id")
        .stringValue();
  }

  private static Request importRequest(String tenantId, byte[] bundle) {
    MultipartBody form = SeedBundles.form("bundle", "bundle.zip", bundle);
    return client
        .post("/api/v1/installation/tenants/" + tenantId + "/seed-imports")
        .host("nowhere.test")
        .header("Content-Type", form.contentType())
        .body(form.content());
  }

  private static Response startImport(String tenantId) {
    return installation(importRequest(tenantId, InstallationIT.demoBundle())).send();
  }

  private static Request seedImportRequest(String id) {
    return client.get("/api/v1/installation/seed-imports/" + id).host("nowhere.test");
  }

  private static Request aliasRequest(String host, String body) {
    return client
        .request("PUT", "/api/v1/installation/aliases/" + host)
        .host("nowhere.test")
        .json(body);
  }

  private static void awaitImport(String id) {
    long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
    while (installation(seedImportRequest(id))
        .send()
        .json()
        .get("state")
        .stringValue()
        .equals("running")) {
      assertThat(System.nanoTime()).isLessThan(deadline);
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }
  }

  @AfterAll
  static void stop() {
    app.close();
  }

  /** An operation of the file: method in lower case and path. */
  private record Operation(String method, String path) implements Comparable<Operation> {

    @Override
    public int compareTo(Operation other) {
      return (path + " " + method).compareTo(other.path + " " + other.method);
    }
  }

  /** An answer: status, content type without parameters (or null) and the body (or null). */
  private record Answer(int status, String contentType, byte[] body) {}

  /** One request of Section 7's ContractIT row and the operation it is sent to. */
  private record Scenario(Operation operation, String name, Supplier<Answer> send) {}

  private static Set<Operation> fileOperations() {
    Set<Operation> operations = new TreeSet<>();
    contract
        .get("paths")
        .properties()
        .forEach(
            path ->
                path.getValue()
                    .propertyNames()
                    .forEach(method -> operations.add(new Operation(method, path.getKey()))));
    return operations;
  }

  private static Map<RequestMappingInfo, HandlerMethod> apiHandlers() {
    Map<RequestMappingInfo, HandlerMethod> handlers =
        app.context()
            .getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class)
            .getHandlerMethods();
    return handlers.entrySet().stream()
        .filter(entry -> entry.getValue().getBeanType().getPackageName().equals("vigil.api"))
        .filter(
            entry ->
                entry.getKey().getPathPatternsCondition().getPatternValues().stream()
                    .anyMatch(pattern -> pattern.startsWith("/api/")))
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  private static String normalised(String path) {
    return path.replaceAll("\\{[^}]*}", "{}");
  }

  @Test
  @DisplayName("AR-I-08 the OpenAPI file holds exactly the operations and access of the code")
  void operationsAndSecurity() {
    assertThat(contract.get("openapi").stringValue()).isEqualTo("3.1.0");
    Set<Operation> code = new TreeSet<>();
    Set<Operation> publicInCode = new TreeSet<>();
    Set<Operation> installationInCode = new TreeSet<>();
    Set<Operation> webhookInCode = new TreeSet<>();
    apiHandlers()
        .forEach(
            (info, handler) -> {
              for (String pattern : info.getPathPatternsCondition().getPatternValues()) {
                info.getMethodsCondition()
                    .getMethods()
                    .forEach(
                        method -> {
                          Operation operation =
                              new Operation(
                                  method.name().toLowerCase(Locale.ROOT), normalised(pattern));
                          code.add(operation);
                          if (handler.hasMethodAnnotation(Public.class)) {
                            publicInCode.add(operation);
                          }
                          if (handler.hasMethodAnnotation(InstallationToken.class)) {
                            installationInCode.add(operation);
                          }
                          if (handler.hasMethodAnnotation(WebhookToken.class)) {
                            webhookInCode.add(operation);
                          }
                        });
              }
            });
    Set<Operation> file = new TreeSet<>();
    fileOperations().forEach(op -> file.add(new Operation(op.method(), normalised(op.path()))));
    assertThat(file).isEqualTo(code);

    Set<Operation> publicInFile = new TreeSet<>();
    Set<Operation> installationInFile = new TreeSet<>();
    Set<Operation> webhookInFile = new TreeSet<>();
    for (Operation operation : fileOperations()) {
      JsonNode security = operation(operation).get("security");
      assertThat(security).as("security of %s", operation).isNotNull();
      Operation normalisedOperation =
          new Operation(operation.method(), normalised(operation.path()));
      if (security.isArray() && security.isEmpty()) {
        publicInFile.add(normalisedOperation);
      } else if (security.equals(JSON.readTree("[{\"installation\":[]}]"))) {
        installationInFile.add(normalisedOperation);
      } else if (security.equals(JSON.readTree("[{\"webhook\":[]}]"))) {
        webhookInFile.add(normalisedOperation);
      } else {
        assertThat(security).isEqualTo(JSON.readTree("[{\"session\":[]}]"));
      }
    }
    assertThat(publicInFile).isEqualTo(publicInCode);
    assertThat(installationInFile).isEqualTo(installationInCode);
    assertThat(webhookInFile).isEqualTo(webhookInCode).isNotEmpty();
  }

  @Test
  @DisplayName("BP-R-13 every answer matches the OpenAPI file and every response is covered")
  void answersMatchTheFile() {
    assertThat(
            schemas
                .getSchema(
                    SchemaLocation.of(
                        FILE_IRI
                            + "#/paths/~1api~1v1~1session/post/responses/200/content/application~1json/schema"))
                .validate(JSON.readTree("{\"username\":\"alice\",\"extra\":1}")))
        .as("the validator refuses a body that does not match")
        .isNotEmpty();
    Set<String> covered = new HashSet<>();
    for (Scenario scenario : scenarios()) {
      Answer answer = scenario.send().get();
      String status = Integer.toString(answer.status());
      JsonNode responses = operation(scenario.operation()).get("responses");
      assertThat(responses.has(status))
          .as("%s: status %s is a response of %s", scenario.name(), status, scenario.operation())
          .isTrue();
      JsonNode response = responses.get(status);
      JsonNode content = response.get("content");
      if (answer.contentType() == null) {
        assertThat(content).as("%s: a response without content", scenario.name()).isNull();
      } else if (answer.contentType().equals("application/json")) {
        assertThat(content.has("application/json")).as(scenario.name()).isTrue();
        Schema schema =
            schemas.getSchema(
                SchemaLocation.of(
                    FILE_IRI
                        + "#/paths/"
                        + pointer(scenario.operation().path())
                        + "/"
                        + scenario.operation().method()
                        + "/responses/"
                        + status
                        + "/content/application~1json/schema"));
        List<Error> errors = schema.validate(JSON.readTree(answer.body()));
        assertThat(errors).as("%s: %s", scenario.name(), new String(answer.body())).isEmpty();
      } else {
        assertThat(content.has(answer.contentType()))
            .as("%s: content type %s", scenario.name(), answer.contentType())
            .isTrue();
      }
      covered.add(scenario.operation() + " " + status);
    }
    for (Operation operation : fileOperations()) {
      operation(operation)
          .get("responses")
          .propertyNames()
          .forEach(
              status ->
                  assertThat(covered)
                      .as("a scenario answers %s %s", operation, status)
                      .contains(operation + " " + status));
    }
  }

  private static JsonNode operation(Operation operation) {
    return contract.get("paths").get(operation.path()).get(operation.method());
  }

  private static String pointer(String path) {
    return path.replace("~", "~0").replace("/", "~1");
  }

  private static List<Scenario> scenarios() {
    Operation branding = new Operation("get", "/api/v1/public/branding");
    Operation logo = new Operation("get", "/api/v1/public/logo");
    Operation login = new Operation("post", "/api/v1/session");
    Operation session = new Operation("get", "/api/v1/session");
    Operation logout = new Operation("delete", "/api/v1/session");
    Operation tenant = new Operation("get", "/api/v1/tenant");
    Operation live = new Operation("get", "/api/v1/live");
    String password = tenantA.password("alice");
    String loginBody = "{\"username\":\"alice\",\"password\":\"" + password + "\"}";
    List<Scenario> scenarios =
        new ArrayList<>(
            baseScenarios(
                branding, logo, login, session, logout, tenant, live, password, loginBody));
    scenarios.addAll(installationScenarios());
    scenarios.addAll(webhookScenarios());
    scenarios.addAll(workspaceScenarios());
    scenarios.addAll(sectionScenarios());
    scenarios.addAll(approvalScenarios());
    return scenarios;
  }

  /** A Zabbix message like the first recorded one (AR §12.3). */
  private static final String ZABBIX_BODY =
      "{\"event_id\":\"36\",\"event_value\":\"1\",\"event_name\":\"Database write errors on"
          + " schema bundle_catalog\",\"host\":\"lab-db-01\",\"severity\":\"Not classified\","
          + "\"tags\":[{\"tag\":\"component\",\"value\":\"db-subscriber\"}],"
          + "\"event_date\":\"2026.10.15\",\"event_time\":\"19:00:43\",\"recovery_date\":\"\","
          + "\"recovery_time\":\"\"}";

  private static Request webhookRequest(String sourceKey, byte[] body, boolean token) {
    Request request =
        client
            .post("/api/v1/webhooks/" + sourceKey)
            .host(HOOKS)
            .header("Content-Type", "application/json")
            .body(body);
    return token
        ? request.header("Authorization", "Bearer " + secrets.value("webhook-zabbix"))
        : request;
  }

  private static List<Scenario> webhookScenarios() {
    Operation webhook = new Operation("post", "/api/v1/webhooks/{source_key}");
    byte[] body = ZABBIX_BODY.getBytes(StandardCharsets.UTF_8);
    return List.of(
        new Scenario(webhook, "webhook", send(webhookRequest("zabbix-lab", body, true))),
        new Scenario(
            webhook, "webhook without token", send(webhookRequest("zabbix-lab", body, false))),
        new Scenario(webhook, "webhook unknown source", send(webhookRequest("nope", body, true))),
        new Scenario(
            webhook,
            "webhook too large",
            send(webhookRequest("zabbix-lab", new byte[5_242_881], true))));
  }

  private static List<Scenario> installationScenarios() {
    Operation tenants = new Operation("post", "/api/v1/installation/tenants");
    Operation imports =
        new Operation("post", "/api/v1/installation/tenants/{tenant_id}/seed-imports");
    Operation record = new Operation("get", "/api/v1/installation/seed-imports/{seed_import_id}");
    Operation alias = new Operation("put", "/api/v1/installation/aliases/{host_name}");
    String readyBody = "{\"tenant_id\":\"" + readyTenant + "\"}";
    return List.of(
        new Scenario(
            tenants, "create tenant", send(installation(createTenantRequest("{\"name\":\"c\"}")))),
        new Scenario(tenants, "create tenant {}", send(installation(createTenantRequest("{}")))),
        new Scenario(
            tenants, "create tenant without token", send(createTenantRequest("{\"name\":\"c\"}"))),
        new Scenario(
            imports,
            "import",
            () -> answer(installation(importRequest(createTenant(), InstallationIT.demoBundle())))),
        new Scenario(
            imports,
            "import tenant x",
            send(installation(importRequest("x", InstallationIT.demoBundle())))),
        new Scenario(
            imports,
            "import without token",
            () -> answer(importRequest(createTenant(), InstallationIT.demoBundle()))),
        new Scenario(
            imports,
            "import unknown tenant",
            send(
                installation(
                    importRequest(UUID.randomUUID().toString(), InstallationIT.demoBundle())))),
        new Scenario(
            imports,
            "import too large",
            () -> answer(installation(importRequest(createTenant(), new byte[10_485_761])))),
        new Scenario(
            imports,
            "import not new",
            send(installation(importRequest(readyTenant, InstallationIT.demoBundle())))),
        new Scenario(record, "seed import", send(installation(seedImportRequest(readyImport)))),
        new Scenario(record, "seed import x", send(installation(seedImportRequest("x")))),
        new Scenario(record, "seed import without token", send(seedImportRequest(readyImport))),
        new Scenario(
            record,
            "seed import unknown",
            send(installation(seedImportRequest(UUID.randomUUID().toString())))),
        new Scenario(alias, "alias", send(installation(aliasRequest("contract.test", readyBody)))),
        new Scenario(alias, "alias a_b", send(installation(aliasRequest("a_b", readyBody)))),
        new Scenario(alias, "alias without token", send(aliasRequest("contract.test", readyBody))),
        new Scenario(
            alias,
            "alias unknown tenant",
            send(
                installation(
                    aliasRequest(
                        "contract.test", "{\"tenant_id\":\"" + UUID.randomUUID() + "\"}")))),
        new Scenario(
            alias,
            "alias provisioning",
            send(
                installation(
                    aliasRequest(
                        "contract.test", "{\"tenant_id\":\"" + provisioningTenant + "\"}")))));
  }

  private static List<Scenario> baseScenarios(
      Operation branding,
      Operation logo,
      Operation login,
      Operation session,
      Operation logout,
      Operation tenant,
      Operation live,
      String password,
      String loginBody) {
    return List.of(
        new Scenario(branding, "branding a.test", send(client.get(branding.path()).host("a.test"))),
        new Scenario(
            branding,
            "branding nowhere.test",
            send(client.get(branding.path()).host("nowhere.test"))),
        new Scenario(logo, "logo a.test", send(client.get(logo.path()).host("a.test"))),
        new Scenario(logo, "logo b.test", send(client.get(logo.path()).host("b.test"))),
        new Scenario(logo, "logo nowhere.test", send(client.get(logo.path()).host("nowhere.test"))),
        new Scenario(
            login, "login right", send(client.post(login.path()).host("a.test").json(loginBody))),
        new Scenario(login, "login {", send(client.post(login.path()).host("a.test").json("{"))),
        new Scenario(
            login,
            "login wrong password",
            send(
                client
                    .post(login.path())
                    .host("a.test")
                    .json("{\"username\":\"alice\",\"password\":\"" + password + "x\"}"))),
        new Scenario(
            login,
            "login nowhere.test",
            send(client.post(login.path()).host("nowhere.test").json(loginBody))),
        new Scenario(
            session, "session with session", () -> answer(withSession(client.get(session.path())))),
        new Scenario(session, "session without", send(client.get(session.path()).host("a.test"))),
        new Scenario(
            session, "session nowhere.test", send(client.get(session.path()).host("nowhere.test"))),
        new Scenario(
            logout,
            "logout with both cookies and header",
            () -> answer(withSessionAndHeader(client.delete(logout.path())))),
        new Scenario(
            logout, "logout without cookie", send(client.delete(logout.path()).host("a.test"))),
        new Scenario(
            logout,
            "logout without header",
            () -> answer(withSession(client.delete(logout.path())))),
        new Scenario(
            logout, "logout nowhere.test", send(client.delete(logout.path()).host("nowhere.test"))),
        new Scenario(
            tenant, "tenant with session", () -> answer(withSession(client.get(tenant.path())))),
        new Scenario(tenant, "tenant without", send(client.get(tenant.path()).host("a.test"))),
        new Scenario(
            tenant, "tenant nowhere.test", send(client.get(tenant.path()).host("nowhere.test"))),
        new Scenario(live, "live with session", ContractIT::openLiveStream),
        new Scenario(
            live,
            "live topics=nope",
            () -> answer(withSession(client.get(live.path() + "?topics=nope")))),
        new Scenario(
            live,
            "live without session",
            send(client.get(live.path() + "?topics=tenant").host("a.test"))),
        new Scenario(
            live,
            "live nowhere.test",
            send(client.get(live.path() + "?topics=tenant").host("nowhere.test"))));
  }

  /** A new session of the user of the Contract API tenant on {@code request}. */
  private static Request as(String username, Request request) {
    Response login = client.login(API_HOST, username, contractApi.password(username));
    assertThat(login.status()).isEqualTo(200);
    String csrf = login.cookieValue("vigil_csrf");
    return request
        .host(API_HOST)
        .cookie("vigil_session", login.cookieValue("vigil_session"))
        .cookie("vigil_csrf", csrf)
        .header("X-Vigil-CSRF", csrf);
  }

  /** The P-25 operations: every response of each (P-25 spec Section 6.12). */
  private static List<Scenario> workspaceScenarios() {
    Operation strip = new Operation("get", "/api/v1/alerts/strip");
    Operation list = new Operation("get", "/api/v1/incidents");
    List<Scenario> scenarios = new ArrayList<>();
    for (Operation operation : List.of(strip, list)) {
      scenarios.add(
          new Scenario(
              operation,
              operation.path() + " as presenter",
              () -> answer(as("presenter", client.get(operation.path())))));
      scenarios.add(
          new Scenario(
              operation,
              operation.path() + " as noc-l1",
              () -> answer(as("noc-l1", client.get(operation.path())))));
      scenarios.add(
          new Scenario(
              operation,
              operation.path() + " without session",
              send(client.get(operation.path()).host(API_HOST))));
      scenarios.add(
          new Scenario(
              operation,
              operation.path() + " nowhere.test",
              send(client.get(operation.path()).host("nowhere.test"))));
    }
    scenarios.add(
        new Scenario(
            list,
            "incidents limit=0",
            () -> answer(as("presenter", client.get(list.path() + "?limit=0")))));
    for (String section :
        List.of("", "/alerts", "/evidence", "/run", "/timeline", "/notified-teams")) {
      Operation operation = new Operation("get", "/api/v1/incidents/{incident_id}" + section);
      String path = "/api/v1/incidents/" + openIncident + section;
      scenarios.add(
          new Scenario(
              operation, path + " as presenter", () -> answer(as("presenter", client.get(path)))));
      scenarios.add(
          new Scenario(
              operation, path + " as noc-l1", () -> answer(as("noc-l1", client.get(path)))));
      scenarios.add(
          new Scenario(
              operation, path + " without session", send(client.get(path).host(API_HOST))));
      scenarios.add(
          new Scenario(
              operation, path + " nowhere.test", send(client.get(path).host("nowhere.test"))));
      String unknown = "/api/v1/incidents/" + UUID.randomUUID() + section;
      scenarios.add(
          new Scenario(
              operation,
              unknown + " unknown incident",
              () -> answer(as("presenter", client.get(unknown)))));
    }
    String merged = "/api/v1/incidents/" + mergedIncident;
    scenarios.add(
        new Scenario(
            new Operation("get", "/api/v1/incidents/{incident_id}"),
            "merged incident",
            () -> answer(as("presenter", client.get(merged)))));
    return scenarios;
  }

  /**
   * The access answers of an operation: 403 as admin, 401, 404 unknown_host and, with {@code
   * unknown}, 404 not_found as {@code user}.
   */
  private static void access(
      List<Scenario> scenarios,
      Operation operation,
      String method,
      String path,
      String unknown,
      String user) {
    scenarios.add(
        new Scenario(
            operation,
            path + " as admin",
            () -> answer(as("admin", client.request(method, path)))));
    scenarios.add(
        new Scenario(
            operation,
            path + " without session",
            send(client.request(method, path).host(API_HOST))));
    scenarios.add(
        new Scenario(
            operation,
            path + " nowhere.test",
            send(client.request(method, path).host("nowhere.test"))));
    if (unknown != null) {
      scenarios.add(
          new Scenario(
              operation,
              unknown + " unknown",
              () -> answer(as(user, client.request(method, unknown)))));
    }
  }

  /** The P-43 workspace sections, the Acknowledge page and action, and the dashboard. */
  private static List<Scenario> sectionScenarios() {
    List<Scenario> scenarios = new ArrayList<>();
    for (String section : List.of("/investigation", "/response", "/recovery", "/acknowledgement")) {
      Operation operation = new Operation("get", "/api/v1/incidents/{incident_id}" + section);
      String path = "/api/v1/incidents/" + openIncident + section;
      scenarios.add(
          new Scenario(
              operation, path + " as presenter", () -> answer(as("presenter", client.get(path)))));
      access(
          scenarios,
          operation,
          "GET",
          path,
          "/api/v1/incidents/" + UUID.randomUUID() + section,
          "presenter");
    }
    Operation acknowledge =
        new Operation("post", "/api/v1/incidents/{incident_id}/acknowledgements");
    String acknowledgePath = "/api/v1/incidents/" + openIncident + "/acknowledgements";
    String resolvedPath = "/api/v1/incidents/" + resolvedIncident + "/acknowledgements";
    scenarios.add(
        new Scenario(
            acknowledge,
            "acknowledge as dbops-engineer",
            () -> answer(as("dbops-engineer", client.post(acknowledgePath)))));
    scenarios.add(
        new Scenario(
            acknowledge,
            "acknowledge resolved incident",
            () -> answer(as("dbops-engineer", client.post(resolvedPath)))));
    access(
        scenarios,
        acknowledge,
        "POST",
        acknowledgePath,
        "/api/v1/incidents/" + UUID.randomUUID() + "/acknowledgements",
        "dbops-engineer");
    Operation dashboard = new Operation("get", "/api/v1/dashboard");
    scenarios.add(
        new Scenario(
            dashboard,
            "dashboard as presenter",
            () -> answer(as("presenter", client.get(dashboard.path())))));
    access(scenarios, dashboard, "GET", dashboard.path(), null, null);
    return scenarios;
  }

  /** The P-43 Approval page and decision. */
  private static List<Scenario> approvalScenarios() {
    List<Scenario> scenarios = new ArrayList<>();
    Operation page = new Operation("get", "/api/v1/approval-requests/{request_id}");
    String pagePath = "/api/v1/approval-requests/" + pendingRequest;
    scenarios.add(
        new Scenario(
            page,
            "approval page as presenter",
            () -> answer(as("presenter", client.get(pagePath)))));
    access(
        scenarios,
        page,
        "GET",
        pagePath,
        "/api/v1/approval-requests/" + UUID.randomUUID(),
        "dbops-engineer");
    Operation decide = new Operation("post", "/api/v1/approval-requests/{request_id}/decisions");
    String decidePath = pagePath + "/decisions";
    String cancelledPath = "/api/v1/approval-requests/" + cancelledRequest + "/decisions";
    scenarios.add(
        new Scenario(
            decide,
            "decision maybe",
            () ->
                answer(
                    as(
                        "dbops-engineer",
                        client
                            .post(decidePath)
                            .json("{\"decision\":\"maybe\",\"snapshot_hash_shown\":\"x\"}")))));
    scenarios.add(
        new Scenario(
            decide,
            "approve request 1",
            () ->
                answer(as("dbops-engineer", client.post(decidePath).json(approve(pendingHash))))));
    scenarios.add(
        new Scenario(
            decide,
            "approve cancelled request 2",
            () ->
                answer(
                    as(
                        "dbops-engineer",
                        client.post(cancelledPath).json(approve(cancelledHash))))));
    scenarios.add(
        new Scenario(
            decide,
            decidePath + " as admin",
            () -> answer(as("admin", client.post(decidePath).json(approve(pendingHash))))));
    scenarios.add(
        new Scenario(
            decide,
            decidePath + " without session",
            send(client.post(decidePath).host(API_HOST).json(approve(pendingHash)))));
    scenarios.add(
        new Scenario(
            decide,
            decidePath + " nowhere.test",
            send(client.post(decidePath).host("nowhere.test").json(approve(pendingHash)))));
    String unknown = "/api/v1/approval-requests/" + UUID.randomUUID() + "/decisions";
    scenarios.add(
        new Scenario(
            decide,
            unknown + " unknown",
            () -> answer(as("dbops-engineer", client.post(unknown).json(approve(pendingHash))))));
    return scenarios;
  }

  private static String approve(String hash) {
    return "{\"decision\":\"approve\",\"snapshot_hash_shown\":\"" + hash + "\"}";
  }

  private static Supplier<Answer> send(Request request) {
    return () -> answer(request);
  }

  private static Answer answer(Request request) {
    Response response = request.send();
    String contentType =
        response.header("Content-Type").map(value -> value.split(";")[0].trim()).orElse(null);
    return new Answer(
        response.status(), contentType, response.body().length == 0 ? null : response.body());
  }

  /** A new session of alice: the session and CSRF cookies on {@code request}. */
  private static Request withSession(Request request) {
    return withSession(request, false);
  }

  /** As {@link #withSession(Request)}, with the matching {@code X-Vigil-CSRF} header. */
  private static Request withSessionAndHeader(Request request) {
    return withSession(request, true);
  }

  private static Request withSession(Request request, boolean csrfHeader) {
    Response login = client.login("a.test", "alice", tenantA.password("alice"));
    assertThat(login.status()).isEqualTo(200);
    String csrf = login.cookieValue("vigil_csrf");
    request
        .host("a.test")
        .cookie("vigil_session", login.cookieValue("vigil_session"))
        .cookie("vigil_csrf", csrf);
    return csrfHeader ? request.header("X-Vigil-CSRF", csrf) : request;
  }

  private static Answer openLiveStream() {
    try (ApiClient.Stream stream = withSession(client.get("/api/v1/live?topics=tenant")).open()) {
      assertThat(stream.nextLine(Duration.ofSeconds(5)).orElseThrow().text())
          .isEqualTo("retry: 1000\n");
      String contentType =
          stream.headers().firstValue("Content-Type").orElseThrow().split(";")[0].trim();
      return new Answer(stream.status(), contentType, null);
    }
  }
}
