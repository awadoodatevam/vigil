package vigil;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.client.RestTestClient;
import vigil.identity.api.Permission;
import vigil.testsupport.ApiClient;
import vigil.testsupport.TestTenants;
import vigil.testsupport.VigilDatabase;
import vigil.testsupport.VigilTestApp;

class WebAppServingIT {

  private static VigilTestApp app;
  private static RestTestClient client;
  private static String indexHtml;
  private static ApiClient api;
  private static TestTenants.Tenant tenant;

  @BeforeAll
  static void start() throws IOException, InterruptedException {
    app = VigilTestApp.start(VigilDatabase.fresh());
    client = RestTestClient.bindToServer().baseUrl(app.baseUrl()).build();
    indexHtml =
        new ClassPathResource("static/index.html").getContentAsString(StandardCharsets.UTF_8);
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (client.get().uri("/").exchange().returnResult(String.class).getStatus().value() == 503) {
      assertThat(System.nanoTime()).as("ready within 30 s").isLessThan(deadline);
      Thread.sleep(200);
    }
    app.awaitStarted();
    api = new ApiClient(app);
    tenant =
        new TestTenants(app)
            .tenant("Serving")
            .host("serving.test")
            .user("viewer", "Viewer", Permission.VIEW)
            .create();
  }

  @AfterAll
  static void stop() {
    app.close();
  }

  @Test
  @DisplayName("AR-W-01 unknown paths answer index.html without caching")
  void unknownPathsAnswerIndexHtmlWithoutCaching() {
    for (String path : new String[] {"/incidents/0190a1b2-0000-7000-8000-000000000000", "/"}) {
      client
          .get()
          .uri(path)
          .exchange()
          .expectStatus()
          .isOk()
          .expectHeader()
          .valueEquals("Cache-Control", "no-cache")
          .expectHeader()
          .contentTypeCompatibleWith("text/html")
          .expectBody(String.class)
          .isEqualTo(indexHtml);
    }
    client
        .head()
        .uri("/incidents")
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .valueEquals("Cache-Control", "no-cache");
    client
        .get()
        .uri("/index.html")
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .valueEquals("Cache-Control", "no-cache");
  }

  @Test
  @DisplayName("AR-W-01 api and actuator paths do not fall back")
  void apiAndActuatorPathsDoNotFallBack() {
    for (String path : new String[] {"/api/v1/anything", "/actuator/anything"}) {
      client
          .get()
          .uri(path)
          .exchange()
          .expectStatus()
          .isNotFound()
          .expectBody(String.class)
          .value(body -> assertThat(body).isNotEqualTo(indexHtml));
    }
  }

  @Test
  @DisplayName("AR-W-01 hashed assets are cached for a year")
  void hashedAssetsAreCachedForAYear() {
    Matcher matcher = Pattern.compile("/assets/[^\"']+\\.js").matcher(indexHtml);
    assertThat(matcher.find()).as("a script under /assets/ in index.html").isTrue();
    client
        .get()
        .uri(matcher.group())
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .valueEquals("Cache-Control", "max-age=31536000, immutable");
    client.get().uri("/assets/missing.js").exchange().expectStatus().isNotFound();
  }

  /** The first path of index.html that matches {@code /assets/…{extension}}. */
  private static String asset(String extension) {
    Matcher matcher = Pattern.compile("/assets/[^\"']+\\" + extension).matcher(indexHtml);
    assertThat(matcher.find()).as("a %s file under /assets/ in index.html", extension).isTrue();
    return matcher.group();
  }

  private static byte[] gunzip(byte[] body) throws IOException {
    try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(body))) {
      return in.readAllBytes();
    }
  }

  @Test
  @DisplayName("AR-W-01 built files are sent gzip-compressed")
  void builtFilesAreSentGzipCompressed() throws IOException {
    for (String path : new String[] {asset(".js"), asset(".css")}) {
      byte[] file = new ClassPathResource("static" + path).getContentAsByteArray();
      ApiClient.Response zipped = api.get(path).header("Accept-Encoding", "gzip").send();
      assertThat(zipped.status()).as(path).isEqualTo(200);
      assertThat(zipped.header("Content-Encoding")).as(path).contains("gzip");
      assertThat(zipped.header("Cache-Control")).as(path).contains("max-age=31536000, immutable");
      assertThat(zipped.body().length).as(path).isLessThan(file.length);
      assertThat(gunzip(zipped.body())).as(path).isEqualTo(file);

      ApiClient.Response plain = api.get(path).send();
      assertThat(plain.status()).as(path).isEqualTo(200);
      assertThat(plain.header("Content-Encoding")).as(path).isEmpty();
      assertThat(plain.header("Cache-Control")).as(path).contains("max-age=31536000, immutable");
      assertThat(plain.body()).as(path).isEqualTo(file);
    }
  }

  @Test
  @DisplayName("AR-W-01 no other answer is compressed")
  void noOtherAnswerIsCompressed() {
    ApiClient.Response branding =
        api.get("/api/v1/public/branding")
            .host("serving.test")
            .header("Accept-Encoding", "gzip")
            .send();
    assertThat(branding.status()).isEqualTo(200);
    assertThat(branding.header("Content-Type").orElseThrow()).startsWith("application/json");
    assertThat(branding.header("Content-Encoding")).isEmpty();
  }

  @Test
  @DisplayName("AR-I-04 the live stream is not compressed")
  void theLiveStreamIsNotCompressed() {
    String session =
        api.login("serving.test", "viewer", tenant.password("viewer")).cookieValue("vigil_session");
    try (ApiClient.Stream stream =
        api.get("/api/v1/live?topics=tenant")
            .host("serving.test")
            .cookie("vigil_session", session)
            .header("Accept-Encoding", "gzip")
            .open()) {
      assertThat(stream.status()).isEqualTo(200);
      assertThat(stream.headers().firstValue("Content-Type").orElseThrow())
          .startsWith("text/event-stream");
      assertThat(stream.headers().firstValue("Content-Encoding")).isEmpty();
    }
  }
}
