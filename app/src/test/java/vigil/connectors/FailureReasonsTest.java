package vigil.connectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.UnresolvedAddressException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vigil.testsupport.TestSecrets;

class FailureReasonsTest {

  private static final ConnectorTimings TIMINGS =
      new ConnectorTimings(Duration.ofSeconds(5), Duration.ofSeconds(10));
  private static final URI ALERTMANAGER = URI.create("http://lab-alertmanager:9093/api/v2/status");
  private static final URI DEFAULT_PORT = URI.create("https://x.test/api");

  private static String reason(Throwable failure, URI uri, List<String> hidden) {
    return FailureReasons.of(failure, uri, TIMINGS, hidden);
  }

  private static <T extends Throwable> T caused(T failure, Throwable cause) {
    failure.initCause(cause);
    return failure;
  }

  @Test
  @DisplayName("BP-A-22 connection test failure reasons from the cause chain")
  void reasons() throws Exception {
    Map<Throwable, String> cases = new LinkedHashMap<>();
    cases.put(
        new HttpConnectTimeoutException("HTTP connect timed out"),
        "No connection to lab-alertmanager:9093 within 5 s.");
    cases.put(new HttpTimeoutException("request timed out"), "No answer within 10 s.");
    cases.put(new TimeoutException(), "No answer within 10 s.");
    cases.put(
        caused(new ConnectException(), new UnresolvedAddressException()),
        "Host lab-alertmanager not found.");
    cases.put(new UnknownHostException("lab-alertmanager"), "Host lab-alertmanager not found.");
    cases.put(
        caused(new ConnectException(), new ClosedChannelException()),
        "Could not connect to lab-alertmanager:9093.");
    cases.put(
        new ConnectException("HTTP connect timed out"),
        "Could not connect to lab-alertmanager:9093.");
    cases.put(new IOException("connection reset."), "Connection failed: connection reset.");
    cases.put(new IOException(), "Connection failed: IOException.");
    for (Map.Entry<Throwable, String> entry : cases.entrySet()) {
      Throwable failure = entry.getKey();
      assertThat(reason(failure, ALERTMANAGER, List.of()))
          .as("%s", failure)
          .isEqualTo(entry.getValue());
      assertThat(reason(new ExecutionException(failure), ALERTMANAGER, List.of()))
          .isEqualTo(entry.getValue());
      assertThat(reason(new CompletionException(failure), ALERTMANAGER, List.of()))
          .isEqualTo(entry.getValue());
    }
    assertThat(reason(new ConnectException(), DEFAULT_PORT, List.of()))
        .isEqualTo("Could not connect to x.test:443.");
    String hidden = TestSecrets.randomToken();
    assertThat(reason(new IOException("bad " + hidden), ALERTMANAGER, List.of(hidden)))
        .isEqualTo("Connection failed: IOException.");
    assertThat(reason(new InterruptedException(), ALERTMANAGER, List.of()))
        .isEqualTo("Connection failed: InterruptedException.");

    try (ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        ConnectorHttp http =
            new ConnectorHttp(new ConnectorTimings(Duration.ofSeconds(1), Duration.ofSeconds(2)))) {
      Thread.ofPlatform()
          .daemon()
          .start(
              () -> {
                try (Socket ignored = server.accept()) {
                  Thread.sleep(Duration.ofSeconds(5));
                } catch (IOException | InterruptedException e) {
                  // The test is over.
                }
              });
      HttpRequest request =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getLocalPort() + "/"))
              .timeout(Duration.ofSeconds(2))
              .build();
      Thread.currentThread().interrupt();
      long start = System.nanoTime();
      assertThatThrownBy(() -> http.send(request)).isInstanceOf(InterruptedException.class);
      assertThat(Thread.interrupted()).isTrue();
      assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(900));
    }
  }
}
