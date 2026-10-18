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

/** The monitoring webhook calls P-04 recorded, copied into the test resources (BP-R-06). */
final class Recordings {

  static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Path FOLDER = Path.of("src/test/resources/recordings/monitoring");

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

  static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  static JsonNode tree(String text) {
    return JSON.readTree(text);
  }
}
