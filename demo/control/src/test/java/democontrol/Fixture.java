package democontrol;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;

/**
 * The lab (demo-faults, Zabbix, Alertmanager) and Vigil as WireMock servers, generated secrets in a
 * temporary folder, and the objects of demo-control built on them (spec 6.9).
 */
final class Fixture implements AutoCloseable {

  static final String TENANT = "0b5f6c1e-2f43-4c1f-9d27-6a9f0e0d1a11";
  static final String IMPORT = "6f2d8a8e-8e0b-4b0e-b5f8-1b2c3d4e5f60";

  final WireMockServer lab = new WireMockServer(options().dynamicPort());
  final WireMockServer vigil = new WireMockServer(options().dynamicPort());
  final Path folder;
  final Path secretsFolder;
  final String installationToken = random();
  final String faultsToken = random();
  final String zabbixToken = random();
  final String alertmanagerPassword = random();
  final Settings settings;
  final Secrets secrets;
  final Clock clock;
  final Panel panel;
  final Lab labClient;
  final Vigil vigilClient;
  final Operations operations;

  Fixture(Path folder, Duration limit, Duration settle) throws IOException {
    this(folder, limit, settle, Clock.systemUTC());
  }

  Fixture(Path folder, Duration limit, Duration settle, Clock clock) throws IOException {
    this.folder = folder;
    this.clock = clock;
    lab.start();
    vigil.start();
    secretsFolder = Files.createDirectories(folder.resolve("secrets"));
    Files.writeString(secretsFolder.resolve("installation-token"), installationToken);
    Files.writeString(secretsFolder.resolve("faults-token"), faultsToken);
    Files.writeString(secretsFolder.resolve("zabbix-api"), zabbixToken);
    Files.writeString(secretsFolder.resolve("alertmanager-password"), alertmanagerPassword);
    settings =
        new Settings(
            vigil.baseUrl(),
            lab.baseUrl(),
            lab.baseUrl() + "/api_jsonrpc.php",
            lab.baseUrl(),
            List.of("file:../../deploy/values/local.yaml", "classpath:test-values/derived.yaml"),
            folder.resolve("state").resolve("state.json"),
            secretsFolder,
            true,
            "Techfinity",
            "vigil.test");
    secrets = new Secrets(secretsFolder);
    panel = new Panel(clock, settings.product(), settings.vigilUrl(), settings.stateFile());
    labClient = new Lab(settings, secrets);
    vigilClient = new Vigil(settings, secrets);
    operations =
        new Operations(panel, labClient, vigilClient, settings.values(), clock, limit, settle);
  }

  static String random() {
    byte[] bytes = new byte[24];
    new SecureRandom().nextBytes(bytes);
    return HexFormat.of().formatHex(bytes);
  }

  /** Both reachability checks answered, so the buttons can be pressed. */
  Fixture connected() {
    panel.labChecked(null, null);
    panel.vigilChecked(null);
    return this;
  }

  /** demo-faults answering every inject and clear with 200. */
  Fixture faultsAnswer() {
    lab.stubFor(
        post(urlMatching("/faults/.*")).willReturn(okJson("{\"active\": []}").withFixedDelay(50)));
    return this;
  }

  /** Zabbix and Alertmanager reads: the given numbers of firing alerts, one read after another. */
  Fixture labReads(int... counts) {
    String scenario = "lab";
    for (int index = 0; index < counts.length; index++) {
      String state = index == 0 ? Scenario.STARTED : "read-" + index;
      String next = index + 1 < counts.length ? "read-" + (index + 1) : state;
      StringBuilder problems = new StringBuilder("[");
      StringBuilder alerts = new StringBuilder("[");
      for (int alert = 0; alert < counts[index]; alert++) {
        problems
            .append(alert == 0 ? "" : ",")
            .append("{\"eventid\":\"")
            .append(alert)
            .append("\"}");
        alerts
            .append(alert == 0 ? "" : ",")
            .append("{\"labels\":{\"alertname\":\"BalanceApiErrors\"}}");
      }
      lab.stubFor(
          post(urlEqualTo("/api_jsonrpc.php"))
              .inScenario(scenario)
              .whenScenarioStateIs(state)
              .willReturn(okJson("{\"jsonrpc\":\"2.0\",\"result\":" + problems + "],\"id\":1}")));
      lab.stubFor(
          get(urlEqualTo("/api/v2/alerts"))
              .inScenario(scenario)
              .whenScenarioStateIs(state)
              .willSetStateTo(next)
              .willReturn(
                  okJson(
                      alerts
                          + (counts[index] == 0 ? "" : ",")
                          + "{\"labels\":{\"alertname\":\"Other\"}}]")));
    }
    return this;
  }

