package vigil.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vigil.identity.api.Permission;
import vigil.testsupport.ApiClient;
import vigil.testsupport.ApiClient.Response;
import vigil.testsupport.TestTenants;
import vigil.testsupport.VigilDatabase;
import vigil.testsupport.VigilTestApp;

class HostResolutionIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String UNKNOWN_HOST_BODY =
      "{\"error\":{\"code\":\"unknown_host\",\"message\":\"This address is not set up.\"}}";

  private static VigilTestApp app;
  private static ApiClient client;
  private static TestTenants.Tenant tenantA;

  @BeforeAll
  static void start() {
    app = VigilTestApp.start(VigilDatabase.fresh());
    app.awaitStarted();
    client = new ApiClient(app);
    TestTenants tenants = new TestTenants(app);
    tenantA =
        tenants
            .tenant("Tenant A")
            .host("a.test")
            .settings("Customer A", "Product A", "Label A")
            .logo(
                "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes(StandardCharsets.UTF_8),
                "image/svg+xml")
            .user("alice", "Alice", Permission.VIEW)
            .create();
    tenants
        .tenant("Tenant B")
        .host("b.test")
        .settings("Customer B", "Product B", null)
        .user("bob", "Bob", Permission.VIEW)
        .create();
  }

  @AfterAll
  static void stop() {
    app.close();
  }

  private static Response branding(String host) {
    return client.get("/api/v1/public/branding").host(host).send();
  }

  private static String sessionCookie() {
    Response login = client.login("a.test", "alice", tenantA.password("alice"));
    assertThat(login.status()).isEqualTo(200);
    return login.cookieValue("vigil_session");
  }

  private static void assertError(Response response, int status, String code) {
    assertThat(response.status()).as(response.text()).isEqualTo(status);
    assertThat(response.json().get("error").get("code").stringValue()).isEqualTo(code);
  }

  @Test
  @DisplayName("AR-I-02 the tenant of a request is the alias of its host name without port")
  void tenantFromHostName() {
    int port = app.port();
    List<String> customers =
        List.of("a.test:" + port, "a.test", "A.Test:" + port, "b.test").stream()
            .map(host -> branding(host).json().get("customer_name").stringValue())
            .toList();
    assertThat(customers).containsExactly("Customer A", "Customer A", "Customer A", "Customer B");
    for (String host : List.of("nowhere.test", "127.0.0.1:" + port)) {
      Response response = branding(host);
      assertThat(response.status()).isEqualTo(404);
      assertThat(response.text()).isEqualTo(UNKNOWN_HOST_BODY);
    }
    assertError(
        client.get("/api/v1/installation/tenants").host("nowhere.test").send(), 404, "not_found");
    assertThat(client.get("/incidents").host("nowhere.test").send().status()).isEqualTo(200);
    assertThat(client.get("/actuator/health/liveness").host("nowhere.test").send().status())
        .isEqualTo(200);
  }

  @Test
  @DisplayName("AR-B-10 branding on a host with no alias answers unknown_host")
  void unknownHostBranding() {
    Response response = branding("nowhere.test");
    assertThat(response.status()).isEqualTo(404);
    assertThat(response.json()).isEqualTo(JSON.readTree(UNKNOWN_HOST_BODY));
  }

  @Test
  @DisplayName("PM-M23-03 webhook paths need no session and no CSRF header, on the host's tenant")
  void webhookPaths() {
    assertError(client.post("/api/v1/webhooks/zabbix").host("a.test").send(), 404, "not_found");
    assertError(
        client.post("/api/v1/webhooks/zabbix").host("nowhere.test").send(), 404, "unknown_host");
  }

  @Test
  @DisplayName("AR-I-10 branding of the host's tenant")
  void branding() {
    JsonNode a = branding("a.test").json();
    assertThat(a)
        .isEqualTo(
            JSON.readTree(
                "{\"product_display_name\":\"Product A\",\"customer_name\":\"Customer A\","
                    + "\"has_logo\":true,\"environment_label\":\"Label A\"}"));
    JsonNode b = branding("b.test").json();
    assertThat(b)
        .isEqualTo(
            JSON.readTree(
                "{\"product_display_name\":\"Product B\",\"customer_name\":\"Customer B\","
                    + "\"has_logo\":false,\"environment_label\":null}"));
  }

  @Test
  @DisplayName("SL-B-01 a session of one tenant is refused on another tenant's host")
  void sessionOfAnotherTenant() {
    String session = sessionCookie();
    Response other =
        client.get("/api/v1/session").host("b.test").cookie("vigil_session", session).send();
    assertError(other, 401, "session_ended");
    assertThat(other.json().get("error").get("message").stringValue())
        .isEqualTo("Your session ended. Sign in again.");
    Response own =
        client.get("/api/v1/session").host("a.test").cookie("vigil_session", session).send();
    assertThat(own.status()).isEqualTo(200);
    assertThat(own.json().get("username").stringValue()).isEqualTo("alice");
  }

  @Test
  @DisplayName("SL-B-13 only the public operations and login answer without a session")
  void noSession() {
    for (String path :
        List.of(
            "/api/v1/session",
            "/api/v1/tenant",
            "/api/v1/live?topics=tenant",
            "/api/v1/incidents/" + UUID.randomUUID())) {
      assertError(client.get(path).host("a.test").send(), 401, "not_signed_in");
    }
    assertThat(branding("a.test").status()).isEqualTo(200);
    assertThat(client.get("/api/v1/public/logo").host("a.test").send().status()).isEqualTo(200);
  }
}
