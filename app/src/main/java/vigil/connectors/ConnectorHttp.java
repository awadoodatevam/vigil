package vigil.connectors;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;

/**
 * The JDK HTTP client of this module's connectors (AR-T-13): HTTP/1.1, explicit connect timeout, no
 * redirects, no proxy. Closed when the context closes.
 */
@Component
class ConnectorHttp implements AutoCloseable {

  private final ConnectorTimings timings;
  private final HttpClient client;

  ConnectorHttp(ConnectorTimings timings) {
    this.timings = timings;
    this.client =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(timings.connect())
            .followRedirects(HttpClient.Redirect.NEVER)
            .proxy(HttpClient.Builder.NO_PROXY)
            .build();
  }

  HttpClient client() {
    return client;
  }

  /**
   * Sends the request and waits at most the monitoring test timeout for the complete answer; a
   * timed-out or interrupted wait aborts the exchange.
   */
  HttpResponse<byte[]> send(HttpRequest request) throws Exception {
    CompletableFuture<HttpResponse<byte[]>> answer =
        client.sendAsync(request, BodyHandlers.ofByteArray());
    try {
      return answer.get(timings.monitoringTest().toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      answer.cancel(true);
      throw e;
    } catch (InterruptedException e) {
      answer.cancel(true);
      Thread.currentThread().interrupt();
      throw e;
    }
  }

  @Override
  public void close() {
    client.close();
  }
}
