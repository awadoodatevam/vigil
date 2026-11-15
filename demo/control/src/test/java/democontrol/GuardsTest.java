package democontrol;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GuardsTest {

  @TempDir Path folder;

  private Panel panel() {
    Panel panel =
        new Panel(
            new TestClock(Instant.parse("2026-11-14T10:00:00Z")),
            "Techfinity",
            "http://demo.vigil.test:8080",
            folder.resolve("state.json"));
    panel.labChecked(null, null);
    panel.vigilChecked(null);
    return panel;
  }

  private static Map<Button, String> refusals(Panel panel) {
    Map<Button, String> refusals = new LinkedHashMap<>();
    for (Button button : Button.values()) {
      refusals.put(button, panel.refusal(button));
    }
    return refusals;
  }

  @Test
  @DisplayName("DC-S12-03 the guards (complete list)")
  void guards() {
    Panel panel = panel();
    Map<Button, String> idle = refusals(panel);
    assertThat(idle).containsEntry(Button.CLEAR, "No active faults.");
    assertThat(idle.entrySet().stream().filter(e -> e.getKey() != Button.CLEAR))
        .allMatch(e -> e.getValue() == null);
    assertThat(panel.accept(Button.CLEAR)).isEqualTo("No active faults.");

    assertThat(panel.accept(Button.STORM_READ_ONLY)).isNull();
    String firing = "Clear all faults first (active: SL-F-1, SL-F-2).";
    assertThat(refusals(panel))
        .containsEntry(Button.STORM, firing)
        .containsEntry(Button.STORM_RERUN, firing)
        .containsEntry(Button.STORM_READ_ONLY, firing)
        .containsEntry(Button.CUT_CLOUD_AI, null)
        .containsEntry(Button.CLEAR, null)
        .containsEntry(Button.RESET, null);
    assertThat(panel.accept(Button.STORM)).isEqualTo(firing);
    assertThat(panel.accept(Button.CUT_CLOUD_AI)).isNull();
    assertThat(refusals(panel))
        .containsEntry(Button.STORM, "Clear all faults first (active: SL-F-1, SL-F-2, SL-F-4).")
        .containsEntry(Button.CUT_CLOUD_AI, "Already active.");

    assertThat(panel.accept(Button.CLEAR)).isNull();
    for (Button button : Button.values()) {
      assertThat(panel.refusal(button)).isEqualTo("Wait: Clear all faults is running.");
      assertThat(panel.accept(button)).isEqualTo("Wait: Clear all faults is running.");
    }
    panel.finish(
        new LastResult(
            "Clear all faults", "completed", Instant.now(), null, null, null, null, null),
        true);
    assertThat(panel.accept(Button.RESET)).isNull();
    for (Button button : Button.values()) {
      assertThat(panel.refusal(button)).isEqualTo("Wait: Reset is running.");
    }
  }

  @Test
  @DisplayName("DD-F-10 an active SL-F-4 does not block the storm buttons")
  void cloudCutDoesNotBlockStorms() {
    Panel panel = panel();
    assertThat(panel.accept(Button.CUT_CLOUD_AI)).isNull();
    assertThat(panel.refusal(Button.STORM)).isNull();
    assertThat(panel.refusal(Button.STORM_RERUN)).isNull();
    assertThat(panel.refusal(Button.STORM_READ_ONLY)).isNull();
    assertThat(panel.refusal(Button.CLEAR)).isNull();
    assertThat(panel.accept(Button.STORM_RERUN)).isNull();
    assertThat(panel.refusal(Button.STORM))
        .isEqualTo("Clear all faults first (active: SL-F-1, SL-F-4).");
  }
}
