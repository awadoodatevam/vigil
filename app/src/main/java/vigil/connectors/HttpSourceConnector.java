package vigil.connectors;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import vigil.connectors.ConnectorRecords.StoredCredential;
import vigil.connectors.api.ConnectionTestResult;
import vigil.connectors.api.ConnectorInstanceView;
import vigil.connectors.api.MonitoringSourceConnector;
import vigil.shared.TransactionGuard;

/**
 * The connection test common to the HTTP monitoring sources (AR-K-01, AR-K-02): address, secret,
 * request, answer, and the failure reasons of {@code 50-architecture} Section 12.1.
 */
abstract class HttpSourceConnector implements MonitoringSourceConnector {

  static final String INVALID_ADDRESS = "The address is not a valid http or https address.";
  private static final int REASON_MAX = 500;
  private static final int PORT_MAX = 65535;

  private final ConnectorRecords records;
  private final FileSecrets secrets;
  private final ConnectorHttp http;
  private final ConnectorTimings timings;

  HttpSourceConnector(
      ConnectorRecords records, FileSecrets secrets, ConnectorHttp http, ConnectorTimings timings) {
    this.records = records;
    this.secrets = secrets;
    this.http = http;
    this.timings = timings;
  }

  /** The address of the test request as text, before it is checked. */
  abstract String target(ConnectorInstanceView instance);

  /** Whether the secret can be sent; a secret that cannot is "not available". */
  boolean usable(String secretName, String secret) {
    return true;
  }

  /** The request without its timeout. */
  abstract HttpRequest.Builder request(ConnectorInstanceView instance, URI uri, String secret);

  /** The values no reason may contain: the secret and anything made from it. */
  abstract List<String> hidden(ConnectorInstanceView instance, String secret);

  /** The result for a received answer. */
  abstract ConnectionTestResult answer(HttpResponse<byte[]> response, String secret);

  /**
   * {@inheritDoc}
   *
   * <p>external call: not inside a transaction
   */
  @Override
  public final ConnectionTestResult test(ConnectorInstanceView instance) {
    TransactionGuard.assertNoTransaction();
    if (instance == null) {
      throw new IllegalArgumentException("instance: required");
    }
    if (instance.kind() != kind()) {
      throw new IllegalArgumentException(
          "connector instance " + instance.id() + " is not of kind " + kind().key());
    }
    Optional<URI> uri = address(target(instance));
    if (uri.isEmpty()) {
      return failed(INVALID_ADDRESS);
    }
    UUID credentialRefId = instance.credentialRefId();
    Optional<StoredCredential> credential =
        credentialRefId == null
            ? Optional.empty()
            : records.credential(instance.tenantId(), credentialRefId);
    if (credential.isEmpty()) {
      return failed("No credential is set.");
    }
    String name = credential.get().secretName();
    Optional<String> secret = secrets.readNamed(name);
    if (secret.isEmpty() || !usable(name, secret.get())) {
      return failed("Secret " + name + " is not available.");
    }
    HttpRequest request;
    try {
      request =
          request(instance, uri.get(), secret.get()).timeout(timings.monitoringTest()).build();
    } catch (RuntimeException e) {
      return failed(INVALID_ADDRESS);
    }
    HttpResponse<byte[]> response;
    try {
      response = http.send(request);
    } catch (Exception e) {
      return failed(FailureReasons.of(e, uri.get(), timings, hidden(instance, secret.get())));
    }
    ConnectionTestResult result = answer(response, secret.get());
    return result instanceof ConnectionTestResult.Failed failed ? failed(failed.reason()) : result;
  }

  /** The string setting {@code key} of the instance. */
  static String setting(ConnectorInstanceView instance, String key) {
    JsonNode value = instance.settings() == null ? null : instance.settings().get(key);
    if (value == null || !value.isString()) {
      throw new IllegalStateException(
          "connector instance " + instance.id() + " has no " + key + " setting");
    }
    return value.stringValue();
  }

  /** The answer body as JSON, or {@code null} when it is not JSON. */
  static JsonNode json(byte[] body) {
    try {
      return JsonBodies.JSON.readTree(body);
    } catch (RuntimeException e) {
      return null;
    }
  }

  static ConnectionTestResult.Failed failed(String reason) {
    if (reason.codePointCount(0, reason.length()) > REASON_MAX) {
      reason = reason.substring(0, reason.offsetByCodePoints(0, REASON_MAX));
    }
    return new ConnectionTestResult.Failed(reason);
  }

  private static Optional<URI> address(String text) {
    URI uri;
    try {
      uri = new URI(text);
    } catch (URISyntaxException e) {
      return Optional.empty();
    }
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    boolean http = scheme.equals("http") || scheme.equals("https");
    boolean port = uri.getPort() == -1 || (uri.getPort() >= 1 && uri.getPort() <= PORT_MAX);
    return http && uri.getHost() != null && port ? Optional.of(uri) : Optional.empty();
  }
}
