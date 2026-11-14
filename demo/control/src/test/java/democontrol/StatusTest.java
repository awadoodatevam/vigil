package democontrol;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

class StatusTest {

  private static final Instant T0 = Instant.parse("2026-11-14T10:00:00Z");

  @TempDir Path folder;
  private final TestClock clock = new TestClock(T0);

  private Panel panel() {
    return new Panel(clock, "Techfinity", "http://demo.vigil.test:8080", folder.resolve("s.json"));
  }

  private static LastResult result(String operation, String outcome, String seconds, String error) {
    return new LastResult(operation, outcome, T0, new BigDecimal(seconds), null, null, null, error);
  }

  @Test
  @DisplayName("DC-S12-05 the status lines in their order")
  void statusLines() {
    Panel panel = panel();
    assertThat(panel.connected()).isFalse();
    assertThat(panel.accept(Button.RESET)).isEqualTo("Connecting…");
    panel.labChecked(null, new Lab.Counts(3, 2));
    assertThat(panel.connected()).isFalse();
    panel.vigilChecked(null);
    assertThat(panel.connected()).isTrue();
    assertThat(panel.status()).isEqualTo("Idle");

    panel.vigilChecked("Connection refused");
    assertThat(panel.status()).isEqualTo("Cannot reach Techfinity: Connection refused.");
    panel.labChecked("Zabbix: answered HTTP 502", null);
    assertThat(panel.status()).isEqualTo("Cannot reach the lab: Zabbix: answered HTTP 502.");

    assertThat(panel.accept(Button.STORM)).isNull();
    assertThat(panel.accept(Button.CLEAR)).isNull();
    clock.advance(Duration.ofMillis(7900));
    assertThat(panel.status())
        .isEqualTo("Clearing faults… 7 s — firing lab alerts: Zabbix 3, Alertmanager 2");
    panel.labRead(new Lab.Counts(1, 0));
    assertThat(panel.status())
        .isEqualTo("Clearing faults… 7 s — firing lab alerts: Zabbix 1, Alertmanager 0");
    panel.finish(result("Clear all faults", "completed", "41.999", null), true);
    assertThat(panel.status()).isEqualTo("Cannot reach the lab: Zabbix: answered HTTP 502.");
    panel.labChecked(null, null);
    assertThat(panel.status()).isEqualTo("Cannot reach Techfinity: Connection refused.");
    panel.vigilChecked(null);
    assertThat(panel.status()).isEqualTo("Clear all faults completed in 41 s");

    assertThat(panel.accept(Button.RESET)).isNull();
    clock.advance(Duration.ofSeconds(12));
    assertThat(panel.status()).isEqualTo("Resetting… 12 s — lab: Clearing, Techfinity: Resetting");
    panel.branchBDone();
    assertThat(panel.status()).isEqualTo("Resetting… 12 s — lab: Clearing, Techfinity: Ready");
    panel.branchADone();
    assertThat(panel.status()).isEqualTo("Resetting… 12 s — lab: Clear, Techfinity: Ready");
    panel.finish(result("Reset", "completed", "44.5", null), true);
    assertThat(panel.status()).isEqualTo("Reset completed in 44 s");
    panel.finish(
        result("Reset", "failed", "58.002", "The seed import was still running after 58 s."),
        false);
    assertThat(panel.status())
        .isEqualTo("Reset failed after 58 s: The seed import was still running after 58 s.");
    panel.faultButtonFailed(
        result("Storm: SL-F-1 + SL-F-3", "failed", "3.2", "demo-faults answered HTTP 502"));
    assertThat(panel.status())
        .isEqualTo("Storm: SL-F-1 + SL-F-3 failed after 3 s: demo-faults answered HTTP 502");
  }

