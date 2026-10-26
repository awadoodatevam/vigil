package vigil.runner;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import vigil.runner.api.RunnerPart.OperationPart;

class CommandsTest {

  private static String template(String key) {
    return RunnerFixture.demoPart().operations().stream()
        .filter(operation -> operation.operationKey().equals(key))
        .findFirst()
        .map(OperationPart::commandTemplate)
        .orElseThrow();
  }

  @Test
  @DisplayName("DD-R-10 placeholders of defined parameters are filled, every other brace is kept")
  void placeholders() {
    assertThat(
            Commands.fill(
                template("largest_dirs"),
                RunnerFixture.definitions("largest_dirs"),
                Map.of("path", "/data", "count", "4")))
        .isEqualTo(
            "set -o pipefail; LC_ALL=C du -x -B1 --max-depth=1 -- '/data' | sort -rn"
                + " | awk -v c='4' 'NR <= c'");
    String growth = template("recent_growth");
    assertThat(
            Commands.fill(
                growth,
                RunnerFixture.definitions("recent_growth"),
                Map.of("path", "/data", "minutes", "15")))
        .isEqualTo(growth.replace("{path}", "'/data'").replace("{minutes}", "'15'"))
        .contains("'BEGIN {n = 0; s = 0} {n += 1; s += $1} END {printf");
    JsonNode one =
        RunnerFixture.JSON.readTree(
            "[{\"name\":\"text\",\"value_type\":\"text\",\"required\":false}]");
    assertThat(Commands.fill("echo {other} {text}", one, Map.of("text", "x")))
        .isEqualTo("echo {other} 'x'");
    assertThat(Commands.fill("echo {text}", one, Map.of("text", ""))).isEqualTo("echo ''");
    JsonNode two =
        RunnerFixture.JSON.readTree(
            "[{\"name\":\"text\",\"value_type\":\"text\",\"required\":true},"
                + " {\"name\":\"count\",\"value_type\":\"integer\",\"required\":true}]");
    assertThat(Commands.fill("{text} {count}", two, Map.of("text", "{count}", "count", "3")))
        .isEqualTo("'{count}' '3'");
  }

  @Test
  @DisplayName("AR-K-12 values are bash-quoted")
  void quoting() {
    assertThat(Commands.quote("it's")).isEqualTo("'it'\\''s'");
    assertThat(Commands.quote("")).isEqualTo("''");
    assertThat(Commands.quote("$(id) \"x\"")).isEqualTo("'$(id) \"x\"'");
  }
}
