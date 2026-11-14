package democontrol;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

class StateFileIT {

  @TempDir Path folder;

  @Test
  @DisplayName("AR-X-04 the record, the storm clock and the last result survive a restart")
  void survivesRestart() throws Exception {
    Path file = folder.resolve("state").resolve("state.json");
    TestClock clock = new TestClock(Instant.parse("2026-11-14T10:00:00Z"));
    Panel first = new Panel(clock, "Techfinity", "http://demo.vigil.test:8080", file);
    assertThat(Files.exists(file)).isFalse();
    first.labChecked(null, null);
    first.vigilChecked(null);
    first.finish(
        new LastResult(
            "Reset",
            "completed",
            Instant.parse("2026-11-14T09:59:00Z"),
            new BigDecimal("44.123"),
            new BigDecimal("43.500"),
            new BigDecimal("31.250"),
            new BigDecimal("12.000"),
            null),
        true);
    assertThat(first.accept(Button.CUT_CLOUD_AI)).isNull();
    clock.advance(Duration.ofSeconds(5));
    assertThat(first.accept(Button.STORM)).isNull();
    assertThat(first.accept(Button.CLEAR)).isNull();
    Map<String, Object> before = first.view();

    JsonNode saved = Http.JSON.readTree(Files.readString(file));
    assertThat(saved.path("record")).hasSize(3);
    assertThat(saved.path("storm_clock").path("label").asString())
        .isEqualTo("Storm: SL-F-1 + SL-F-3");
    assertThat(saved.path("last_result").path("seconds").decimalValue())
        .isEqualByComparingTo("44.123");
    try (var listing = Files.list(file.getParent())) {
      assertThat(listing.map(path -> path.getFileName().toString())).containsExactly("state.json");
    }

    clock.advance(Duration.ofSeconds(70));
    Panel second = new Panel(clock, "Techfinity", "http://demo.vigil.test:8080", file);
    second.labChecked(null, null);
    second.vigilChecked(null);
    Map<String, Object> after = second.view();
    assertThat(after.get("active")).isEqualTo(before.get("active"));
    assertThat(after.get("last_result")).isEqualTo(before.get("last_result"));
    assertThat(after.get("storm_clock")).isEqualTo("T+1:10 since 'Storm: SL-F-1 + SL-F-3'");
    // the clear that ran when demo-control stopped is not resumed.
    assertThat(second.operationRunning()).isFalse();
    assertThat(second.status()).isEqualTo("Reset completed in 44 s");
    assertThat(second.refusal(Button.CUT_CLOUD_AI)).isEqualTo("Already active.");
  }
}
