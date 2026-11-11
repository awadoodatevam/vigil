package vigil.connectors;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The monitoring webhook calls P-04 recorded and the provider calls P-06 recorded, copied into the
 * test resources (BP-R-06).
 */
public final class Recordings {

  public static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Path RECORDINGS = Path.of("src/test/resources/recordings");
  private static final Path FOLDER = RECORDINGS.resolve("monitoring");

  /**
   * One provider call of P-06 (P-06 spec Section 6.2): the request's method, address and body as
   * sent, the answer's status, headers and body as received.
   */
  public record ProviderCall(
      String method,
      String url,
      String requestBody,
      int status,
      JsonNode responseHeaders,
      String responseBody) {

    public JsonNode request() {
      return JSON.readTree(requestBody);
    }
  }

  private Recordings() {}

  /** The file names of one source key, in order. */
  static List<String> names(String sourceKey) {
    try (Stream<Path> files = Files.list(FOLDER)) {
      return files
          .map(file -> file.getFileName().toString())
          .filter(name -> name.endsWith("-" + sourceKey + ".json"))
          .sorted()
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The body of a recording exactly as received. */
  static String body(String name) {
    try {
      return JSON.readTree(Files.readString(FOLDER.resolve(name), StandardCharsets.UTF_8))
          .get("body")
          .stringValue();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The provider call {@code name} of the folder {@code provider} (Example: {@code claude}). */
  public static ProviderCall provider(String provider, String name) {
    return provider(provider, name, "${endpoint.jira}");
  }

  /**
   * The provider call {@code name} of the folder {@code provider} with {@code ${endpoint.jira}}
   * replaced by {@code base} in the request address and the answer body.
   */
  public static ProviderCall provider(String provider, String name, String base) {
    JsonNode call;
    try {
      call =
          JSON.readTree(
              Files.readString(RECORDINGS.resolve(provider).resolve(name), StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    JsonNode response = call.get("response");
    JsonNode request = call.get("request");
    return new ProviderCall(
        request.get("method").stringValue(),
        request.get("url").stringValue().replace("${endpoint.jira}", base),
        request.get("body").stringValue(),
        response.get("status").intValue(),
        response.get("headers"),
        response.get("body").stringValue().replace("${endpoint.jira}", base));
  }

  public static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  public static JsonNode tree(String text) {
    return JSON.readTree(text);
  }
}