  /** AR-I-95 to AR-I-98 answering as Vigil does, the import ending with the given answer. */
  Fixture vigilAnswers(String importAnswer) {
    vigil.stubFor(
        post(urlEqualTo("/api/v1/installation/tenants"))
            .willReturn(okJson("{\"tenant_id\":\"" + TENANT + "\"}")));
    vigil.stubFor(
        post(urlEqualTo("/api/v1/installation/tenants/" + TENANT + "/seed-imports"))
            .willReturn(
                aResponse()
                    .withStatus(202)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"seed_import_id\":\"" + IMPORT + "\"}")));
    vigil.stubFor(
        get(urlEqualTo("/api/v1/installation/seed-imports/" + IMPORT))
            .inScenario("import")
            .whenScenarioStateIs(Scenario.STARTED)
            .willSetStateTo("done")
            .willReturn(okJson(seedImport("running", "[]"))));
    vigil.stubFor(
        get(urlEqualTo("/api/v1/installation/seed-imports/" + IMPORT))
            .inScenario("import")
            .whenScenarioStateIs("done")
            .willReturn(okJson(importAnswer)));
    vigil.stubFor(
        put(urlEqualTo("/api/v1/installation/aliases/demo.vigil.test"))
            .withHeader("Authorization", equalTo("Bearer " + installationToken))
            .willReturn(
                okJson(
                    "{\"host_name\":\"demo.vigil.test\",\"revision\":2,\"tenant_id\":\""
                        + TENANT
                        + "\",\"set_at\":\"2026-11-14T10:00:00Z\"}")));
    return this;
  }

  static String seedImport(String state, String parts) {
    return "{\"seed_import_id\":\""
        + IMPORT
        + "\",\"tenant_id\":\""
        + TENANT
        + "\",\"bundle_name\":\"jazz-demo\",\"bundle_version\":\"1.5\",\"state\":\""
        + state
        + "\",\"started_at\":\"2026-11-14T10:00:00Z\",\"finished_at\":null,\"parts\":"
        + parts
        + "}";
  }

  static String succeeded() {
    return seedImport(
        "succeeded",
        "[{\"module\":\"tenancy\",\"outcome\":\"succeeded\",\"counts\":{},\"error\":null}]");
  }

  /** Waits (at most the given time) for the last result of the given operation. */
  LastResult awaitResult(String operation, Duration within) {
    Instant end = Instant.now().plus(within);
    while (Instant.now().isBefore(end)) {
      Object last = panel.view().get("last_result");
      if (last != null && !panel.operationRunning()) {
        LastResult result = lastResult();
        if (result.operation().equals(operation)) {
          return result;
        }
      }
      sleep(Duration.ofMillis(20));
    }
    throw new AssertionError("no result of " + operation + " within " + within);
  }

  @SuppressWarnings("unchecked")
  LastResult lastResult() {
    java.util.Map<String, Object> map =
        (java.util.Map<String, Object>) panel.view().get("last_result");
    return map == null ? null : LastResult.of(Http.JSON.valueToTree(map));
  }

  static void sleep(Duration duration) {
    try {
      Thread.sleep(duration);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }

  static <T> T until(Supplier<T> value, Duration within) {
    Instant end = Instant.now().plus(within);
    while (true) {
      T found = value.get();
      if (found != null && !Boolean.FALSE.equals(found)) {
        return found;
      }
      if (Instant.now().isAfter(end)) {
        throw new AssertionError("not reached within " + within);
      }
      sleep(Duration.ofMillis(20));
    }
  }

  /** The requests a server received, oldest first. */
  static List<ServeEvent> journal(WireMockServer server) {
    List<ServeEvent> events = new ArrayList<>(server.getAllServeEvents());
    Collections.reverse(events);
    return events;
  }

  @Override
  public void close() {
    lab.stop();
    vigil.stop();
  }
}
