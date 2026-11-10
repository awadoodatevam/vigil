package vigil.testsupport;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.extension.ResponseTransformerV2;
import com.github.tomakehurst.wiremock.http.Response;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The test embedding (P-20 spec Section 6.13): one WireMock server per test JVM answering the
 * Ollama {@code /api/embed} and the OpenAI {@code /v1/embeddings} forms with deterministic vectors
 * of 64 numbers from SHA-256, so equal texts give equal vectors.
 */
public final class TestEmbedding {

  public static final int DIMENSIONS = 64;
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static TestEmbedding shared;

  private final Transformer transformer = new Transformer();
  private WireMockServer server;
  private int port;
  private Duration delay = Duration.ZERO;

  private TestEmbedding() {}

  /** The server of this test JVM, started at first use. */
  public static synchronized TestEmbedding shared() {
    if (shared == null) {
      shared = new TestEmbedding();
      shared.startServer(0);
    }
    return shared;
  }

  /**
   * The vector of a text: the k-th number from the first two bytes of SHA-256 of {@code text#k}.
   */
  public static float[] vector(String text) {
    float[] vector = new float[DIMENSIONS];
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (int k = 0; k < DIMENSIONS; k++) {
        byte[] hash = digest.digest((text + "#" + k).getBytes(StandardCharsets.UTF_8));
        vector[k] = (float) ((((hash[0] & 0xff) << 8 | (hash[1] & 0xff)) / 65535.0) - 0.5);
      }
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
    return vector;
  }

  public synchronized String address() {
    return "http://127.0.0.1:" + port;
  }

  /** The number of requests answered so far in this test JVM. */
  public int requestCount() {
    synchronized (transformer.bodies) {
      return transformer.bodies.size();
    }
  }

  /** The bodies of the requests answered after {@code count} was taken. */
  public List<String> requestsSince(int count) {
    synchronized (transformer.bodies) {
      return List.copyOf(transformer.bodies.subList(count, transformer.bodies.size()));
    }
  }

  /** Stops the server: the next request fails as unreachable. */
  public synchronized void stop() {
    if (server != null) {
      server.stop();
      server = null;
    }
  }

  /** Starts a new server on the same port. */
  public synchronized void start() {
    if (server == null) {
      startServer(port);
    }
  }

  /** Delays every answer until {@code delay(Duration.ZERO)}. */
  public synchronized void delay(Duration delay) {
    this.delay = delay;
    if (server != null) {
      stubs();
    }
  }

  private void startServer(int fixedPort) {
    server =
        new WireMockServer(
            (fixedPort == 0 ? options().dynamicPort() : options().port(fixedPort))
                .bindAddress("127.0.0.1")
                .extensions(transformer));
    server.start();
    port = server.port();
    stubs();
  }

  private void stubs() {
    server.resetMappings();
    int ms = (int) delay.toMillis();
    server.stubFor(
        post(urlEqualTo("/api/embed"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withFixedDelay(ms)));
    server.stubFor(
        post(urlEqualTo("/v1/embeddings"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withFixedDelay(ms)));
  }

  /** Writes the answer of each request from its {@code input} texts and keeps its body. */
  private static final class Transformer implements ResponseTransformerV2 {

    final List<String> bodies = new ArrayList<>();

    @Override
    public String getName() {
      return "test-embedding";
    }

    @Override
    public Response transform(Response response, ServeEvent serveEvent) {
      String body = serveEvent.getRequest().getBodyAsString();
      synchronized (bodies) {
        bodies.add(body);
      }
      JsonNode request = JSON.readTree(body);
      List<String> texts = new ArrayList<>();
      JsonNode input = request.get("input");
      if (input.isArray()) {
        input.forEach(text -> texts.add(text.stringValue()));
      } else {
        texts.add(input.stringValue());
      }
      String model = request.has("model") ? request.get("model").stringValue() : "test";
      ObjectNode answer = JSON.createObjectNode();
      if (serveEvent.getRequest().getUrl().equals("/api/embed")) {
        answer.put("model", model);
        ArrayNode embeddings = answer.putArray("embeddings");
        texts.forEach(text -> numbers(embeddings.addArray(), text));
        answer.put("prompt_eval_count", texts.size());
      } else {
        answer.put("object", "list");
        ArrayNode data = answer.putArray("data");
        for (int i = 0; i < texts.size(); i++) {
          ObjectNode item = data.addObject();
          item.put("object", "embedding");
          item.put("index", i);
          numbers(item.putArray("embedding"), texts.get(i));
        }
        answer.put("model", model);
        ObjectNode usage = answer.putObject("usage");
        usage.put("prompt_tokens", texts.size());
        usage.put("total_tokens", texts.size());
      }
      return Response.Builder.like(response).but().body(JSON.writeValueAsString(answer)).build();
    }

    private static void numbers(ArrayNode array, String text) {
      for (float number : vector(text)) {
        array.add(number);
      }
    }
  }
}
