package vigil.connectors;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.springframework.stereotype.Component;
import vigil.connectors.api.ConnectionTestResult;
import vigil.connectors.api.ConnectorInstanceView;
import vigil.connectors.api.ConnectorKind;
import vigil.connectors.api.ParseResult;

/** The {@code alertmanager} connector (PM-K-02, AR-K-02). */
@Component
class AlertmanagerConnector extends HttpSourceConnector {

  private static final int HTTP_OK = 200;

  AlertmanagerConnector(
      ConnectorRecords records, FileSecrets secrets, ConnectorHttp http, ConnectorTimings timings) {
    super(records, secrets, http, timings);
  }

  @Override
  public ConnectorKind kind() {
    return ConnectorKind.ALERTMANAGER;
  }

  @Override
  public ParseResult parse(byte[] body, String contentType) {
    return AlertmanagerPayloads.parse(body);
  }

  @Override
  String target(ConnectorInstanceView instance) {
    setting(instance, "username");
    return instance.address() + "/api/v2/status";
  }

  @Override
  HttpRequest.Builder request(ConnectorInstanceView instance, URI uri, String secret) {
    return HttpRequest.newBuilder(uri)
        .header("Authorization", "Basic " + basic(instance, secret))
        .GET();
  }

  @Override
  List<String> hidden(ConnectorInstanceView instance, String secret) {
    return List.of(secret, basic(instance, secret));
  }

  @Override
  ConnectionTestResult answer(HttpResponse<byte[]> response, String secret) {
    return response.statusCode() == HTTP_OK
        ? new ConnectionTestResult.Passed()
        : new ConnectionTestResult.Failed("HTTP " + response.statusCode());
  }

  private static String basic(ConnectorInstanceView instance, String secret) {
    String pair = setting(instance, "username") + ":" + secret;
    return Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8));
  }
}
