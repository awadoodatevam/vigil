package vigil.runner;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;

class OutputParsersTest {

  private static final JsonNode NONE = RunnerFixture.JSON.createObjectNode();

  private static JsonNode parse(String parser, String output, JsonNode parameters) {
    Optional<ArrayNode> values = OutputParsers.parse(parser, output, parameters);
    assertThat(values).as(parser + " of " + output).isPresent();
    return RunnerFixture.JSON.readTree(RunnerFixture.JSON.writeValueAsString(values.get()));
  }

  /** The values written as {@code [{"name": value}, …]}, as a tree of {@code {name, value}}. */
  private static JsonNode values(String pairsJson) {
    ArrayNode values = RunnerFixture.JSON.createArrayNode();
    for (JsonNode pair : RunnerFixture.JSON.readTree(pairsJson)) {
      String name = pair.propertyNames().iterator().next();
      values.addObject().put("name", name).set("value", pair.get(name));
    }
    return RunnerFixture.JSON.readTree(RunnerFixture.JSON.writeValueAsString(values));
  }

  @Test
  @DisplayName("AR-K-13 the five parsers read their outputs into ordered values")
  void parsers() {
    assertThat(
            parse(
                "df_row",
                "Mounted on 1B-blocks Used Avail Use%\n"
                    + "/data 17112760320 17045651456 67108864 100%\n",
                NONE))
        .isEqualTo(
            values(
                "[{\"mount\":\"/data\"}, {\"size_bytes\":17112760320},"
                    + " {\"used_bytes\":17045651456}, {\"avail_bytes\":67108864},"
                    + " {\"used_pct\":100}, {\"size_gib\":\"15.9\"}, {\"used_gib\":\"15.9\"},"
                    + " {\"avail_gib\":\"0.1\"}]"));
    assertThat(
            parse(
                "du_top",
                "17045651456\t/data\n9126805504\t/data/txlog_archive\n6442450944\t/data/db\n"
                    + "1073741824\t/data/export\n",
                RunnerFixture.JSON.readTree("{\"path\": \"/data\", \"count\": 4}")))
        .isEqualTo(
            values(
                "[{\"top1_path\":\"/data/txlog_archive\"}, {\"top1_gib\":\"8.5\"},"
                    + " {\"top2_path\":\"/data/db\"}, {\"top2_gib\":\"6.0\"},"
                    + " {\"top3_path\":\"/data/export\"}, {\"top3_gib\":\"1.0\"}]"));
    assertThat(parse("count_bytes", "34 9167810560\n", NONE))
        .isEqualTo(values("[{\"files\":34}, {\"bytes\":9167810560}, {\"gib\":\"8.5\"}]"));
    assertThat(parse("single_number", "100", NONE)).isEqualTo(values("[{\"value\":100}]"));
    assertThat(parse("single_number", " 47\n", NONE)).isEqualTo(values("[{\"value\":47}]"));
    assertThat(parse("single_number", "-3", NONE)).isEqualTo(values("[{\"value\":-3}]"));
    assertThat(
            parse(
                "removed_files", "268435456 txlog_000001.arc\n268435456 txlog_000002.arc\n", NONE))
        .isEqualTo(
            values(
                "[{\"files_removed\":2}, {\"bytes_removed\":536870912},"
                    + " {\"gib_removed\":\"0.5\"}]"));
    assertThat(parse("removed_files", "", NONE))
        .isEqualTo(
            values("[{\"files_removed\":0}, {\"bytes_removed\":0}, {\"gib_removed\":\"0.0\"}]"));
    assertThat(parse("count_bytes", "1 1342177280\n", NONE).get(2).get("value").stringValue())
        .isEqualTo("1.3");
  }

  @Test
  @DisplayName("AR-K-13 outputs a parser cannot read give no values")
  void notReadable() {
    assertThat(OutputParsers.parse("df_row", "", NONE)).isEmpty();
    assertThat(OutputParsers.parse("df_row", "/data 1 2 3\n", NONE)).isEmpty();
    assertThat(OutputParsers.parse("du_top", "x\n", NONE)).isEmpty();
    assertThat(OutputParsers.parse("count_bytes", "1 2\n3 4\n", NONE)).isEmpty();
    assertThat(OutputParsers.parse("single_number", "", NONE)).isEmpty();
    assertThat(OutputParsers.parse("single_number", "12a", NONE)).isEmpty();
    assertThat(OutputParsers.parse("single_number", "a�b", NONE)).isEmpty();
    assertThat(OutputParsers.parse("removed_files", "x y\n", NONE)).isEmpty();
  }
}
