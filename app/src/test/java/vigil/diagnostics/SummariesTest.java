package vigil.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

class SummariesTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Path OPERATIONS =
      Path.of("../docs/31-demo-data/04-operations-actions-recovery.yaml");

  private static JsonNode values(String json) {
    return JSON.readTree(json);
  }

  private static String template(String key) throws IOException {
    JsonNode file =
        YAMLMapper.builder().build().readTree(Files.readString(OPERATIONS, StandardCharsets.UTF_8));
    for (JsonNode definition : file.get("diagnostic_definitions")) {
      if (definition.get("diagnostic_key").stringValue().equals(key)) {
        return definition.get("summary_template").stringValue();
      }
    }
    throw new IllegalArgumentException(key);
  }

  @Test
  @DisplayName("DD-R-10 placeholders of values are filled, every other brace is kept")
  void placeholders() {
    assertThat(
            Summaries.fill(
                "{x} and {used_pct}%", values("[{\"name\":\"used_pct\",\"value\":100}]")))
        .isEqualTo("{x} and 100%");
    assertThat(Summaries.fill("<{a/b.c}>", values("[{\"name\":\"a/b.c\",\"value\":\"v\"}]")))
        .isEqualTo("<v>");
  }

  @Test
  @DisplayName("DC-TX-03 the three demo summaries with the development values")
  void demoSummaries() throws IOException {
    JsonNode disk =
        values(
            "[{\"name\":\"mount\",\"value\":\"/data\"},"
                + "{\"name\":\"size_bytes\",\"value\":17112760320},"
                + "{\"name\":\"used_bytes\",\"value\":17045651456},"
                + "{\"name\":\"avail_bytes\",\"value\":67108864},"
                + "{\"name\":\"used_pct\",\"value\":100},"
                + "{\"name\":\"size_gib\",\"value\":\"15.9\"},"
                + "{\"name\":\"used_gib\",\"value\":\"15.9\"},"
                + "{\"name\":\"avail_gib\",\"value\":\"0.1\"}]");
    JsonNode folders =
        values(
            "[{\"name\":\"top1_path\",\"value\":\"/data/txlog_archive\"},"
                + "{\"name\":\"top1_gib\",\"value\":\"8.5\"},"
                + "{\"name\":\"top2_path\",\"value\":\"/data/db\"},"
                + "{\"name\":\"top2_gib\",\"value\":\"6.0\"},"
                + "{\"name\":\"top3_path\",\"value\":\"/data/export\"},"
                + "{\"name\":\"top3_gib\",\"value\":\"1.0\"}]");
    JsonNode growth =
        values(
            "[{\"name\":\"files\",\"value\":34},{\"name\":\"bytes\",\"value\":9167810560},"
                + "{\"name\":\"gib\",\"value\":\"8.5\"}]");
    assertThat(Summaries.fill(template("disk_usage"), disk))
        .isEqualTo("/data is 100% full (15.9 of 15.9 GiB used, 0.1 GiB free)");
    assertThat(Summaries.fill(template("largest_folders"), folders))
        .isEqualTo("Largest folder under /data: /data/txlog_archive (8.5 GiB)");
    assertThat(Summaries.fill(template("disk_growth"), growth))
        .isEqualTo("8.5 GiB written to 34 files under /data in the last 15 minutes");
  }
}
