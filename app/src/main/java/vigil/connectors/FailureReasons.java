package vigil.connectors;

import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/** The failure reasons of a connection test that got no answer (BP-A-22, CTX-D-043). */
final class FailureReasons {

  private FailureReasons() {}

  /**
   * The reason for {@code failure} of a call to {@code uri}; never a message that contains a value
   * of {@code hidden}.
   */
  static String of(Throwable failure, URI uri, ConnectorTimings timings, List<String> hidden) {
    List<Throwable> chain = chain(failure);
    String host = uri.getHost();
    if (any(chain, HttpConnectTimeoutException.class)) {
      return "No connection to "
          + host
          + ":"
          + port(uri)
          + " within "
          + timings.connect().toSeconds()
          + " s.";
    }
    if (any(chain, HttpTimeoutException.class) || any(chain, TimeoutException.class)) {
      return "No answer within " + timings.monitoringTest().toSeconds() + " s.";
    }
    if (any(chain, UnresolvedAddressException.class) || any(chain, UnknownHostException.class)) {
      return "Host " + host + " not found.";
    }
    if (any(chain, ConnectException.class)) {
      return "Could not connect to " + host + ":" + port(uri) + ".";
    }
    String outermost = chain.getFirst().getClass().getSimpleName();
    String message =
        chain.stream()
            .map(Throwable::getMessage)
            .filter(m -> m != null && !m.isEmpty())
            .findFirst()
            .orElse(outermost);
    if (message.endsWith(".")) {
      message = message.substring(0, message.length() - 1);
    }
    for (String value : hidden) {
      if (value != null && !value.isEmpty() && message.contains(value)) {
        message = outermost;
        break;
      }
    }
    return "Connection failed: " + message + ".";
  }

  private static List<Throwable> chain(Throwable failure) {
    Throwable outermost = failure;
    while ((outermost instanceof ExecutionException || outermost instanceof CompletionException)
        && outermost.getCause() != null) {
      outermost = outermost.getCause();
    }
    List<Throwable> chain = new ArrayList<>();
    for (Throwable t = outermost; t != null && !chain.contains(t); t = t.getCause()) {
      chain.add(t);
    }
    return chain;
  }

  private static boolean any(List<Throwable> chain, Class<? extends Throwable> type) {
    return chain.stream().anyMatch(type::isInstance);
  }

  private static int port(URI uri) {
    if (uri.getPort() != -1) {
      return uri.getPort();
    }
    return "https".equals(uri.getScheme().toLowerCase(Locale.ROOT)) ? 443 : 80;
  }
}
