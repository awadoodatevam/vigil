package democontrol;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * The lab: demo-faults (AR-X-03; one call at a time, in the order asked), and Zabbix's and
 * Alertmanager's read-only APIs (AR-B-03 branch A).
 */
@Component
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
    faultsCalls.lock();
    try {
      Duration left = timeout.minusNanos(System.nanoTime() - started);
      Http.Answer answer =
          send(
              "demo-faults",
              Http.request(settings.faultsUrl() + path, left)
                  .header("Authorization", "Bearer " + secrets.value("faults-token"))
                  .header("Content-Type", "application/json")
                  .POST(Http.json(body)));
      JsonNode error = answer.json() == null ? null : answer.json().get("error");
      if (answer.status() != 200) {
        throw new Failed(
            error != null && error.isString()
                ? error.stringValue()
                : "demo-faults answered HTTP " + answer.status());
      }
    } finally {
      faultsCalls.unlock();
    }
  }

  private static Http.Answer send(String who, HttpRequest.Builder request) throws Failed {
    try {
      return Http.send(request.build());
    } catch (IOException e) {
      throw new Failed(who + ": " + Failed.text(e));
    }
  }

  /** The JSON array of an answer read with 200 (the whole answer, or the member given). */
  private static JsonNode array(String who, HttpRequest.Builder request, String member)
      throws Failed {
    Http.Answer answer = send(who, request);
    JsonNode json = answer.json();
    JsonNode array = json == null || member == null ? json : json.get(member);
    if (answer.status() != 200 || array == null || !array.isArray()) {
      throw new Failed(who + ": answered HTTP " + answer.status());
    }
    return array;
  }

  /** Zabbix problem.get (tag vigil=lab) and Alertmanager's catalogue alerts. */
  Counts read() throws Failed {
    String body =
        "{\"jsonrpc\":\"2.0\",\"method\":\"problem.get\",\"params\":{\"output\":[\"eventid\","
            + "\"name\"],\"tags\":[{\"tag\":\"vigil\",\"value\":\"lab\",\"operator\":1}]},\"id\":1}";
    JsonNode problems =
        array(
            "Zabbix",
            Http.request(settings.zabbixUrl(), READ_TIMEOUT)
                .header("Authorization", "Bearer " + secrets.value("zabbix-api"))
                .header("Content-Type", "application/json-rpc")
                .POST(HttpRequest.BodyPublishers.ofString(body)),
            "result");
    byte[] user = ("vigil:" + secrets.value("alertmanager-password")).getBytes(UTF_8);
    JsonNode alerts =
        array(
            "Alertmanager",
            Http.request(settings.alertmanagerUrl() + "/api/v2/alerts", READ_TIMEOUT)
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(user))
                .header("Accept", "application/json"),
            null);
    int count = 0;
    for (JsonNode alert : alerts) {
      JsonNode name = alert.path("labels").path("alertname");
      count += name.isString() && CATALOGUE_ALERTS.contains(name.stringValue()) ? 1 : 0;
    }
    return new Counts(problems.size(), count);
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
