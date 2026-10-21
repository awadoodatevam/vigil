package vigil.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.JsonNode;
import vigil.shared.Actor;
import vigil.shared.Ids;
import vigil.shared.TenantId;
import vigil.tenancy.api.TenantService;
import vigil.tenancy.api.TenantState;
import vigil.tenancy.api.TenantView;
import vigil.testsupport.ApiClient;
import vigil.testsupport.ApiClient.Request;
import vigil.testsupport.ApiClient.Response;
import vigil.testsupport.LogLines;
import vigil.testsupport.SeedBundles;
import vigil.testsupport.SeedBundles.MultipartBody;
import vigil.testsupport.TestSecrets;
import vigil.testsupport.TestTenants;
import vigil.testsupport.VigilDatabase;
import vigil.testsupport.VigilTestApp;

@ExtendWith(OutputCaptureExtension.class)
class InstallationIT {

  static final List<String> USERS =
      List.of("presenter", "noc-l1", "dbops-engineer", "dbops-lead", "admin");
  private static final Path DATA = Path.of("../docs/31-demo-data");

  private static VigilDatabase database;
  private static TestSecrets secrets;
  private static VigilTestApp app;
  private static ApiClient client;
  private static TenantService tenants;

  @BeforeAll
  static void start() {
    database = VigilDatabase.fresh();
    secrets = secrets(database);
    app = VigilTestApp.start(database, secrets.folder(), List.of());
    app.awaitStarted();
    client = new ApiClient(app);
    tenants = app.context().getBean(TenantService.class);
  }

  @AfterAll
  static void stop() {
    app.close();
  }

  /** A secret folder with the installation token and the five seed passwords of DD-F-01. */
  static TestSecrets secrets(VigilDatabase database) {
    Map<String, String> values = new LinkedHashMap<>();
    values.put("installation-token", TestSecrets.randomToken());
    USERS.forEach(user -> values.put("seed-password-" + user, TestTenants.randomPassword()));
    return TestSecrets.create(database, values);
  }

  /** The demo bundle with test values and a random host key fingerprint. */
  static byte[] demoBundle() {
    return SeedBundles.zip(demoEntries());
  }

