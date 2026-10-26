package vigil.runner;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class ParametersTest {

  private static List<String> problems(String key, String parametersJson) {
    return Parameters.valueProblems(key, RunnerFixture.definitions(key), parametersJson);
  }

  @Test
  @DisplayName("DM-V-06 parameter values are checked against their definitions")
  void valueChecks() {
    assertThat(problems("fs_usage", "{\"mount\": \"/data\"}")).isEmpty();
    assertThat(problems("fs_usage", "{\"mount\": \"/data/db\"}")).isEmpty();
    for (String mount : List.of("/etc", "/data/../etc", "/data2", "data")) {
      assertThat(problems("fs_usage", "{\"mount\": \"" + mount + "\"}"))
          .as(mount)
          .containsExactly("parameter mount: not under /data");
    }
    assertThat(problems("fs_usage", "{\"mount\": 5}"))
        .containsExactly("parameter mount: not a text");
    assertThat(problems("fs_usage", "{\"mount\": \"/data/\\u0000\"}"))
        .containsExactly("parameter mount: holds the character U+0000");
    assertThat(problems("fs_usage", "{}")).containsExactly("parameter mount: missing");
    assertThat(problems("fs_usage", "{\"mount\": null}"))
        .containsExactly("parameter mount: missing");
    assertThat(problems("fs_usage", "{\"mount\": \"/data\", \"x\": 1}"))
        .containsExactly("parameter x: not a parameter of fs_usage");
    assertThat(problems("fs_usage", "[1]")).containsExactly("parameters: not an object");
    for (String count : List.of("1", "12")) {
      assertThat(problems("largest_dirs", "{\"path\": \"/data\", \"count\": " + count + "}"))
          .containsExactly("parameter count: 2 to 11");
    }
    for (String count : List.of("\"4\"", "4.5")) {
      assertThat(problems("largest_dirs", "{\"path\": \"/data\", \"count\": " + count + "}"))
          .containsExactly("parameter count: not a whole number");
    }
    assertThat(problems("largest_dirs", "{\"x\": 1}"))
        .containsExactly(
            "parameter x: not a parameter of largest_dirs",
            "parameter path: missing",
            "parameter count: missing");
    assertThat(problems("recent_growth", "{\"path\": \"/data\", \"minutes\": 0}"))
        .containsExactly("parameter minutes: 1 to 1440");
    JsonNode definitions =
        RunnerFixture.JSON.readTree(
            "[{\"name\":\"mode\",\"value_type\":\"enum\",\"required\":true,"
                + "\"allowed_values\":[\"a\",\"b\"]},"
                + " {\"name\":\"flag\",\"value_type\":\"boolean\",\"required\":false},"
                + " {\"name\":\"ratio\",\"value_type\":\"decimal\",\"required\":false,\"min\":0.5},"
                + " {\"name\":\"note\",\"value_type\":\"text\",\"required\":false,\"max_length\":3},"
                + " {\"name\":\"wait\",\"value_type\":\"duration\",\"required\":false,\"max\":60}]");
    assertThat(
            Parameters.valueProblems(
                "custom",
                definitions,
                "{\"mode\": \"c\", \"flag\": \"yes\", \"ratio\": 0.25, \"note\": \"abcd\","
                    + " \"wait\": -1}"))
        .containsExactly(
            "parameter mode: one of a, b",
            "parameter flag: true or false",
            "parameter ratio: at least 0.5",
            "parameter note: at most 3 characters",
            "parameter wait: 0 to 60");
    assertThat(
            Parameters.texts(
                RunnerFixture.definitions("largest_dirs"),
                RunnerFixture.JSON.readTree("{\"path\": \"/data\", \"count\": 4}")))
        .isEqualTo(Map.of("path", "/data", "count", "4"))
        .hasToString("{path=/data, count=4}");
    assertThat(
            Parameters.texts(
                    RunnerFixture.definitions("purge_old_archives"),
                    RunnerFixture.JSON.readTree(
                        "{\"path\": \"/data/txlog_archive\", \"older_than_minutes\": 1440,"
                            + " \"max_bytes\": 12884901888}"))
                .get("max_bytes"))
        .isEqualTo("12884901888");
  }
}