  @Test
  @DisplayName("DC-S12-06 the storm clock until the next storm button or a reset")
  void stormClock() {
    Panel panel = panel();
    panel.labChecked(null, null);
    panel.vigilChecked(null);
    assertThat(panel.stormClock()).isNull();
    assertThat(panel.accept(Button.CUT_CLOUD_AI)).isNull();
    assertThat(panel.stormClock()).isNull();
    assertThat(panel.accept(Button.STORM)).isNull();
    assertThat(panel.stormClock()).isEqualTo("T+0:00 since 'Storm: SL-F-1 + SL-F-3'");
    clock.advance(Duration.ofSeconds(65));
    assertThat(panel.stormClock()).isEqualTo("T+1:05 since 'Storm: SL-F-1 + SL-F-3'");
    assertThat(panel.accept(Button.CLEAR)).isNull();
    clock.advance(Duration.ofSeconds(600));
    assertThat(panel.stormClock()).isEqualTo("T+11:05 since 'Storm: SL-F-1 + SL-F-3'");
    panel.finish(result("Clear all faults", "completed", "40", null), true);
    assertThat(panel.accept(Button.STORM_RERUN)).isNull();
    assertThat(panel.stormClock()).isEqualTo("T+0:00 since 'Storm re-run: SL-F-1'");
    clock.advance(Duration.ofSeconds(9));
    assertThat(panel.stormClock()).isEqualTo("T+0:09 since 'Storm re-run: SL-F-1'");
    assertThat(panel.accept(Button.RESET)).isNull();
    assertThat(panel.stormClock()).isNull();
    assertThat(panel.view().get("storm_clock")).isNull();
  }

  @Test
  @DisplayName("DC-S12-04 the active faults; the fault names are DD-F-10's")
  @SuppressWarnings("unchecked")
  void activeFaults() throws Exception {
    JsonNode data =
        new YAMLMapper()
            .readTree(Path.of("../../docs/31-demo-data/10-lab-and-faults.yaml").toFile());
    for (String fault : List.of("SL-F-1", "SL-F-2", "SL-F-3", "SL-F-4")) {
      assertThat(Button.FAULT_NAMES.get(fault))
          .isEqualTo(data.path("faults").path(fault).path("name").asString());
    }
    assertThat(Button.FAULT_NAMES).hasSize(4);
    Panel panel = panel();
    panel.labChecked(null, null);
    panel.vigilChecked(null);
    assertThat((List<?>) panel.view().get("active")).isEmpty();
    assertThat(panel.accept(Button.CUT_CLOUD_AI)).isNull();
    clock.advance(Duration.ofSeconds(3));
    assertThat(panel.accept(Button.STORM)).isNull();
    List<Map<String, Object>> active = (List<Map<String, Object>>) panel.view().get("active");
    assertThat(active)
        .containsExactly(
            Map.of(
                "fault",
                "SL-F-1",
                "name",
                "Subscriber DB /data full",
                "since",
                "2026-11-14T10:00:03Z"),
            Map.of(
                "fault",
                "SL-F-3",
                "name",
                "Unrelated report export failure",
                "since",
                "2026-11-14T10:00:03Z"),
            Map.of(
                "fault",
                "SL-F-4",
                "name",
                "Cloud AI unreachable",
                "since",
                "2026-11-14T10:00:00Z"));
  }

  @Test
  @DisplayName("DC-S12-02 the six buttons with their descriptions")
  @SuppressWarnings("unchecked")
  void buttons() {
    Panel panel = panel();
    List<Map<String, Object>> buttons = (List<Map<String, Object>>) panel.view().get("buttons");
    assertThat(buttons)
        .extracting(b -> b.get("key"), b -> b.get("label"), b -> b.get("description"))
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(
                "storm",
                "Storm: SL-F-1 + SL-F-3",
                "Fills /data on the Subscriber DB server and fails the report export job."),
            org.assertj.core.groups.Tuple.tuple(
                "storm-rerun",
                "Storm re-run: SL-F-1",
                "Fills /data on the Subscriber DB server again."),
            org.assertj.core.groups.Tuple.tuple(
                "storm-read-only",
                "Storm with read-only DB: SL-F-1 + SL-F-2",
                "Fills /data and sets the database read-only."),
            org.assertj.core.groups.Tuple.tuple(
                "cut-cloud-ai",
                "Cut cloud AI: SL-F-4",
                "Blocks Techfinity's calls to the Claude and OpenAI APIs."),
            org.assertj.core.groups.Tuple.tuple(
                "clear",
                "Clear all faults",
                "Ends every active fault and waits until every lab alert is resolved."),
            org.assertj.core.groups.Tuple.tuple(
                "reset", "Reset", "Clears the lab and brings Techfinity to the starting state."));
    assertThat(panel.view())
        .containsKeys(
            "product",
            "vigil_url",
            "connected",
            "active",
            "buttons",
            "status",
            "storm_clock",
            "last_result")
        .hasSize(8)
        .containsEntry("product", "Techfinity")
        .containsEntry("vigil_url", "http://demo.vigil.test:8080");
  }
}
