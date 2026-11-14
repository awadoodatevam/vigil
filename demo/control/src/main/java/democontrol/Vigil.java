package democontrol;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * Vigil, reached only through vigil-api: AR-I-10 (reachability) and the installation operations
 * AR-I-95 to AR-I-98 with the installation token (AR-S-07).
 */
class Vigil {

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
    JsonNode answer =
        call(
            "/api/v1/installation/tenants",
            timeout,
            200,
            builder ->
                builder
                    .header("Content-Type", "application/json")
                    .POST(Http.json(Map.of("name", name))));
    return text(answer, "tenant_id", 200);
  }

  /** AR-I-96 with the multipart field bundle: the seed import's id. */
  String importBundle(String tenantId, byte[] bundle, Duration timeout) throws Failed {
    String boundary = "demo-control-" + HexFormat.of().formatHex(randomBytes());
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    body.writeBytes(
        ("--"
                + boundary
                + "\r\nContent-Disposition: form-data; name=\"bundle\"; filename=\"bundle.zip\""
                + "\r\nContent-Type: application/zip\r\n\r\n")
            .getBytes(StandardCharsets.US_ASCII));
    body.writeBytes(bundle);
    body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
    JsonNode answer =
        call(
            "/api/v1/installation/tenants/" + tenantId + "/seed-imports",
            timeout,
            202,
            builder ->
                builder
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())));
    return text(answer, "seed_import_id", 202);
  }

  /** AR-I-97. */
  JsonNode readImport(String seedImportId, Duration timeout) throws Failed {
    return call(
        "/api/v1/installation/seed-imports/" + seedImportId,
        timeout,
        200,
        HttpRequest.Builder::GET);
  }

  /** AR-I-98 for demo.{demo_domain}. */
  void setAlias(String tenantId, Duration timeout) throws Failed {
    call(
        "/api/v1/installation/aliases/demo." + settings.demoDomain(),
        timeout,
        200,
        builder ->
            builder
                .header("Content-Type", "application/json")
                .PUT(Http.json(Map.of("tenant_id", tenantId))));
  }

  private interface Method {
    HttpRequest.Builder apply(HttpRequest.Builder builder);
  }

  private JsonNode call(String path, Duration timeout, int expected, Method method) throws Failed {
    HttpRequest request =
        method
            .apply(
                Http.request(settings.vigilUrl() + path, timeout)
                    .header("Authorization", "Bearer " + secrets.value("installation-token"))
                    .header("Accept", "application/json"))
            .build();
    Http.Answer answer;
    try {
      answer = Http.send(request);
    } catch (IOException e) {
      throw new Failed(settings.product() + ": " + Failed.text(e));
    }
    JsonNode json = answer.json();
    if (answer.status() != expected) {
      JsonNode message = json == null ? null : json.path("error").path("message");
      throw new Failed(
          message != null && message.isString()
              ? message.stringValue()
              : settings.product() + " answered HTTP " + answer.status());
    }
    if (json == null || !json.isObject()) {
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
