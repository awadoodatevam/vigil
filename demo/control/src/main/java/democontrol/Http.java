package democontrol;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** HTTP calls without any proxy of the environment, and their JSON. */
final class Http {

  static final JsonMapper JSON =
      JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
  private static final HttpClient CLIENT =
      HttpClient.newBuilder()
          .proxy(HttpClient.Builder.NO_PROXY)
          .version(HttpClient.Version.HTTP_1_1)
          .connectTimeout(Duration.ofSeconds(5))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  /** An answer: its status and body (raw bytes as text). */
  record Answer(int status, String body) {

    /** The body as JSON, or null when it is not JSON. */
    JsonNode json() {
      if (body == null || body.isEmpty()) {
        return null;
      }
      try {
        return JSON.readTree(body);
      } catch (RuntimeException e) {
        return null;
      }
    }
  }

  private Http() {}

  static HttpRequest.Builder request(String url, Duration timeout) {
    Duration bounded =
        timeout.compareTo(Duration.ofMillis(500)) < 0 ? Duration.ofMillis(500) : timeout;
    return HttpRequest.newBuilder(URI.create(url)).timeout(bounded);
  }

  static HttpRequest.BodyPublisher json(Object body) {
    return HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body));
  }

  /** Sends the request; an IOException means no answer. */
  static Answer send(HttpRequest request) throws IOException {
    try {
      HttpResponse<String> response =
          CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      return new Answer(response.statusCode(), response.body());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("interrupted", e);
    }
  }
}
