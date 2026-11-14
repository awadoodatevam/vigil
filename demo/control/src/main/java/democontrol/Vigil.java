package democontrol;

import static java.net.http.HttpRequest.BodyPublishers.noBody;
import static java.net.http.HttpRequest.BodyPublishers.ofByteArray;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Vigil, reached only through vigil-api: AR-I-10 (reachability) and the installation operations
 * AR-I-95 to AR-I-98 with the installation token (AR-S-07).
 */
@Component
class Vigil {

  private static final String JSON = "application/json";

  private static final Duration BRANDING_TIMEOUT = Duration.ofSeconds(5);
  private static final SecureRandom RANDOM = new SecureRandom();

  private final Settings settings;
  private final Secrets secrets;

  Vigil(Settings settings, Secrets secrets) {
    this.settings = settings;
    this.secrets = secrets;
  }

  /** AR-I-10 answering any HTTP status within 5 s. */
  void branding() throws Failed {
    try {
      Http.send(
          Http.request(settings.vigilUrl() + "/api/v1/public/branding", BRANDING_TIMEOUT)
              .GET()
              .build());
    } catch (IOException e) {
      throw new Failed(Failed.text(e));
    }
  }

  /** AR-I-95: the new tenant's id. */
  String createTenant(String name, Duration timeout) throws Failed {
    JsonNode answer = call("POST", "/tenants", JSON, Http.json(Map.of("name", name)), timeout, 200);
    return text(answer, "tenant_id", 200);
  }

  /** AR-I-96 with the multipart field bundle: the seed import's id. */
  String importBundle(String tenantId, byte[] bundle, Duration timeout) throws Failed {
    String boundary = "demo-control-" + HexFormat.of().formatHex(randomBytes());
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    body.writeBytes(
        ("--%s\r\nContent-Disposition: form-data; name=\"bundle\"; filename=\"bundle.zip\"\r\n"
                + "Content-Type: application/zip\r\n\r\n")
            .formatted(boundary)
            .getBytes(StandardCharsets.US_ASCII));
    body.writeBytes(bundle);
    body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
    String path = "/tenants/" + tenantId + "/seed-imports";
    String type = "multipart/form-data; boundary=" + boundary;
    JsonNode answer = call("POST", path, type, ofByteArray(body.toByteArray()), timeout, 202);
    return text(answer, "seed_import_id", 202);
  }

  /** AR-I-97. */
  JsonNode readImport(String seedImportId, Duration timeout) throws Failed {
    return call("GET", "/seed-imports/" + seedImportId, null, noBody(), timeout, 200);
  }

  /** AR-I-98 for demo.{demo_domain}. */
  void setAlias(String tenantId, Duration timeout) throws Failed {
    String path = "/aliases/demo." + settings.demoDomain();
    call("PUT", path, JSON, Http.json(Map.of("tenant_id", tenantId)), timeout, 200);
  }

  /** An installation operation: its JSON answer with the expected status, else its error text. */
  private JsonNode call(
      String method, String path, String type, BodyPublisher body, Duration timeout, int expected)
      throws Failed {
    HttpRequest.Builder request =
        Http.request(settings.vigilUrl() + "/api/v1/installation" + path, timeout)
            .header("Authorization", "Bearer " + secrets.value("installation-token"))
            .header("Accept", JSON)
            .method(method, body);
    if (type != null) {
      request.header("Content-Type", type);
    }
    Http.Answer answer;
    try {
      answer = Http.send(request.build());
    } catch (IOException e) {
      throw new Failed(settings.product() + ": " + Failed.text(e));
    }
    JsonNode json = answer.json();
    JsonNode message = json == null ? null : json.path("error").path("message");
    if (answer.status() != expected && message != null && message.isString()) {
      throw new Failed(message.stringValue());
    }
    if (answer.status() != expected || json == null || !json.isObject()) {
      throw new Failed(settings.product() + " answered HTTP " + answer.status());
    }
    return json;
  }

  private String text(JsonNode answer, String field, int status) throws Failed {
    JsonNode value = answer.get(field);
    if (value == null || !value.isString()) {
      throw new Failed(settings.product() + " answered HTTP " + status);
    }
    return value.stringValue();
  }

  private static byte[] randomBytes() {
    byte[] bytes = new byte[16];
    RANDOM.nextBytes(bytes);
    return bytes;
  }
}
