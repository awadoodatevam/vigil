package democontrol;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FaultButtonsIT {

  @TempDir Path folder;
  private Fixture fixture;

  @BeforeEach
  void start() throws Exception {
    fixture = new Fixture(folder, Duration.ofSeconds(5), Duration.ofMillis(200)).connected();
  }

  @AfterEach
  void stop() {
    fixture.close();
  }

  private List<String> faultCalls() {
    return Fixture.journal(fixture.lab).stream()
        .map(ServeEvent::getRequest)
        .filter(r -> r.getUrl().startsWith("/faults/"))
        .map(r -> r.getUrl())
        .toList();
  }

  @SuppressWarnings("unchecked")
  private List<String> active() {
    return ((List<Map<String, Object>>) fixture.panel.view().get("active"))
        .stream().map(line -> (String) line.get("fault")).toList();
  }

  @Test
  @DisplayName(
      "SL-A3-02 a storm button records its faults at the press and injects them in fault ID order")
  void storm() {
    fixture.lab.stubFor(
        post(urlEqualTo("/faults/SL-F-1/inject"))
            .withHeader("Authorization", equalTo("Bearer " + fixture.faultsToken))
            .willReturn(okJson("{\"active\":[\"SL-F-1\"]}").withFixedDelay(400)));
    fixture.lab.stubFor(
        post(urlEqualTo("/faults/SL-F-2/inject"))
            .withHeader("Authorization", equalTo("Bearer " + fixture.faultsToken))
            .willReturn(okJson("{\"active\":[\"SL-F-1\",\"SL-F-2\"]}")));
    assertThat(fixture.operations.press(Button.STORM_READ_ONLY.key))
        .isEqualTo(new Operations.Press(true, null));
    assertThat(active()).containsExactly("SL-F-1", "SL-F-2");
    assertThat(fixture.panel.stormClock())
        .isEqualTo("T+0:00 since 'Storm with read-only DB: SL-F-1 + SL-F-2'");
    Fixture.until(() -> faultCalls().size() == 2, Duration.ofSeconds(5));
    assertThat(faultCalls()).containsExactly("/faults/SL-F-1/inject", "/faults/SL-F-2/inject");
    Fixture.sleep(Duration.ofMillis(200));
    assertThat(fixture.panel.view().get("last_result")).isNull();
    assertThat(fixture.panel.status()).isEqualTo("Idle");
    assertThat(fixture.operations.press("unknown")).isEqualTo(new Operations.Press(false, null));
  }

  @Test
  @DisplayName("SL-A3-02 Cut cloud AI injects SL-F-4")
  void cutCloudAi() {
    fixture.faultsAnswer();
    assertThat(fixture.operations.press(Button.CUT_CLOUD_AI.key).refusal()).isNull();
    assertThat(active()).containsExactly("SL-F-4");
    assertThat(fixture.panel.stormClock()).isNull();
    Fixture.until(() -> faultCalls().size() == 1, Duration.ofSeconds(5));
    assertThat(faultCalls()).containsExactly("/faults/SL-F-4/inject");
    assertThat(fixture.operations.press(Button.CUT_CLOUD_AI.key).refusal())
        .isEqualTo("Already active.");
  }

  @Test
  @DisplayName("DC-S12-05 a failed inject is the last result and the record keeps the faults")
  void failedInject() {
    fixture.lab.stubFor(
        post(urlEqualTo("/faults/SL-F-1/inject"))
            .willReturn(
                aResponse()
                    .withStatus(500)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"error\":\"SL-F-1 inject failed (exit 1): no space\"}")));
    fixture.operations.press(Button.STORM.key);
    LastResult result = fixture.awaitResult("Storm: SL-F-1 + SL-F-3", Duration.ofSeconds(5));
    assertThat(result.outcome()).isEqualTo("failed");
    assertThat(result.error()).isEqualTo("SL-F-1 inject failed (exit 1): no space");
    assertThat(fixture.panel.status())
        .isEqualTo(
            "Storm: SL-F-1 + SL-F-3 failed after 0 s: SL-F-1 inject failed (exit 1): no space");
    assertThat(faultCalls()).containsExactly("/faults/SL-F-1/inject");
    assertThat(active()).containsExactly("SL-F-1", "SL-F-3");

    fixture.lab.stubFor(
        post(urlEqualTo("/faults/SL-F-4/inject"))
            .willReturn(aResponse().withStatus(502).withBody("bad gateway")));
    fixture.operations.press(Button.CUT_CLOUD_AI.key);
    Fixture.until(
        () -> fixture.lastResult().operation().equals("Cut cloud AI: SL-F-4"),
        Duration.ofSeconds(5));
    assertThat(fixture.lastResult().error()).isEqualTo("demo-faults answered HTTP 502");

    fixture.lab.stop();
    fixture.panel.finish(fixture.lastResult(), true);
    fixture.operations.press(Button.CUT_CLOUD_AI.key);
    Fixture.until(
        () -> fixture.lastResult().error().startsWith("demo-faults: "), Duration.ofSeconds(10));
    assertThat(active()).containsExactly("SL-F-4");
  }
}
