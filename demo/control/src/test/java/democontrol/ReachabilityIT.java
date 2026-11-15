package democontrol;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReachabilityIT {

  @TempDir Path folder;
  private Fixture fixture;

  @BeforeEach
  void start() throws Exception {
    fixture = new Fixture(folder, Duration.ofSeconds(5), Duration.ofMillis(200));
  }

  @AfterEach
  void stop() {
    fixture.close();
  }

  private Reachability reachability() {
    return new Reachability(fixture.panel, fixture.labClient, fixture.vigilClient);
  }

  @Test
  @DisplayName("DC-S12-05 Cannot reach the lab and Cannot reach Techfinity")
  void cannotReach() {
    fixture.lab.stubFor(
        post(urlEqualTo("/api_jsonrpc.php")).willReturn(aResponse().withStatus(502)));
    fixture.vigil.stubFor(
        get(urlEqualTo("/api/v1/public/branding")).willReturn(aResponse().withStatus(404)));
    reachability().check();
    assertThat(fixture.panel.status())
        .isEqualTo("Cannot reach the lab: Zabbix: answered HTTP 502.");

    fixture.labReads(4);
    fixture.lab.stubFor(get(urlEqualTo("/api/v2/alerts")).willReturn(aResponse().withStatus(401)));
    reachability().check();
    assertThat(fixture.panel.status())
        .isEqualTo("Cannot reach the lab: Alertmanager: answered HTTP 401.");

    fixture.lab.resetAll();
    fixture.labReads(4);
    reachability().check();
    // Vigil answered 404: any HTTP status is an answer.
    assertThat(fixture.panel.status()).isEqualTo("Idle");

    Settings closed =
        new Settings(
            fixture.settings.vigilUrl(),
            "http://127.0.0.1:1",
            fixture.settings.zabbixUrl(),
            fixture.settings.alertmanagerUrl(),
            fixture.settings.values(),
            fixture.settings.stateFile(),
            fixture.settings.secrets(),
            true,
            "Techfinity",
            "vigil.test");
    new Reachability(fixture.panel, new Lab(closed, fixture.secrets), fixture.vigilClient).check();
    assertThat(fixture.panel.status())
        .startsWith("Cannot reach the lab: demo-faults: ")
        .endsWith(".");

    fixture.vigil.stop();
    reachability().check();
    assertThat(fixture.panel.status()).startsWith("Cannot reach Techfinity: ").endsWith(".");
    assertThat(fixture.panel.status()).isNotEqualTo("Cannot reach Techfinity: .");

    // a clear uses the counts of the latest successful lab read until its own first read.
    fixture.panel.accept(Button.STORM);
    fixture.panel.accept(Button.CLEAR);
    assertThat(fixture.panel.status()).endsWith("firing lab alerts: Zabbix 4, Alertmanager 4");
  }

  @Test
  @DisplayName("DC-S12-01 not connected until both checks have answered")
  void connecting() {
    assertThat(fixture.panel.connected()).isFalse();
    assertThat(fixture.panel.view()).containsEntry("connected", false);
    assertThat(fixture.operations.press(Button.RESET.key))
        .isEqualTo(new Operations.Press(true, "Connecting…"));
    fixture.vigil.stubFor(get(urlEqualTo("/api/v1/public/branding")).willReturn(okJson("{}")));
    fixture.lab.stubFor(
        post(urlEqualTo("/api_jsonrpc.php")).willReturn(aResponse().withStatus(500)));
    reachability().check();
    // both checks answered, one with a failure: connected, with the failure as the status.
    assertThat(fixture.panel.connected()).isTrue();
    assertThat(fixture.panel.view()).containsEntry("connected", true);
    assertThat(fixture.panel.status())
        .isEqualTo("Cannot reach the lab: Zabbix: answered HTTP 500.");
  }
}
