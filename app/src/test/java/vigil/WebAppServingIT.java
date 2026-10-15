package vigil;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.client.RestTestClient;
import vigil.testsupport.VigilDatabase;
import vigil.testsupport.VigilTestApp;

class WebAppServingIT {

  private static VigilTestApp app;
  private static RestTestClient client;
  private static String indexHtml;

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
}
