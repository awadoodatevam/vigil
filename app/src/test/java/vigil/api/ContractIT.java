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
import java.time.Duration;
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
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;
import vigil.identity.api.Permission;
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

  private static VigilTestApp app;
  private static ApiClient client;
  private static TestSecrets secrets;
  private static TestTenants.Tenant tenantA;
  private static String readyTenant;
  private static String readyImport;
  private static String provisioningTenant;
  private static JsonNode contract;
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
