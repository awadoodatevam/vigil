package democontrol;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LoginIT {

  static final Path FOLDER;
  static final String PASSWORD = Fixture.random();

  static {
    try {
      FOLDER = Files.createTempDirectory("login-it");
      Files.createDirectories(FOLDER.resolve("secrets"));
      Files.writeString(FOLDER.resolve("secrets").resolve("control-password"), PASSWORD);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("demo-control.secrets", () -> FOLDER.resolve("secrets").toString());
    registry.add("demo-control.state-file", () -> FOLDER.resolve("state.json").toString());
  }

  @LocalServerPort int port;
  @Autowired Panel panel;

  private static final HttpClient CLIENT =
      HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();

  static HttpResponse<String> send(
      int port, String method, String path, String type, String body, String cookie)
      throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body));
    if (type != null) {
      builder.header("Content-Type", type);
    }
    if (cookie != null) {
      builder.header("Cookie", cookie);
    }
    return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  static String credentials(String username, String password) {
    return Http.JSON.writeValueAsString(
        java.util.Map.of("username", username, "password", password));
  }

  private HttpResponse<String> signIn(int port, String username, String password) throws Exception {
    return send(
        port, "POST", "/api/session", "application/json", credentials(username, password), null);
  }

  @Test
  @DisplayName(
      "AR-S-12 operator signs in with control-password; a wrong username or password is refused")
  void signIn() throws Exception {
    String refused = "{\"message\":\"Username or password is incorrect.\"}";
    HttpResponse<String> wrong = signIn(port, "operator", PASSWORD + "x");
    assertThat(wrong.statusCode()).isEqualTo(401);
    assertThat(wrong.body()).isEqualTo(refused);
    assertThat(wrong.headers().firstValue("Set-Cookie")).isEmpty();
    assertThat(signIn(port, "presenter", PASSWORD).body()).isEqualTo(refused);
    assertThat(signIn(port, "Operator", PASSWORD).statusCode()).isEqualTo(401);
    for (String body :
        List.of(
            "{}",
            "[]",
            "{\"username\":\"operator\"}",
            "not json",
            "{\"username\":\"operator\",\"password\":1}",
            "{\"username\":\"operator\",\"password\":\"" + PASSWORD + "\",\"x\":1}")) {
      assertThat(send(port, "POST", "/api/session", "application/json", body, null).statusCode())
          .as(body)
          .isEqualTo(400);
    }
    HttpResponse<String> state = send(port, "GET", "/api/state", null, null, null);
    assertThat(state.statusCode()).isEqualTo(401);
    assertThat(state.body()).isEqualTo("{\"product\":\"Techfinity\"}");
    assertThat(
            send(port, "POST", "/api/buttons/reset", "application/json", "{}", null).statusCode())
        .isEqualTo(401);

    HttpResponse<String> ok = signIn(port, "operator", PASSWORD);
    assertThat(ok.statusCode()).isEqualTo(204);
    String cookie = ok.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];
    state = send(port, "GET", "/api/state", null, null, cookie);
    assertThat(state.statusCode()).isEqualTo(200);
    assertThat(Http.JSON.readTree(state.body()).path("product").asString()).isEqualTo("Techfinity");
    assertThat(
            send(port, "GET", "/api/state", null, null, "control_session=" + "A".repeat(43))
                .statusCode())
        .isEqualTo(401);
    assertThat(
            send(port, "POST", "/api/buttons/other", "application/json", "{}", cookie).statusCode())
        .isEqualTo(404);
  }

  @Test
  @DisplayName("AR-S-12 the session cookie's attributes, Secure by vigil.security.secure-cookies")
  void cookie() throws Exception {
    String header =
        signIn(port, "operator", PASSWORD).headers().firstValue("Set-Cookie").orElseThrow();
    List<String> parts = List.of(header.split(";\\s*"));
    assertThat(parts.getFirst()).matches("control_session=[A-Za-z0-9_-]{43}");
    assertThat(parts.subList(1, parts.size()))
        .containsExactlyInAnyOrder("Path=/", "Secure", "HttpOnly", "SameSite=Strict");
    try (ConfigurableApplicationContext local = start("--vigil.security.secure-cookies=false")) {
      int other = local.getEnvironment().getProperty("local.server.port", Integer.class);
      String plain =
          signIn(other, "operator", PASSWORD).headers().firstValue("Set-Cookie").orElseThrow();
      assertThat(List.of(plain.split(";\\s*")).subList(1, 4))
          .containsExactlyInAnyOrder("Path=/", "HttpOnly", "SameSite=Strict");
      assertThat(plain).doesNotContain("Secure").doesNotContain("Max-Age");
      // a session of one demo-control is not one of another (sessions are kept in memory).
      assertThat(send(other, "GET", "/api/state", null, null, parts.getFirst()).statusCode())
          .isEqualTo(401);
    }
  }

  @Test
  @DisplayName("AR-S-12 a POST without application/json does nothing")
  void postNeedsJson() throws Exception {
    HttpResponse<String> form =
        send(
            port,
            "POST",
            "/api/session",
            "application/x-www-form-urlencoded",
            "username=operator&password=" + PASSWORD,
            null);
    assertThat(form.statusCode()).isEqualTo(415);
    assertThat(form.headers().firstValue("Set-Cookie")).isEmpty();
    assertThat(
            send(
                    port,
                    "POST",
                    "/api/session",
                    "text/plain",
                    credentials("operator", PASSWORD),
                    null)
                .statusCode())
        .isEqualTo(415);
    String cookie =
        signIn(port, "operator", PASSWORD)
            .headers()
            .firstValue("Set-Cookie")
            .orElseThrow()
            .split(";")[0];
    panel.labChecked(null, null);
    panel.vigilChecked(null);
    for (String type : new String[] {"text/plain", "application/x-www-form-urlencoded", null}) {
      assertThat(send(port, "POST", "/api/buttons/cut-cloud-ai", type, "{}", cookie).statusCode())
          .as(String.valueOf(type))
          .isEqualTo(415);
    }
    assertThat(panel.view().get("active")).asList().isEmpty();
    assertThat(panel.refusal(Button.CUT_CLOUD_AI)).isNull();
  }

  @Test
  @DisplayName("AR-U-06 GET /health answers 200 without login")
  void health() throws Exception {
    HttpResponse<String> health = send(port, "GET", "/health", null, null, null);
    assertThat(health.statusCode()).isEqualTo(200);
    assertThat(health.body()).isEmpty();
  }

  @Test
  @DisplayName("SL-B-14 no password, no login")
  void noPassword() throws Exception {
    Path empty = Files.createDirectories(FOLDER.resolve("empty-secrets"));
    try (ConfigurableApplicationContext local = start("--demo-control.secrets=" + empty)) {
      int other = local.getEnvironment().getProperty("local.server.port", Integer.class);
      for (String password : List.of("", PASSWORD, "operator", "password")) {
        assertThat(signIn(other, "operator", password).statusCode()).as(password).isEqualTo(401);
      }
    }
    Files.writeString(empty.resolve("control-password"), "");
    try (ConfigurableApplicationContext local = start("--demo-control.secrets=" + empty)) {
      int other = local.getEnvironment().getProperty("local.server.port", Integer.class);
      assertThat(signIn(other, "operator", "").statusCode()).isEqualTo(401);
    }
  }

  private static ConfigurableApplicationContext start(String... arguments) {
    String[] all = new String[arguments.length + 3];
    all[0] = "--server.port=0";
    all[1] = "--demo-control.state-file=" + FOLDER.resolve("other-state.json");
    all[2] = "--demo-control.secrets=" + FOLDER.resolve("secrets");
    System.arraycopy(arguments, 0, all, 3, arguments.length);
    return new SpringApplicationBuilder(DemoControlApplication.class).run(all);
  }
}
