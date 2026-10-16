package vigil.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.context.ApplicationContext;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vigil.identity.api.Permission;
import vigil.testsupport.ApiClient;
import vigil.testsupport.ApiClient.Request;
import vigil.testsupport.ApiClient.Response;
import vigil.testsupport.TestTenants;
import vigil.testsupport.VigilDatabase;
import vigil.testsupport.VigilTestApp;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SessionIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String WRONG_LOGIN = "Username or password is incorrect.";
  private static final String FORBIDDEN = "You do not have permission to do this.";

  /** Every answer of this class, for the JSESSIONID check of AR-T-11. */
  private static final List<Response> ANSWERS = new CopyOnWriteArrayList<>();

  private static VigilDatabase database;
  private static VigilTestApp app;
  private static ApiClient client;
  private static TestTenants.Tenant tenantA;
  private static byte[] pngLogo;
  private static byte[] svgLogo;

  @BeforeAll
  static void start() {
    database = VigilDatabase.fresh();
    app = VigilTestApp.start(database);
    app.awaitStarted();
    client = new ApiClient(app);
    pngLogo = new byte[300];
    new SecureRandom().nextBytes(pngLogo);
    System.arraycopy(
        new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'}, 0, pngLogo, 0, 8);
    svgLogo = "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes(StandardCharsets.UTF_8);
    TestTenants tenants = new TestTenants(app);
    tenantA = tenantA(tenants);
    tenants.tenant("Tenant B").host("b.test").logo(svgLogo, "image/svg+xml").create();
    tenants.tenant("Tenant C").host("c.test").create();
  }

  /** Tenant A on a.test with a PNG logo, users alice and inactive, and three teams of alice. */
  private static TestTenants.Tenant tenantA(TestTenants tenants) {
    return tenants
        .tenant("Tenant A")
        .host("a.test")
        .settings("Customer A", "Product A", "Label A")
        .logo(pngLogo, "image/png")
        .team("zeta", "Zeta")
        .team("Alpha", "Alpha")
        .team("beta", "Beta")
        .user(
            "alice",
            "Alice Example",
            Permission.PUBLISH_WORKFLOW,
            Permission.APPROVE_ACTION,
            Permission.VIEW)
        .user("inactive", "Inactive User", Permission.VIEW)
        .member("zeta", "alice")
        .member("Alpha", "alice")
        .member("beta", "alice")
        .create();
  }

  @AfterAll
  static void stop() {
    app.close();
  }

  private static Response send(Request request) {
    Response response = request.send();
    ANSWERS.add(response);
    return response;
  }

  private static Response login(ApiClient on, String username, String password) {
    Response response = on.login("a.test", username, password);
    ANSWERS.add(response);
    return response;
  }

  /** A logged-in session of alice: the two cookie values. */
  private record Session(String token, String csrf) {}

  private static Session aliceSession() {
    Response response = login(client, "alice", tenantA.password("alice"));
    assertThat(response.status()).isEqualTo(200);
    return new Session(response.cookieValue("vigil_session"), response.cookieValue("vigil_csrf"));
  }

  private static Request withSession(Request request, Session session) {
    return request
        .host("a.test")
        .cookie("vigil_session", session.token())
        .cookie("vigil_csrf", session.csrf());
  }

  private static void assertError(Response response, int status, String code, String message) {
    assertThat(response.status()).as(response.text()).isEqualTo(status);
    JsonNode error = response.json().get("error");
    assertThat(error.get("code").stringValue()).isEqualTo(code);
    if (message != null) {
      assertThat(error.get("message").stringValue()).isEqualTo(message);
    }
  }

  /** The attributes of a Set-Cookie value after the first pair, in lower case, with values. */
  private static Map<String, String> attributes(String setCookie) {
    return Arrays.stream(setCookie.split(";"))
        .skip(1)
        .map(String::trim)
        .collect(
            Collectors.toMap(
                part -> part.split("=", 2)[0].toLowerCase(Locale.ROOT),
                part -> part.contains("=") ? part.split("=", 2)[1] : ""));
  }

  private static String sessionState(String token) {
    return database
        .postgres()
        .sql(
            "SELECT state || ' ' || coalesce(end_reason, '-') FROM identity.login_session WHERE token_hash = ?")
        .param(tokenHash(token))
        .query(String.class)
        .single();
  }

  private static String tokenHash(String token) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(Base64.getUrlDecoder().decode(token)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static final String ALICE_JSON =
      "{\"username\":\"alice\",\"display_name\":\"Alice Example\","
          + "\"permissions\":[\"view\",\"approve_action\",\"publish_workflow\"],"
          + "\"team_keys\":[\"Alpha\",\"beta\",\"zeta\"]}";

  @Test
  @Order(1)
  @DisplayName("AR-I-01 answers are JSON in UTF-8 with snake case fields")
  void jsonAnswers() {
    Response response = login(client, "alice", tenantA.password("alice"));
    String contentType = response.header("Content-Type").orElseThrow().toLowerCase(Locale.ROOT);
    assertThat(contentType).startsWith("application/json");
    assertThat(contentType.equals("application/json") || contentType.contains("charset=utf-8"))
        .as(contentType)
        .isTrue();
    String text = new String(response.body(), StandardCharsets.UTF_8);
    assertThat(text.getBytes(StandardCharsets.UTF_8)).isEqualTo(response.body());
    List<String> keys = new ArrayList<>(JSON.readTree(text).propertyNames());
    assertThat(keys).containsExactly("username", "display_name", "permissions", "team_keys");
  }

  @Test
  @Order(2)
  @DisplayName("AR-I-12 login answers the user and sets the session and CSRF cookies")
  void login() {
    Response right = login(client, "alice", tenantA.password("alice"));
    assertThat(right.status()).isEqualTo(200);
    assertThat(right.json()).isEqualTo(JSON.readTree(ALICE_JSON));
    assertThat(right.setCookies("vigil_session")).hasSize(1);
    assertThat(right.setCookies("vigil_csrf")).hasSize(1);
    assertThat(right.setCookies()).hasSize(2);

    database
        .postgres()
        .sql("UPDATE identity.\"user\" SET active = false WHERE tenant_id = ? AND username = ?")
        .param(tenantA.id().value())
        .param("inactive")
        .update();
    for (Response refused :
        List.of(
            login(client, "alice", tenantA.password("alice") + "x"),
            login(client, "nobody", tenantA.password("alice")),
            login(client, "not a key!", tenantA.password("alice")),
            login(client, "inactive", tenantA.password("inactive")))) {
      assertError(refused, 401, "not_signed_in", WRONG_LOGIN);
      assertThat(refused.setCookies()).isEmpty();
    }
    assertError(
        send(client.post("/api/v1/session").host("a.test").json("{")), 400, "bad_request", null);
    assertError(
        send(client.post("/api/v1/session").host("a.test").json("{\"username\":\"alice\"}")),
        400,
        "bad_request",
        null);
  }

  @Test
  @Order(3)
  @DisplayName("AR-S-02 the vigil_session cookie and its attributes")
  void sessionCookie() {
    Response response = login(client, "alice", tenantA.password("alice"));
    String setCookie = response.setCookies("vigil_session").getFirst();
    assertThat(response.cookieValue("vigil_session")).matches("[A-Za-z0-9_-]{43}");
    Map<String, String> attributes = attributes(setCookie);
    assertThat(attributes).containsEntry("path", "/").containsEntry("httponly", "");
    assertThat(attributes).containsEntry("samesite", "Lax").containsEntry("secure", "");
    assertThat(attributes).doesNotContainKeys("max-age", "expires");
  }

  @Test
  @Order(4)
  @DisplayName("PM-M23-05 the Secure attribute follows vigil.security.secure-cookies")
  void secureCookies() {
    Response secure = login(client, "alice", tenantA.password("alice"));
    assertThat(attributes(secure.setCookies("vigil_session").getFirst())).containsKey("secure");
    assertThat(attributes(secure.setCookies("vigil_csrf").getFirst())).containsKey("secure");

    try (VigilTestApp plain =
        VigilTestApp.start(
            VigilDatabase.fresh(), List.of("--vigil.security.secure-cookies=false"))) {
      plain.awaitStarted();
      TestTenants.Tenant again = tenantA(new TestTenants(plain));
      ApiClient plainClient = new ApiClient(plain);
      Response response = login(plainClient, "alice", again.password("alice"));
      assertThat(response.status()).isEqualTo(200);
      for (String name : List.of("vigil_session", "vigil_csrf")) {
        Map<String, String> withSecure = attributes(secure.setCookies(name).getFirst());
        withSecure.remove("secure");
        assertThat(attributes(response.setCookies(name).getFirst())).isEqualTo(withSecure);
      }
    }
  }

  @Test
  @Order(5)
  @DisplayName("AR-I-13 the current user of the session")
  void currentUser() {
    Session session = aliceSession();
    Response response = send(withSession(client.get("/api/v1/session"), session));
    assertThat(response.status()).isEqualTo(200);
    assertThat(response.json()).isEqualTo(JSON.readTree(ALICE_JSON));
  }

  @Test
  @Order(6)
  @DisplayName("AR-I-14 logout ends the session and clears both cookies")
  void logout() {
    Session session = aliceSession();
    Response response =
        send(
            withSession(client.delete("/api/v1/session"), session)
                .header("X-Vigil-CSRF", session.csrf()));
    assertThat(response.status()).isEqualTo(204);
    assertThat(response.body()).isEmpty();
    for (String name : List.of("vigil_session", "vigil_csrf")) {
      assertThat(response.setCookies(name)).hasSize(1);
      assertThat(response.cookieValue(name)).isEmpty();
    }
    assertThat(attributes(response.setCookies("vigil_session").getFirst()))
        .containsEntry("max-age", "0");
    // Tomcat writes a servlet cookie with max age 0 as an Expires in the past, without Max-Age.
    Map<String, String> csrfCleared = attributes(response.setCookies("vigil_csrf").getFirst());
    assertThat(
            "0".equals(csrfCleared.get("max-age"))
                || ZonedDateTime.parse(
                        csrfCleared.get("expires"), DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant()
                    .isBefore(Instant.now()))
        .as("vigil_csrf cleared: %s", csrfCleared)
        .isTrue();
    assertThat(sessionState(session.token())).isEqualTo("ended logout");
    assertError(
        send(client.get("/api/v1/session").host("a.test").cookie("vigil_session", session.token())),
        401,
        "session_ended",
        "Your session ended. Sign in again.");
  }

  @Test
  @Order(7)
  @DisplayName("AR-S-03 state-changing requests with a session carry the CSRF header")
  void csrf() {
    Response login = login(client, "alice", tenantA.password("alice"));
    Session session =
        new Session(login.cookieValue("vigil_session"), login.cookieValue("vigil_csrf"));
    assertThat(UUID.fromString(session.csrf()).toString()).isEqualTo(session.csrf());
    Map<String, String> csrfCookie = attributes(login.setCookies("vigil_csrf").getFirst());
    assertThat(csrfCookie).containsEntry("path", "/").containsEntry("samesite", "Strict");
    assertThat(csrfCookie).containsKey("secure").doesNotContainKey("httponly");

    for (Request refused :
        List.of(
            withSession(client.delete("/api/v1/session"), session),
            withSession(client.delete("/api/v1/session"), session)
                .header("X-Vigil-CSRF", UUID.randomUUID().toString()),
            withSession(client.delete("/api/v1/session?_csrf=" + session.csrf()), session))) {
      assertError(send(refused), 403, "forbidden", FORBIDDEN);
      assertThat(sessionState(session.token())).isEqualTo("active -");
    }
    assertThat(send(withSession(client.get("/api/v1/session"), session)).setCookies()).isEmpty();
    assertError(
        send(
            client
                .post("/api/v1/webhooks/zabbix")
                .host("a.test")
                .cookie("vigil_session", session.token())),
        404,
        "not_found",
        null);
    assertError(send(client.delete("/api/v1/session").host("a.test")), 401, "not_signed_in", null);
    Response again =
        send(
            withSession(client.post("/api/v1/session"), session)
                .json(
                    "{\"username\":\"alice\",\"password\":\"" + tenantA.password("alice") + "\"}"));
    assertThat(again.status()).isEqualTo(200);
    assertThat(again.setCookies("vigil_csrf")).hasSize(1);
  }

  @Test
  @Order(8)
  @DisplayName("AR-I-15 the tenant settings for the banner and header")
  void tenantSettings() {
    Response response = send(withSession(client.get("/api/v1/tenant"), aliceSession()));
    assertThat(response.status()).isEqualTo(200);
    assertThat(response.json())
        .isEqualTo(
            JSON.readTree(
                "{\"product_display_name\":\"Product A\",\"customer_name\":\"Customer A\","
                    + "\"has_logo\":true,\"environment_label\":\"Label A\"}"));
  }

  @Test
  @Order(9)
  @DisplayName("AR-I-11 the logo of the host's tenant")
  void logo() {
    Response png = send(client.get("/api/v1/public/logo").host("a.test"));
    assertThat(png.status()).isEqualTo(200);
    assertThat(png.body()).isEqualTo(pngLogo);
    assertThat(png.header("Content-Type")).hasValue("image/png");
    assertThat(png.header("Cache-Control")).hasValue("no-cache");
    Response svg = send(client.get("/api/v1/public/logo").host("b.test"));
    assertThat(svg.status()).isEqualTo(200);
    assertThat(svg.body()).isEqualTo(svgLogo);
    assertThat(svg.header("Content-Type")).hasValue("image/svg+xml");
    assertError(
        send(client.get("/api/v1/public/logo").host("c.test")), 404, "not_found", "Not found.");
  }

  @Test
  @Order(10)
  @DisplayName("BP-A-20 wrong methods, TRACE and methods the firewall refuses")
  void methods() {
    Session session = aliceSession();
    assertError(
        send(
            withSession(client.request("PUT", "/api/v1/tenant"), session)
                .header("X-Vigil-CSRF", session.csrf())),
        404,
        "not_found",
        "Not found.");
    assertThat(send(client.request("TRACE", "/api/v1/session").host("a.test")).status())
        .isEqualTo(405);
    assertError(
        send(client.request("PROPFIND", "/api/v1/session").host("a.test")),
        400,
        "bad_request",
        "The request is not valid.");
    assertError(
        send(client.request("PROPFIND", "/api/v1/session").host("nowhere.test")),
        404,
        "unknown_host",
        "This address is not set up.");
    assertError(
        send(withSession(client.get("/api/v1/no-such-thing"), session)),
        404,
        "not_found",
        "Not found.");
  }

  @Test
  @Order(11)
  @DisplayName("AR-T-11 Spring Security with the session filter before CSRF, no HTTP session")
  void filterChain() {
    assertThat(ANSWERS).isNotEmpty();
    for (Response response : ANSWERS) {
      assertThat(response.setCookies()).noneMatch(value -> value.startsWith("JSESSIONID="));
    }
    ApplicationContext context = app.context();
    assertThat(context.getBeanNamesForType(UserDetailsService.class)).isEmpty();
    assertThat(context.getBeanNamesForType(CookieCsrfTokenRepository.class)).hasSize(1);
    List<String> filters =
        context.getBeansOfType(SecurityFilterChain.class).values().stream()
            .flatMap(chain -> chain.getFilters().stream())
            .map(filter -> filter.getClass().getName())
            .toList();
    int csrf = filters.indexOf(CsrfFilter.class.getName());
    assertThat(csrf).isPositive();
    assertThat(filters.get(csrf - 1)).isEqualTo(SessionAuthenticationFilter.class.getName());
  }
}
