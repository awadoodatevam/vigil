package democontrol;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClearIT {

  @TempDir Path folder;
  private Fixture fixture;

  @AfterEach
  void stop() {
    fixture.close();
  }

  private List<LoggedRequest> reads() {
    return Fixture.journal(fixture.lab).stream()
        .map(ServeEvent::getRequest)
        .filter(r -> r.getUrl().equals("/api/v2/alerts"))
        .map(r -> (LoggedRequest) r)
        .toList();
  }

  @Test
  @DisplayName("DD-F-10 Clear all faults clears the four faults, waits for a quiet lab, then 12 s")
  void clear() throws Exception {
    fixture = new Fixture(folder, Duration.ofSeconds(20), Duration.ofSeconds(1)).connected();
    fixture.faultsAnswer().labReads(2, 1, 0);
    assertThat(fixture.panel.accept(Button.STORM)).isNull();
    assertThat(fixture.operations.press(Button.CLEAR.key).refusal()).isNull();
    assertThat(fixture.panel.refusal(Button.RESET)).isEqualTo("Wait: Clear all faults is running.");
    Fixture.until(() -> reads().size() >= 1, Duration.ofSeconds(5));
    Fixture.until(
        () -> fixture.panel.status().endsWith("Zabbix 2, Alertmanager 2"), Duration.ofSeconds(2));
    LastResult result = fixture.awaitResult("Clear all faults", Duration.ofSeconds(15));
    assertThat(result.outcome()).isEqualTo("completed");
    assertThat(fixture.panel.status()).matches("Clear all faults completed in \\d+ s");
    fixture.lab.verify(
        postRequestedFor(urlEqualTo("/faults/clear"))
            .withHeader("Authorization", equalTo("Bearer " + fixture.faultsToken))
            .withRequestBody(
                equalToJson("{\"faults\":[\"SL-F-1\",\"SL-F-2\",\"SL-F-3\",\"SL-F-4\"]}")));
    fixture.lab.verify(
        postRequestedFor(urlEqualTo("/api_jsonrpc.php"))
            .withHeader("Authorization", equalTo("Bearer " + fixture.zabbixToken))
            .withHeader("Content-Type", equalTo("application/json-rpc"))
            .withRequestBody(
                equalToJson(
                    "{\"jsonrpc\":\"2.0\",\"method\":\"problem.get\",\"params\":{\"output\":"
                        + "[\"eventid\",\"name\"],\"tags\":[{\"tag\":\"vigil\",\"value\":\"lab\","
                        + "\"operator\":1}]},\"id\":1}")));
    String basic =
        Base64.getEncoder()
            .encodeToString(
                ("vigil:" + fixture.alertmanagerPassword).getBytes(StandardCharsets.UTF_8));
    assertThat(reads()).allMatch(r -> r.getHeader("Authorization").equals("Basic " + basic));
    assertThat(reads()).hasSize(3);
    // quiet at the third read (about 4 s after the clear), then the settle time (1 s here).
    assertThat(result.quietSeconds()).isBetween(new BigDecimal("3.5"), new BigDecimal("5.5"));
    assertThat(result.seconds().subtract(result.quietSeconds()))
        .isGreaterThanOrEqualTo(new BigDecimal("1.0"));
    assertThat(result.branchASeconds()).isNull();
    assertThat(fixture.panel.view().get("active")).asList().isEmpty();
    assertThat(Wiring.SETTLE).isEqualTo(Duration.ofSeconds(12));
    assertThat(Wiring.LIMIT).isEqualTo(Duration.ofSeconds(58));
  }

  @Test
  @DisplayName("AR-B-03 the lab reads are retried every 2 s until the limit")
  void retried() throws Exception {
    fixture = new Fixture(folder, Duration.ofSeconds(5), Duration.ofSeconds(1)).connected();
    fixture.faultsAnswer();
    fixture.lab.stubFor(
        post(urlEqualTo("/api_jsonrpc.php")).willReturn(aResponse().withStatus(503)));
    fixture.lab.stubFor(get(urlEqualTo("/api/v2/alerts")).willReturn(aResponse().withStatus(200)));
    fixture.panel.accept(Button.STORM_RERUN);
    fixture.operations.press(Button.CLEAR.key);
    LastResult result = fixture.awaitResult("Clear all faults", Duration.ofSeconds(10));
    assertThat(result.outcome()).isEqualTo("failed");
    assertThat(result.error()).isEqualTo("The lab could not be read: Zabbix: answered HTTP 503.");
    List<LoggedRequest> zabbix =
        Fixture.journal(fixture.lab).stream()
            .map(e -> (LoggedRequest) e.getRequest())
            .filter(r -> r.getUrl().equals("/api_jsonrpc.php"))
            .toList();
    assertThat(zabbix).hasSize(4);
    for (int index = 1; index < 3; index++) {
      long gap =
          zabbix.get(index).getLoggedDate().getTime()
              - zabbix.get(index - 1).getLoggedDate().getTime();
      assertThat(gap).isBetween(1800L, 2400L);
    }
    assertThat(result.seconds()).isBetween(new BigDecimal("5.0"), new BigDecimal("6.5"));
    // demo-faults answered the clear with 200: the record is emptied although the clear failed.
    assertThat(fixture.panel.view().get("active")).asList().isEmpty();
  }

  @Test
  @DisplayName("DC-S12-05 the clear's failure texts")
  void failureTexts() throws Exception {
    fixture = new Fixture(folder, Duration.ofSeconds(3), Duration.ofSeconds(1)).connected();
    fixture.faultsAnswer().labReads(2);
    fixture.panel.accept(Button.STORM);
    fixture.operations.press(Button.CLEAR.key);
    LastResult result = fixture.awaitResult("Clear all faults", Duration.ofSeconds(10));
    assertThat(result.error())
        .isEqualTo("Lab alerts were still firing after 3 s: Zabbix 2, Alertmanager 2.");
    assertThat(fixture.panel.status())
        .isEqualTo(
            "Clear all faults failed after 3 s: Lab alerts were still firing after 3 s: Zabbix 2,"
                + " Alertmanager 2.");

    fixture.lab.resetAll();
    fixture.lab.stubFor(
        post(urlEqualTo("/faults/clear"))
            .willReturn(
                aResponse()
                    .withStatus(500)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"error\":\"SL-F-2 clear failed (exit 1): read-only\"}")));
    fixture.panel.accept(Button.STORM);
    fixture.operations.press(Button.CLEAR.key);
    Fixture.until(
        () -> "SL-F-2 clear failed (exit 1): read-only".equals(fixture.lastResult().error()),
        Duration.ofSeconds(5));
    // demo-faults did not clear: the record keeps the faults.
    assertThat(fixture.panel.view().get("active")).asList().hasSize(2);

    fixture.lab.resetAll();
    fixture.lab.stubFor(post(urlEqualTo("/faults/clear")).willReturn(aResponse().withStatus(500)));
    fixture.operations.press(Button.CLEAR.key);
    Fixture.until(
        () -> "demo-faults answered HTTP 500".equals(fixture.lastResult().error()),
        Duration.ofSeconds(5));

    fixture.lab.stop();
    fixture.operations.press(Button.CLEAR.key);
    Fixture.until(
        () -> fixture.lastResult().error().startsWith("demo-faults: "), Duration.ofSeconds(5));
    assertThat(fixture.panel.view().get("active")).asList().hasSize(2);
  }
}
