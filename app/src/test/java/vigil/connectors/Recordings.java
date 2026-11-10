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
final class Recordings {

  static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Path RECORDINGS = Path.of("src/test/resources/recordings");
  private static final Path FOLDER = RECORDINGS.resolve("monitoring");

  /**
   * One provider call of P-06 (P-06 spec Section 6.2): the request body as sent, the answer's
   * status, headers and body as received.
   */
  record ProviderCall(
      String requestBody, int status, JsonNode responseHeaders, String responseBody) {

    JsonNode request() {
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
  static ProviderCall provider(String provider, String name) {
    JsonNode call;
    try {
      call =
          JSON.readTree(
              Files.readString(RECORDINGS.resolve(provider).resolve(name), StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    JsonNode response = call.get("response");
    return new ProviderCall(
        call.get("request").get("body").stringValue(),
        response.get("status").intValue(),
        response.get("headers"),
        response.get("body").stringValue());
  }

  static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  static JsonNode tree(String text) {
    return JSON.readTree(text);
  }
}
