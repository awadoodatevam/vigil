package democontrol;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResetIT {

  private static final String ALIAS = "/api/v1/installation/aliases/demo.vigil.test";
  private static final String READ = "/api/v1/installation/seed-imports/" + Fixture.IMPORT;

  @TempDir Path folder;
  private Fixture fixture;

  private Fixture fixture(Duration limit) throws Exception {
    fixture = new Fixture(folder, limit, Duration.ofMillis(500)).connected();
    return fixture;
  }

  @AfterEach
  void stop() {
    if (fixture != null) {
      fixture.close();
    }
  }

  private static List<LoggedRequest> requests(
      com.github.tomakehurst.wiremock.WireMockServer server) {
    return Fixture.journal(server).stream().map(e -> (LoggedRequest) e.getRequest()).toList();
  }

  private static long last(List<LoggedRequest> requests, String url) {
    return requests.stream()
        .filter(r -> r.getUrl().equals(url))
        .mapToLong(r -> r.getLoggedDate().getTime())
        .max()
        .orElseThrow();
  }

  private static String tenantName(LoggedRequest request) {
    tools.jackson.databind.JsonNode body = Http.JSON.readTree(request.getBodyAsString());
    return body.path("name").asString();
  }

  private LastResult reset() {
    assertThat(fixture.operations.press(Button.RESET.key).refusal()).isNull();
    return fixture.awaitResult("Reset", Duration.ofSeconds(20));
  }

  @Test
  @DisplayName("AR-B-03 both branches at once, the alias last")
  void bothBranches() throws Exception {
    fixture(Duration.ofSeconds(20)).faultsAnswer().labReads(1, 0).vigilAnswers(Fixture.succeeded());
    LastResult result = reset();
    assertThat(result.outcome()).isEqualTo("completed");
    assertThat(fixture.panel.status()).matches("Reset completed in \\d+ s");
    List<LoggedRequest> lab = requests(fixture.lab);
    List<LoggedRequest> vigil = requests(fixture.vigil);
    long started =
        Math.min(lab.get(0).getLoggedDate().getTime(), vigil.get(0).getLoggedDate().getTime());
    // both branches started at once: the clear and the tenant within 1 s of the start.
    assertThat(lab.get(0).getUrl()).isEqualTo("/faults/clear");
    assertThat(vigil.get(0).getUrl()).isEqualTo("/api/v1/installation/tenants");
    assertThat(lab.get(0).getLoggedDate().getTime() - started).isLessThan(1000);
    assertThat(vigil.get(0).getLoggedDate().getTime() - started).isLessThan(1000);
    long alias = last(vigil, ALIAS);
    assertThat(vigil.getLast().getUrl()).isEqualTo(ALIAS);
    assertThat(alias).isGreaterThanOrEqualTo(last(vigil, READ));
    assertThat(alias).isGreaterThanOrEqualTo(last(lab, "/api/v2/alerts"));
    // the settle time after the quiet read lies before the alias.
    assertThat(alias - last(lab, "/api/v2/alerts")).isGreaterThanOrEqualTo(500);
    assertThat(result.branchASeconds()).isNotNull();
    assertThat(result.branchBSeconds()).isNotNull();
    assertThat(result.quietSeconds()).isNotNull();
    assertThat(result.seconds()).isGreaterThanOrEqualTo(result.branchASeconds());
    assertThat(result.seconds()).isGreaterThanOrEqualTo(result.branchBSeconds());
  }

  @Test
  @DisplayName("AR-B-01 the alias of demo.{demo_domain} is set to the new tenant")
  void alias() throws Exception {
    fixture(Duration.ofSeconds(20)).faultsAnswer().labReads(0).vigilAnswers(Fixture.succeeded());
    assertThat(reset().completed()).isTrue();
    fixture.vigil.verify(
        putRequestedFor(urlEqualTo(ALIAS))
            .withHeader("Content-Type", equalTo("application/json"))
            .withRequestBody(equalToJson("{\"tenant_id\":\"" + Fixture.TENANT + "\"}")));
    LoggedRequest upload =
        requests(fixture.vigil).stream()
            .filter(r -> r.getUrl().endsWith("/seed-imports"))
            .findFirst()
            .orElseThrow();
    assertThat(upload.getHeader("Content-Type")).startsWith("multipart/form-data; boundary=");
    String body = upload.getBodyAsString();
    assertThat(body)
        .contains("Content-Disposition: form-data; name=\"bundle\"; filename=\"bundle.zip\"")
        .contains("Content-Type: application/zip");
  }

  @Test
  @DisplayName("AR-B-03 a failed import leaves the alias unchanged")
  void failedImport() throws Exception {
    fixture(Duration.ofSeconds(20))
        .faultsAnswer()
        .labReads(0)
        .vigilAnswers(
            Fixture.seedImport(
                "failed",
                "[{\"module\":\"identity\",\"outcome\":\"succeeded\",\"counts\":{},\"error\":null},"
                    + "{\"module\":\"servicemap\",\"outcome\":\"failed\",\"counts\":{},"
                    + "\"error\":\"Unknown component db-02.\"},"
                    + "{\"module\":\"intake\",\"outcome\":\"failed\",\"counts\":{},\"error\":null},"
                    + "{\"module\":\"routing\",\"outcome\":\"not_started\",\"counts\":{},"
                    + "\"error\":null}]"));
    LastResult result = reset();
    assertThat(result.outcome()).isEqualTo("failed");
    assertThat(result.error())
        .isEqualTo("The seed import failed: servicemap: Unknown component db-02.; intake: failed");
    assertThat(requests(fixture.vigil)).noneMatch(r -> r.getUrl().equals(ALIAS));
  }

  @Test
  @DisplayName("AR-B-03 an error of demo-faults fails the reset at once and stops branch B")
  void faultsError() throws Exception {
    fixture(Duration.ofSeconds(20)).labReads(0);
    fixture.vigilAnswers(Fixture.seedImport("running", "[]"));
    fixture.vigil.stubFor(
        get(urlEqualTo(READ)).willReturn(okJson(Fixture.seedImport("running", "[]"))));
    fixture.lab.stubFor(
        post(urlEqualTo("/faults/clear"))
            .willReturn(
                aResponse()
                    .withStatus(500)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"error\":\"SL-F-1 clear failed (exit 1): busy\"}")
                    .withFixedDelay(1500)));
    LastResult result = reset();
    assertThat(result.outcome()).isEqualTo("failed");
    assertThat(result.error()).isEqualTo("SL-F-1 clear failed (exit 1): busy");
    assertThat(result.seconds()).isLessThan(new BigDecimal("3"));
    int reads = (int) requests(fixture.vigil).stream().filter(r -> r.getUrl().equals(READ)).count();
    assertThat(reads).isGreaterThanOrEqualTo(1);
    Fixture.sleep(Duration.ofMillis(2500));
    assertThat(requests(fixture.vigil).stream().filter(r -> r.getUrl().equals(READ)).count())
        .isLessThanOrEqualTo(reads + 1);
    assertThat(requests(fixture.vigil)).noneMatch(r -> r.getUrl().equals(ALIAS));
  }

  @Test
  @DisplayName("AR-B-03 a branch over 58 s fails the reset")
  void overTheLimit() throws Exception {
    fixture(Duration.ofSeconds(3)).faultsAnswer().labReads(0);
    fixture.vigilAnswers(Fixture.seedImport("running", "[]"));
    fixture.vigil.stubFor(
        get(urlEqualTo(READ)).willReturn(okJson(Fixture.seedImport("running", "[]"))));
    LastResult result = reset();
    assertThat(result.error()).isEqualTo("The seed import was still running after 3 s.");
    assertThat(result.seconds()).isBetween(new BigDecimal("3.0"), new BigDecimal("4.5"));
    assertThat(requests(fixture.vigil)).noneMatch(r -> r.getUrl().equals(ALIAS));

    fixture.vigil.stubFor(get(urlEqualTo(READ)).willReturn(aResponse().withStatus(503)));
    result = reset();
    assertThat(result.error())
        .isEqualTo("The seed import could not be read: Techfinity answered HTTP 503.");

    fixture.lab.stubFor(
        post(urlEqualTo("/api_jsonrpc.php"))
            .willReturn(okJson("{\"jsonrpc\":\"2.0\",\"result\":[{\"eventid\":\"1\"}],\"id\":1}")));
    fixture.vigil.stubFor(get(urlEqualTo(READ)).willReturn(okJson(Fixture.succeeded())));
    result = reset();
    assertThat(result.error())
        .isEqualTo("Lab alerts were still firing after 3 s: Zabbix 1, Alertmanager 0.");
    assertThat(result.branchBSeconds()).isNotNull();
    assertThat(result.branchASeconds()).isNull();
    assertThat(requests(fixture.vigil)).noneMatch(r -> r.getUrl().equals(ALIAS));

    fixture.vigil.stubFor(
        post(urlEqualTo("/api/v1/installation/tenants"))
            .willReturn(
                aResponse()
                    .withStatus(422)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"error\":{\"code\":\"invalid\",\"message\":\"The request has problems.\"}}")));
    assertThat(reset().error()).isEqualTo("The request has problems.");
    fixture.vigil.stubFor(
        post(urlEqualTo("/api/v1/installation/tenants")).willReturn(aResponse().withStatus(500)));
    assertThat(reset().error()).isEqualTo("Techfinity answered HTTP 500");
    fixture.vigil.stop();
    assertThat(reset().error()).startsWith("Techfinity: ");
  }

  @Test
  @DisplayName("AR-B-03 the record after a failed reset")
  @SuppressWarnings("unchecked")
  void recordAfterFailure() throws Exception {
    fixture(Duration.ofSeconds(20)).faultsAnswer().labReads(0);
    fixture.vigilAnswers(Fixture.seedImport("failed", "[]"));
    fixture.panel.accept(Button.STORM);
    fixture.panel.accept(Button.CUT_CLOUD_AI);
    LastResult result = reset();
    assertThat(result.outcome()).isEqualTo("failed");
    assertThat((List<Map<String, Object>>) fixture.panel.view().get("active")).isEmpty();

    fixture.lab.stubFor(post(urlMatching("/faults/clear")).willReturn(aResponse().withStatus(500)));
    fixture.panel.accept(Button.STORM_READ_ONLY);
    result = reset();
    assertThat(result.error()).isEqualTo("demo-faults answered HTTP 500");
    assertThat((List<Map<String, Object>>) fixture.panel.view().get("active")).hasSize(2);
    assertThat(fixture.panel.status())
        .isEqualTo("Reset failed after 0 s: demo-faults answered HTTP 500");
  }

  @Test
  @DisplayName("AR-B-02 every reset creates a tenant named jazz-demo {UTC time}")
  void newTenantEveryReset() throws Exception {
    fixture(Duration.ofSeconds(20)).faultsAnswer().labReads(0).vigilAnswers(Fixture.succeeded());
    assertThat(reset().completed()).isTrue();
    fixture.vigil.resetScenarios();
    Fixture.sleep(Duration.ofMillis(1100));
    assertThat(reset().completed()).isTrue();
    List<String> names =
        requests(fixture.vigil).stream()
            .filter(r -> r.getUrl().equals("/api/v1/installation/tenants"))
            .map(ResetIT::tenantName)
            .toList();
    assertThat(names).hasSize(2).doesNotHaveDuplicates();
    Pattern form = Pattern.compile("jazz-demo \\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z");
    assertThat(names).allMatch(name -> form.matcher(name).matches());
  }

  @Test
  @DisplayName(
      "AR-S-07 Vigil is called with the installation token and only through AR-I-10 and AR-I-95"
          + " to AR-I-98")
  void onlyInstallationOperations() throws Exception {
    fixture(Duration.ofSeconds(20)).faultsAnswer().labReads(0).vigilAnswers(Fixture.succeeded());
    assertThat(reset().completed()).isTrue();
    fixture.vigil.stubFor(get(urlEqualTo("/api/v1/public/branding")).willReturn(okJson("{}")));
    new Reachability(fixture.panel, fixture.labClient, fixture.vigilClient).check();
    List<LoggedRequest> requests = requests(fixture.vigil);
    Pattern allowed =
        Pattern.compile(
            "(GET /api/v1/public/branding|POST /api/v1/installation/tenants"
                + "|POST /api/v1/installation/tenants/[0-9a-f-]+/seed-imports"
                + "|GET /api/v1/installation/seed-imports/[0-9a-f-]+"
                + "|PUT /api/v1/installation/aliases/demo\\.vigil\\.test)");
    assertThat(requests).hasSizeGreaterThanOrEqualTo(5);
    for (LoggedRequest request : requests) {
      assertThat(allowed.matcher(request.getMethod() + " " + request.getUrl()).matches())
          .as(request.getUrl())
          .isTrue();
      if (request.getUrl().startsWith("/api/v1/installation/")) {
        assertThat(request.getHeader("Authorization"))
            .isEqualTo("Bearer " + fixture.installationToken);
      } else {
        assertThat(request.containsHeader("Authorization")).isFalse();
      }
    }
    assertThat(requests(fixture.lab).stream().map(LoggedRequest::getUrl))
        .allMatch(
            url ->
                url.startsWith("/faults/")
                    || url.equals("/api_jsonrpc.php")
                    || url.equals("/api/v2/alerts"));
  }

  @Test
  @DisplayName("DM-R-24 a reset is complete only after a quiet lab")
  void quietLab() throws Exception {
    fixture(Duration.ofSeconds(20))
        .faultsAnswer()
        .labReads(3, 2, 0)
        .vigilAnswers(Fixture.succeeded());
    LastResult result = reset();
    assertThat(result.completed()).isTrue();
    List<LoggedRequest> lab = requests(fixture.lab);
    List<LoggedRequest> alerts =
        lab.stream().filter(r -> r.getUrl().equals("/api/v2/alerts")).toList();
    assertThat(alerts).hasSize(3);
    long quiet = alerts.getLast().getLoggedDate().getTime();
    assertThat(last(requests(fixture.vigil), ALIAS) - quiet).isGreaterThanOrEqualTo(500);
    assertThat(result.quietSeconds()).isGreaterThan(new BigDecimal("3.5"));
  }

  @Test
  @DisplayName("AR-B-04 the alias moves last, after the lab clear")
  void aliasLast() throws Exception {
    fixture(Duration.ofSeconds(20))
        .faultsAnswer()
        .labReads(1, 1, 0)
        .vigilAnswers(Fixture.succeeded());
    LastResult result = reset();
    assertThat(result.completed()).isTrue();
    // branch B is ready long before the lab is clear; the alias waits for both.
    assertThat(result.branchBSeconds()).isLessThan(result.branchASeconds());
    assertThat(last(requests(fixture.vigil), ALIAS))
        .isGreaterThanOrEqualTo(last(requests(fixture.lab), "/api/v2/alerts") + 500);
    assertThat(result.seconds()).isGreaterThanOrEqualTo(result.branchASeconds());
  }

  @Test
  @DisplayName("AR-TM-10 each branch is limited to 58 s, within the 60 s reset")
  void timingLimits() {
    fixture = null;
    assertThat(Wiring.LIMIT).isEqualTo(Duration.ofSeconds(58));
    assertThat(Wiring.SETTLE).isEqualTo(Duration.ofSeconds(12));
    assertThat(Wiring.LIMIT).isLessThan(Duration.ofSeconds(60));
  }
}
