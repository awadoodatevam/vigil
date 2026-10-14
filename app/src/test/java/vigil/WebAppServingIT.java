package vigil;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.client.RestTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebAppServingIT {

  @LocalServerPort private int port;

  private RestTestClient client;
  private String indexHtml;

  @BeforeEach
  void setUp() throws IOException {
    client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    indexHtml =
        new ClassPathResource("static/index.html").getContentAsString(StandardCharsets.UTF_8);
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
