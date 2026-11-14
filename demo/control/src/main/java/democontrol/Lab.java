package democontrol;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import tools.jackson.databind.JsonNode;

/**
 * The lab: demo-faults (AR-X-03; one call at a time, in the order asked), and Zabbix's and
 * Alertmanager's read-only APIs (AR-B-03 branch A).
 */
class Lab {

  /** The five Alertmanager catalogue alert names of 03-monitoring.yaml. */
  static final List<String> CATALOGUE_ALERTS =
      List.of(
          "NodeFilesystemFull",
          "BalanceApiErrors",
          "BundleApiErrors",
          "UssdGatewayTimeouts",
          "SyntheticUssdSessionFailed");

  static final List<String> ALL_FAULTS = List.of("SL-F-1", "SL-F-2", "SL-F-3", "SL-F-4");
  static final Duration INJECT_TIMEOUT = Duration.ofSeconds(65);
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

  /** The counts of one successful lab read. */
  record Counts(int zabbix, int alertmanager) {
    boolean quiet() {
      return zabbix == 0 && alertmanager == 0;
    }
  }

  private final Settings settings;
  private final Secrets secrets;
  private final ReentrantLock faultsCalls = new ReentrantLock(true);

  Lab(Settings settings, Secrets secrets) {
    this.settings = settings;
    this.secrets = secrets;
  }

  /** POST /faults/{fault}/inject (65 s). */
  void inject(String fault) throws Failed {
    faults("/faults/" + fault + "/inject", Map.of(), INJECT_TIMEOUT);
  }

  /** POST /faults/clear with the four faults, within the given time. */
  void clearAll(Duration timeout) throws Failed {
    faults("/faults/clear", Map.of("faults", ALL_FAULTS), timeout);
  }

  private void faults(String path, Object body, Duration timeout) throws Failed {
    long started = System.nanoTime();
    try {
      if (!faultsCalls.tryLock(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
        throw new Failed("demo-faults: request timed out");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new Failed("demo-faults: interrupted");
    }
    try {
      Duration left = timeout.minusNanos(System.nanoTime() - started);
      Http.Answer answer;
      try {
        answer =
            Http.send(
                Http.request(settings.faultsUrl() + path, left)
                    .header("Authorization", "Bearer " + secrets.value("faults-token"))
                    .header("Content-Type", "application/json")
                    .POST(Http.json(body))
                    .build());
      } catch (IOException e) {
        throw new Failed("demo-faults: " + Failed.text(e));
      }
      if (answer.status() != 200) {
        JsonNode json = answer.json();
        JsonNode error = json == null ? null : json.get("error");
        throw new Failed(
            error != null && error.isString()
                ? error.stringValue()
                : "demo-faults answered HTTP " + answer.status());
      }
    } finally {
      faultsCalls.unlock();
    }
  }

  /** Zabbix problem.get (tag vigil=lab) and Alertmanager's catalogue alerts. */
  Counts read() throws Failed {
    int zabbix = zabbixProblems();
    int alertmanager = alertmanagerAlerts();
    return new Counts(zabbix, alertmanager);
  }

  private int zabbixProblems() throws Failed {
    Map<String, Object> body =
        Map.of(
            "jsonrpc",
            "2.0",
            "method",
            "problem.get",
            "params",
            Map.of(
                "output",
                List.of("eventid", "name"),
                "tags",
                List.of(Map.of("tag", "vigil", "value", "lab", "operator", 1))),
            "id",
            1);
    Http.Answer answer;
    try {
      answer =
          Http.send(
              Http.request(settings.zabbixUrl(), READ_TIMEOUT)
                  .header("Authorization", "Bearer " + secrets.value("zabbix-api"))
                  .header("Content-Type", "application/json-rpc")
                  .POST(Http.json(body))
                  .build());
    } catch (IOException e) {
      throw new Failed("Zabbix: " + Failed.text(e));
    }
    JsonNode json = answer.json();
    JsonNode result = json == null ? null : json.get("result");
    if (answer.status() != 200 || result == null || !result.isArray()) {
      throw new Failed("Zabbix: answered HTTP " + answer.status());
    }
    return result.size();
  }

  private int alertmanagerAlerts() throws Failed {
    String basic =
        Base64.getEncoder()
            .encodeToString(
                ("vigil:" + secrets.value("alertmanager-password"))
                    .getBytes(StandardCharsets.UTF_8));
    Http.Answer answer;
    try {
      answer =
          Http.send(
              Http.request(settings.alertmanagerUrl() + "/api/v2/alerts", READ_TIMEOUT)
                  .header("Authorization", "Basic " + basic)
                  .header("Accept", "application/json")
                  .GET()
                  .build());
    } catch (IOException e) {
      throw new Failed("Alertmanager: " + Failed.text(e));
    }
    JsonNode alerts = answer.json();
    if (answer.status() != 200 || alerts == null || !alerts.isArray()) {
      throw new Failed("Alertmanager: answered HTTP " + answer.status());
    }
    int count = 0;
    for (JsonNode alert : alerts) {
      JsonNode name = alert.path("labels").path("alertname");
      if (name.isString() && CATALOGUE_ALERTS.contains(name.stringValue())) {
        count++;
      }
    }
    return count;
  }

  /** A TCP connection to demo-faults within 2 s. */
  void connectFaults() throws Failed {
    URI uri = URI.create(settings.faultsUrl());
    int port = uri.getPort() < 0 ? 80 : uri.getPort();
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(uri.getHost(), port), 2000);
    } catch (IOException e) {
      throw new Failed("demo-faults: " + Failed.text(e));
    }
  }
}
