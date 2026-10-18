package vigil.connectors;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/** The application's own log lines in captured output: ECS objects of a {@code vigil.} logger. */
final class LogLines {

  private LogLines() {}

  static List<JsonNode> application(String output) {
    List<JsonNode> lines = new ArrayList<>();
    for (String line : output.split("\\R")) {
      if (!line.startsWith("{")) {
        continue;
      }
      try {
        JsonNode node = Recordings.JSON.readTree(line);
        if (node.isObject() && node.path("log").path("logger").asString("").startsWith("vigil.")) {
          lines.add(node);
        }
      } catch (JacksonException e) {
        // Not a JSON log line.
      }
    }
    return lines;
  }

  /** No application line holds a value as written or JSON-escaped. */
  static void assertNoneContains(String output, List<String> values) {
    List<JsonNode> lines = application(output);
    assertThat(lines).isNotEmpty();
    for (JsonNode line : lines) {
      String text = Recordings.JSON.writeValueAsString(line);
      for (String value : values) {
        String escaped = Recordings.JSON.writeValueAsString(value);
        escaped = escaped.substring(1, escaped.length() - 1);
        assertThat(text).doesNotContain(value).doesNotContain(escaped);
      }
    }
  }
}