  static List<Map.Entry<String, byte[]>> demoEntries() {
    byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);
    return SeedBundles.demoEntries(
        SeedBundles.testValues("SHA256:" + Base64.getEncoder().encodeToString(key)));
  }

  private static String token() {
    return secrets.value("installation-token");
  }

  private static Request installation(Request request) {
    return request.header("Authorization", "Bearer " + token());
  }

  private static Request createTenantRequest(String name) {
    return client
        .post("/api/v1/installation/tenants")
        .host("nowhere.test")
        .json("{\"name\":\"" + name + "\"}");
  }

  private static Request importRequest(String tenantId, byte[] bundle) {
    MultipartBody form = SeedBundles.form("bundle", "bundle.zip", bundle);
    return client
        .post("/api/v1/installation/tenants/" + tenantId + "/seed-imports")
        .host("nowhere.test")
        .header("Content-Type", form.contentType())
        .body(form.content());
  }

  private static Request seedImportRequest(String id) {
    return client.get("/api/v1/installation/seed-imports/" + id).host("nowhere.test");
  }

  private static Request aliasRequest(String host, String tenantId) {
    return client
        .request("PUT", "/api/v1/installation/aliases/" + host)
        .host("nowhere.test")
        .json("{\"tenant_id\":\"" + tenantId + "\"}");
  }

  private static String createTenant() {
    Response response =
        installation(createTenantRequest("installation test " + Ids.newId())).send();
    assertThat(response.status()).as(response.text()).isEqualTo(200);
    return response.json().get("tenant_id").stringValue();
  }

  private static String startImport(String tenantId) {
    Response response = installation(importRequest(tenantId, demoBundle())).send();
    assertThat(response.status()).as(response.text()).isEqualTo(202);
    return response.json().get("seed_import_id").stringValue();
  }

  /** Polls AR-I-97 every 100 ms until the import is not running, at most 60 s. */
  private static JsonNode awaitImport(String seedImportId) {
    long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
    while (true) {
      Response response = installation(seedImportRequest(seedImportId)).send();
      assertThat(response.status()).isEqualTo(200);
      JsonNode body = response.json();
      if (!body.get("state").stringValue().equals("running")) {
        return body;
      }
      assertThat(System.nanoTime()).as("import finished within 60 s").isLessThan(deadline);
      sleep(Duration.ofMillis(100));
    }
  }

  /** A new tenant with the demo bundle imported: {@code ready}. */
  private static String readyTenant() {
    String tenant = createTenant();
    assertThat(awaitImport(startImport(tenant)).get("state").stringValue()).isEqualTo("succeeded");
    return tenant;
  }

  private static void sleep(Duration duration) {
    try {
      Thread.sleep(duration);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private static void assertError(Response response, int status, String code) {
    assertThat(response.status()).as(response.text()).isEqualTo(status);
    assertThat(response.json().get("error").get("code").stringValue()).isEqualTo(code);
  }

  private static String message(Response response) {
    return response.json().get("error").get("message").stringValue();
  }

  private static String host() {
    return "h" + Ids.newId().toString().substring(24) + ".test";
  }

  @Test
  @DisplayName("AR-I-95 POST /api/v1/installation/tenants creates a provisioning tenant")
  void createTenantOperation() {
    Response created = installation(createTenantRequest("jazz-demo 2026-10-19T10:00:00Z")).send();
    assertThat(created.status()).isEqualTo(200);
    assertThat(created.json().propertyNames()).containsExactly("tenant_id");
    TenantView tenant =
        tenants
            .tenant(new TenantId(UUID.fromString(created.json().get("tenant_id").stringValue())))
            .orElseThrow();
    assertThat(tenant.state()).isEqualTo(TenantState.PROVISIONING);
    assertThat(tenant.createdBy()).isEqualTo(Actor.module("vigil-setup"));
    assertThat(tenant.name()).isEqualTo("jazz-demo 2026-10-19T10:00:00Z");
    for (String body : List.of("{}", "{\"name\":3}", "[]")) {
      assertError(
          installation(client.post("/api/v1/installation/tenants").host("nowhere.test").json(body))
              .send(),
          400,
          "bad_request");
    }
    Response tooLong = installation(createTenantRequest("n".repeat(101))).send();
    assertError(tooLong, 400, "bad_request");
    assertThat(message(tooLong)).isEqualTo("name: 1 to 100 characters");
  }

  @Test
  @DisplayName("AR-I-96 POST /api/v1/installation/tenants/{tenant_id}/seed-imports")
  void startSeedImportOperation() throws IOException {
    String tenant = createTenant();
    Response started = installation(importRequest(tenant, demoBundle())).send();
    assertThat(started.status()).isEqualTo(202);
    assertThat(started.json().propertyNames()).containsExactly("seed_import_id");

    MultipartBody other = SeedBundles.form("other", "bundle.zip", demoBundle());
    assertError(
        installation(
                client
                    .post("/api/v1/installation/tenants/" + createTenant() + "/seed-imports")
                    .host("nowhere.test")
                    .header("Content-Type", other.contentType())
                    .body(other.content()))
            .send(),
        400,
        "bad_request");
    assertError(
        installation(
                client
                    .post("/api/v1/installation/tenants/" + createTenant() + "/seed-imports")
                    .host("nowhere.test")
                    .header("Content-Type", "application/zip")
                    .body(demoBundle()))
            .send(),
        400,
        "bad_request");

    List<Map.Entry<String, byte[]>> unreplaced = new ArrayList<>();
    unreplaced.add(demoEntries().getFirst());
    for (Map.Entry<String, byte[]> entry : demoEntries().subList(1, 10)) {
      unreplaced.add(Map.entry(entry.getKey(), Files.readAllBytes(DATA.resolve(entry.getKey()))));
    }
    Response placeholders =
        installation(importRequest(createTenant(), SeedBundles.zip(unreplaced))).send();
    assertError(placeholders, 422, "invalid");
    assertThat(message(placeholders))
        .startsWith("The bundle still contains placeholders: demo_domain, ")
        .endsWith(", mailbox.dbops-lead.");

    List<Map.Entry<String, byte[]>> missing =
        demoEntries().stream().filter(e -> !e.getKey().equals("08-runbooks.md")).toList();
    Response missingRunbooks =
        installation(importRequest(createTenant(), SeedBundles.zip(missing))).send();
    assertError(missingRunbooks, 422, "invalid");
    assertThat(message(missingRunbooks))
        .isEqualTo("The bundle cannot be read: entry 08-runbooks.md is missing.");

    assertError(
        installation(importRequest(UUID.randomUUID().toString(), demoBundle())).send(),
        404,
        "not_found");
    assertError(installation(importRequest("x", demoBundle())).send(), 400, "bad_request");

    Response second = installation(importRequest(tenant, demoBundle())).send();
    assertError(second, 422, "refused");
    assertThat(message(second)).isEqualTo("A seed bundle can be imported only into a new tenant.");
    assertThat(second.json().get("error").get("details").get("refusal").stringValue())
        .isEqualTo("tenant_not_new");
  }

  @Test
  @DisplayName("AR-I-97 GET /api/v1/installation/seed-imports/{seed_import_id}")
  void seedImportOperation() {
    String tenant = createTenant();
    String id = startImport(tenant);
    JsonNode body = awaitImport(id);
    assertThat(body.get("state").stringValue()).isEqualTo("succeeded");
    assertThat(body.propertyNames())
        .containsExactlyInAnyOrder(
            "seed_import_id",
            "tenant_id",
            "bundle_name",
            "bundle_version",
            "state",
            "started_at",
            "finished_at",
            "parts");
    assertThat(body.get("seed_import_id").stringValue()).isEqualTo(id);
    assertThat(body.get("tenant_id").stringValue()).isEqualTo(tenant);
    assertThat(body.get("bundle_name").stringValue()).isEqualTo("jazz-demo");
    assertThat(body.get("parts")).hasSize(17);
    assertThat(body.get("parts").get(0).propertyNames())
        .containsExactlyInAnyOrder("module", "outcome", "counts", "error");
    assertThat(body.get("parts").get(0).get("module").stringValue()).isEqualTo("vigil-tenancy");
    assertThat(body.get("started_at").stringValue()).matches("\\d{4}-\\d\\d-\\d\\dT[0-9:.]{12}Z");
    assertError(
        installation(seedImportRequest(UUID.randomUUID().toString())).send(), 404, "not_found");
    assertError(installation(seedImportRequest("x")).send(), 400, "bad_request");
  }

  @Test
  @DisplayName(
      "AR-I-98 PUT /api/v1/installation/aliases/{host_name} sets an alias to a ready tenant")
  void setAliasOperation() {
    String ready = readyTenant();
    Response set = installation(aliasRequest("m1.test", ready)).send();
    assertThat(set.status()).as(set.text()).isEqualTo(200);
    assertThat(set.json().get("host_name").stringValue()).isEqualTo("m1.test");
    assertThat(set.json().get("revision").asInt()).isEqualTo(1);
    assertThat(set.json().get("tenant_id").stringValue()).isEqualTo(ready);
    assertThat(set.json().has("set_at")).isTrue();
    Response branding = client.get("/api/v1/public/branding").host("m1.test").send();
    assertThat(branding.status()).isEqualTo(200);
    assertThat(branding.json().get("customer_name").stringValue()).isEqualTo("Jazz");

    Response provisioning = installation(aliasRequest(host(), createTenant())).send();
    assertError(provisioning, 422, "refused");
    assertThat(message(provisioning)).isEqualTo("The tenant is not ready.");
    assertThat(provisioning.json().get("error").get("details").get("refusal").stringValue())
        .isEqualTo("tenant_not_ready");
    assertError(
        installation(aliasRequest(host(), UUID.randomUUID().toString())).send(), 404, "not_found");
    assertError(installation(aliasRequest("a_b", ready)).send(), 400, "bad_request");
    assertError(installation(aliasRequest(host(), "x")).send(), 400, "bad_request");
  }

  @Test
  @DisplayName("AR-I-03 a file over 10 MiB answers 413 too_large")
  void tooLarge() {
    Response response = installation(importRequest(createTenant(), new byte[10_485_761])).send();
    assertError(response, 413, "too_large");
  }

  @Test
  @DisplayName(
      "AR-I-03 a malformed multipart upload answers 400 bad_request before the token check")
  void malformedMultipart() {
    Response response =
        client
            .post("/api/v1/installation/tenants/" + createTenant() + "/seed-imports")
            .host("nowhere.test")
            .header("Content-Type", "multipart/form-data")
            .body("garbage".getBytes(StandardCharsets.UTF_8))
            .send();
    assertError(response, 400, "bad_request");
  }

  @Test
  @DisplayName("AR-I-01 vigil-api reads and writes JSON only")
  void jsonOnly() {
    Response yamlBody =
        installation(
                client
                    .post("/api/v1/installation/tenants")
                    .host("nowhere.test")
                    .header("Content-Type", "application/yaml")
                    .body("name: x".getBytes(StandardCharsets.UTF_8)))
            .send();
    assertError(yamlBody, 400, "bad_request");
    Response yamlAnswer =
        installation(createTenantRequest("json only " + Ids.newId()))
            .header("Accept", "application/yaml")
            .send();
    assertError(yamlAnswer, 400, "bad_request");
    assertThat(yamlAnswer.header("Content-Type"))
        .hasValueSatisfying(type -> assertThat(type).startsWith("application/json"));
  }

  /** The four operations, each with a request whose token case is under test. */
  private static List<Function<String, Request>> operations(String readyTenant) {
    String seedImport = startImport(createTenant());
    return List.of(
        auth -> header(createTenantRequest("token test " + Ids.newId()), auth),
        auth -> header(importRequest(createTenant(), demoBundle()), auth),
        auth -> header(seedImportRequest(seedImport), auth),
        auth -> header(aliasRequest(host(), readyTenant), auth));
  }

  private static Request header(Request request, String authorization) {
    return authorization == null ? request : request.header("Authorization", authorization);
  }

  @Test
  @DisplayName(
      "AR-S-07 the installation operations accept only the installation token, on any host")
  void installationToken() {
    String ready = readyTenant();
    List<Function<String, Request>> operations = operations(ready);
    String another = TestSecrets.randomToken();
    for (Function<String, Request> operation : operations) {
      assertError(operation.apply(null).send(), 401, "not_signed_in");
      assertError(operation.apply("Basic " + token()).send(), 401, "not_signed_in");
      assertError(operation.apply("Bearer ").send(), 401, "not_signed_in");
      assertError(operation.apply("Bearer  " + token()).send(), 401, "not_signed_in");
      Response wrong = operation.apply("Bearer " + another).send();
      assertError(wrong, 401, "bad_token");
      assertThat(message(wrong)).isEqualTo("The token is not valid.");
      Response accepted = operation.apply("bearer " + token()).send();
      assertThat(accepted.status()).as(accepted.text()).isIn(200, 202);
    }
    for (String host : List.of("m2.test", "nowhere.test", "127.0.0.1")) {
      Response created =
          installation(
                  client
                      .post("/api/v1/installation/tenants")
                      .host(host)
                      .json("{\"name\":\"host test\"}"))
              .send();
      assertThat(created.status()).as(host).isEqualTo(200);
    }
    String alias = host();
    assertThat(installation(aliasRequest(alias, ready)).send().status()).isEqualTo(200);
    String setBy =
        database
            .postgres()
            .sql("SELECT set_by FROM tenancy.tenant_alias WHERE host_name = :host")
            .param("host", alias)
            .query(String.class)
            .single();
    assertThat(setBy).isEqualTo("installation token");

    Response login = client.login(alias, "admin", secrets.value("seed-password-admin"));
    assertThat(login.status()).isEqualTo(200);
    for (Function<String, Request> operation : operations) {
      Response withCookie =
          operation
              .apply(null)
              .cookie("vigil_session", login.cookieValue("vigil_session"))
              .cookie("vigil_csrf", login.cookieValue("vigil_csrf"))
              .header("X-Vigil-CSRF", login.cookieValue("vigil_csrf"))
              .send();
      assertThat(withCookie.status()).isEqualTo(401);
    }
  }

  @Test
  @DisplayName("PM-M23-06 the installation token grants nothing but the installation operations")
  void tokenGrantsNothingElse() {
    String alias = host();
    assertThat(installation(aliasRequest(alias, readyTenant())).send().status()).isEqualTo(200);
    assertError(
        installation(client.get("/api/v1/session").host(alias)).send(), 401, "not_signed_in");
    assertError(
        installation(client.get("/api/v1/tenant").host(alias)).send(), 401, "not_signed_in");
  }

  /** Every row of tenant A in the tables of AR-B-02, as JSON text, in id order. */
  private static Map<String, List<String>> rowsOf(String tenant) {
    Map<String, List<String>> rows = new LinkedHashMap<>();
    for (String table :
        List.of(
            "tenancy.tenant_settings",
            "identity.user",
            "identity.team",
            "identity.team_membership",
            "identity.permission_grant",
            "identity.login_session",
            "connectors.credential_reference",
            "connectors.connector_instance",
            "setup.seed_import")) {
      rows.put(
          table,
          database
              .postgres()
              .sql(
                  "SELECT to_jsonb(t)::text FROM "
                      + table
                      + " t WHERE tenant_id = :tenantId ORDER BY id")
              .param("tenantId", UUID.fromString(tenant))
              .query(String.class)
              .list());
    }
    return rows;
  }

  /** A reset as AR-B-03 branch B does it, then the alias moves to the new tenant. */
  private record Reset(String tenantA, String tenantB, Map<String, List<String>> before) {}

  private static Reset reset(String hostName) {
    String tenantA = readyTenant();
    assertThat(installation(aliasRequest(hostName, tenantA)).send().status()).isEqualTo(200);
    Map<String, List<String>> before = rowsOf(tenantA);
    String tenantB = readyTenant();
    assertThat(installation(aliasRequest(hostName, tenantB)).send().status()).isEqualTo(200);
    return new Reset(tenantA, tenantB, before);
  }

  @Test
  @DisplayName("SL-A3-03 a reset creates a tenant, imports the bundle and moves the alias")
  void resetOnTheVigilSide() {
    Reset reset = reset("m3.test");
    Response branding = client.get("/api/v1/public/branding").host("m3.test").send();
    assertThat(branding.status()).isEqualTo(200);
    assertThat(branding.json().get("customer_name").stringValue()).isEqualTo("Jazz");
    assertThat(tenants.isActive(new TenantId(UUID.fromString(reset.tenantA())))).isFalse();
    assertThat(tenants.isActive(new TenantId(UUID.fromString(reset.tenantB())))).isTrue();
  }

  @Test
  @DisplayName("AR-B-02 a reset leaves the records of the earlier tenant unchanged")
  void earlierTenantUnchanged() {
    Reset reset = reset(host());
    Map<String, List<String>> after = rowsOf(reset.tenantA());
    assertThat(reset.before().get("tenancy.tenant_settings")).hasSize(1);
    assertThat(reset.before().get("connectors.connector_instance")).hasSize(11);
    assertThat(reset.before().get("setup.seed_import")).hasSize(1);
    assertThat(after).isEqualTo(reset.before());
  }

  @Test
  @DisplayName("AR-S-11 no installation token or seed password in a log line")
  void noSecretsInLogs(CapturedOutput output) {
    String tenant = readyTenant();
    assertThat(installation(aliasRequest(host(), tenant)).send().status()).isEqualTo(200);
    List<String> expected =
        List.of(
            "installation operation PUT /api/v1/installation/aliases/{host_name}: 200",
            "installation operation POST /api/v1/installation/tenants: 200");
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    List<String> messages = List.of();
    while (System.nanoTime() < deadline) {
      messages =
          LogLines.application(output.getOut()).stream()
              .map(line -> line.path("message").asString(""))
              .toList();
      if (messages.containsAll(expected)) {
        break;
      }
      sleep(Duration.ofMillis(100));
    }
    assertThat(messages).containsAll(expected);
    List<String> values = new ArrayList<>();
    values.add(token());
    USERS.forEach(user -> values.add(secrets.value("seed-password-" + user)));
    LogLines.assertNoneContains(output.getOut(), values);
  }
}
