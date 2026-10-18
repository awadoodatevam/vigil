package vigil.connectors;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import vigil.connectors.api.ConnectionTestResult;
import vigil.connectors.api.ConnectorInstanceView;
import vigil.connectors.api.ConnectorKind;
import vigil.connectors.api.ParseResult;

/** The {@code zabbix} connector (PM-K-01, AR-K-01). */
@Component
class ZabbixConnector extends HttpSourceConnector {

  private static final Logger LOG = LoggerFactory.getLogger(ZabbixConnector.class);
  private static final String TEST_BODY =
      "{\"jsonrpc\":\"2.0\",\"method\":\"host.get\",\"params\":{\"output\":[\"hostid\"],"
          + "\"limit\":1},\"id\":1}";
  private static final int HTTP_OK = 200;
  private static final char LAST_HEADER_CHAR = 'ÿ';
  private static final char DELETE = '\u007F';

  ZabbixConnector(
      ConnectorRecords records, FileSecrets secrets, ConnectorHttp http, ConnectorTimings timings) {
    super(records, secrets, http, timings);
  }

  @Override
  public ConnectorKind kind() {
    return ConnectorKind.ZABBIX;
  }

  @Override
  public ParseResult parse(byte[] body, String contentType) {
    return ZabbixPayloads.parse(body);
  }

  @Override
  String target(ConnectorInstanceView instance) {
    return instance.address() + setting(instance, "api_path");
  }

  /** The token goes into a header as is, so it holds only characters a header value can hold. */
  @Override
  boolean usable(String secretName, String secret) {
    for (int i = 0; i < secret.length(); i++) {
      char c = secret.charAt(i);
      if ((c < ' ' && c != '\t') || c == DELETE || c > LAST_HEADER_CHAR) {
        LOG.warn("Secret {} is not available.", secretName);
        return false;
      }
    }
    return true;
  }

  @Override
  HttpRequest.Builder request(ConnectorInstanceView instance, URI uri, String secret) {
    return HttpRequest.newBuilder(uri)
        .header("Content-Type", "application/json-rpc")
        .header("Authorization", "Bearer " + secret)
        .POST(HttpRequest.BodyPublishers.ofString(TEST_BODY, StandardCharsets.UTF_8));
  }

  @Override
  List<String> hidden(ConnectorInstanceView instance, String secret) {
    return List.of(secret);
  }

  @Override
  ConnectionTestResult answer(HttpResponse<byte[]> response, String secret) {
    JsonNode answer = json(response.body());
    if (answer != null && answer.isObject()) {
      JsonNode result = answer.get("result");
      if (response.statusCode() == HTTP_OK && result != null && result.isArray()) {
        return new ConnectionTestResult.Passed();
      }
      JsonNode error = answer.get("error");
      if (error != null && error.isObject()) {
        String data = JsonBodies.present(error, "data");
        if (data != null && !data.contains(secret)) {
          return new ConnectionTestResult.Failed(data);
        }
      }
    }
    return new ConnectionTestResult.Failed("HTTP " + response.statusCode());
  }
}
