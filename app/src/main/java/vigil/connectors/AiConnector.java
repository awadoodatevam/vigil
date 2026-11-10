package vigil.connectors;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import vigil.connectors.ConnectorRecords.StoredCredential;
import vigil.connectors.api.AiCompletion;
import vigil.connectors.api.AiEmbedding;
import vigil.connectors.api.AiFailure;
import vigil.connectors.api.AiFinish;
import vigil.connectors.api.AiPromptPart;
import vigil.connectors.api.AiProviderConnector;
import vigil.connectors.api.ConnectorInstanceView;
import vigil.shared.TenantId;
import vigil.shared.TransactionGuard;

/**
 * What the four AI provider connectors share (P-19 spec Sections 6.4 and 6.5): the order of checks,
 * the address, the API key, the call with its whole-call timeout, the reading of the answer's
 * status, units and vectors, and the rendering of the parts (AR-G-10).
 */
abstract class AiConnector implements AiProviderConnector {

  static final String NO_CREDENTIAL = "No credential is set.";
  private static final int REASON_MAX = 500;
  private static final int HTTP_OK_MIN = 200;
  private static final int HTTP_OK_MAX = 299;
  private static final char LAST_HEADER_CHAR = 'ÿ';
  private static final char DELETE = '\u007F';

  private final ConnectorRecords records;
  private final FileSecrets secrets;
  private final ConnectorHttp http;
  final ConnectorTimings timings;

  AiConnector(
      ConnectorRecords records, FileSecrets secrets, ConnectorHttp http, ConnectorTimings timings) {
    this.records = records;
    this.secrets = secrets;
    this.http = http;
    this.timings = timings;
  }

  /** Whether the kind sends the API key of the instance's credential reference. */
  abstract boolean sendsKey();

  /** The path of the completion call; {@code null} when the kind does not offer it. */
  String completePath() {
    return null;
  }

  /** The path of the embedding call; {@code null} when the kind does not offer it. */
  String embedPath() {
    return null;
  }

  /** The whole-call timeout of a completion call. */
  Duration completeTimeout() {
    return timings.chat();
  }

  /** The completion request body, keys in the order of the wire format. */
  ObjectNode completeBody(
      ConnectorInstanceView instance,
      String model,
      String systemText,
      String userText,
      String outputSchemaName,
      JsonNode outputSchema,
      int maxOutputUnits) {
    throw new UnsupportedOperationException();
  }

  /** The embedding request body, keys in the order of the wire format. */
  ObjectNode embedBody(ConnectorInstanceView instance, String model, List<String> texts) {
    throw new UnsupportedOperationException();
  }

  /** The headers besides {@code Content-Type}; {@code key} is {@code null} for a keyless kind. */
  void headers(HttpRequest.Builder request, ConnectorInstanceView instance, String key) {}

  /** The result of a 2xx completion answer that is a JSON object. */
  AiCompletion.Answered completeAnswer(JsonNode answer) {
    throw new UnsupportedOperationException();
  }

  /** The vector nodes of a 2xx embedding answer in text order; {@code null} when unreadable. */
  List<JsonNode> vectorNodes(JsonNode answer) {
    throw new UnsupportedOperationException();
  }

  /** The input units of a 2xx embedding answer. */
  long embedUnits(JsonNode answer) {
    throw new UnsupportedOperationException();
  }

  /**
   * {@inheritDoc}
   *
   * <p>external call: not inside a transaction
   */
  @Override
  public final AiCompletion complete(
      TenantId tenantId,
      ConnectorInstanceView instance,
      String model,
      String systemText,
      List<AiPromptPart> parts,
      String outputSchemaName,
      JsonNode outputSchema,
      int maxOutputUnits) {
    TransactionGuard.assertNoTransaction();
    required(tenantId, instance, model, systemText, parts, outputSchemaName, outputSchema);
    elements(parts);
    if (maxOutputUnits < 1) {
      throw new IllegalArgumentException("maxOutputUnits: at least 1");
    }
    kind(instance);
    if (completePath() == null) {
      return new AiCompletion.Failed(AiFailure.PROVIDER_ERROR, notOffered("complete"));
    }
    Exchange exchange =
        exchange(
            instance,
            completePath(),
            () ->
                completeBody(
                    instance,
                    model,
                    systemText,
                    render(parts),
                    outputSchemaName,
                    outputSchema,
                    maxOutputUnits),
            completeTimeout());
    if (exchange.failure() != null) {
      return new AiCompletion.Failed(exchange.failure(), exchange.reason());
    }
    JsonNode answer = object(exchange.body());
    return answer == null
        ? new AiCompletion.Answered(null, AiFinish.COMPLETE, 0, 0)
        : completeAnswer(answer);
  }

  /**
   * {@inheritDoc}
   *
   * <p>external call: not inside a transaction
   */
  @Override
  public final AiEmbedding embed(
      TenantId tenantId, ConnectorInstanceView instance, String model, List<String> texts) {
    TransactionGuard.assertNoTransaction();
    required(tenantId, instance, model, texts);
    elements(texts);
    kind(instance);
    if (embedPath() == null) {
      return new AiEmbedding.Failed(AiFailure.PROVIDER_ERROR, notOffered("embed"));
    }
    Exchange exchange =
        exchange(
            instance,
            embedPath(),
            () -> embedBody(instance, model, texts),
            texts.size() == 1 ? timings.embedOne() : timings.embedBatch());
    if (exchange.failure() != null) {
      return new AiEmbedding.Failed(exchange.failure(), exchange.reason());
    }
    int m = texts.size();
    JsonNode answer = object(exchange.body());
    List<JsonNode> nodes = answer == null ? null : vectorNodes(answer);
    if (nodes == null) {
      return embedFailed("The answer has 0 vectors for " + m + " texts.");
    }
    if (nodes.size() != m) {
      return embedFailed("The answer has " + nodes.size() + " vectors for " + m + " texts.");
    }
    List<float[]> vectors = new ArrayList<>();
    for (JsonNode node : nodes) {
      float[] vector = vector(node);
      if (vector == null) {
        return embedFailed("The answer has a vector that is not a list of numbers.");
      }
      vectors.add(vector);
    }
    return new AiEmbedding.Embedded(List.copyOf(vectors), embedUnits(answer));
  }

  /** The user message: each part as {@code ### {label} ({category})}, a line break and its text. */
  static String render(List<AiPromptPart> parts) {
    List<String> rendered = new ArrayList<>();
    for (AiPromptPart part : parts) {
      rendered.add("### " + part.label() + " (" + part.categoryName() + ")\n" + part.text());
    }
    return String.join("\n\n", rendered);
  }

  /** A unit: a non-negative integer that fits in a {@code long}, otherwise 0 (DM-E-74). */
  static long units(JsonNode node) {
    if (node == null
        || !node.isIntegralNumber()
        || !node.canConvertToLong()
        || node.longValue() < 0) {
      return 0;
    }
    return node.longValue();
  }

  /** The value of {@code field} when it is a JSON string, else {@code null}. */
  static String string(JsonNode node, String field) {
    JsonNode value = node == null || !node.isObject() ? null : node.get(field);
    return value != null && value.isString() ? value.stringValue() : null;
  }

  /** The setting {@code key} of the instance as it is stored. */
  static JsonNode setting(ConnectorInstanceView instance, String key) {
    JsonNode value = instance.settings() == null ? null : instance.settings().get(key);
    if (value == null || value.isNull()) {
      throw new IllegalStateException(
          "connector instance " + instance.id() + " has no " + key + " setting");
    }
    return value;
  }

  static ObjectNode newObject() {
    return JsonBodies.JSON.createObjectNode();
  }

  /** The answer body of a 2xx call, or the failure and its reason. */
  private record Exchange(byte[] body, AiFailure failure, String reason) {}

  private Exchange exchange(
      ConnectorInstanceView instance, String path, Supplier<ObjectNode> body, Duration timeout) {
    String base = instance.address().replaceFirst("/+$", "");
    Optional<URI> uri = HttpSourceConnector.address(base + path);
    if (uri.isEmpty()) {
      return failed(AiFailure.PROVIDER_ERROR, HttpSourceConnector.INVALID_ADDRESS);
    }
    String key = null;
    if (sendsKey()) {
      UUID credentialRefId = instance.credentialRefId();
      Optional<StoredCredential> credential =
          credentialRefId == null
              ? Optional.empty()
              : records.credential(instance.tenantId(), credentialRefId);
      if (credential.isEmpty()) {
        return failed(AiFailure.PROVIDER_ERROR, NO_CREDENTIAL);
      }
      String name = credential.get().secretName();
      Optional<String> secret = secrets.readNamed(name);
      if (secret.isEmpty() || !headerSafe(secret.get())) {
        return failed(AiFailure.PROVIDER_ERROR, "Secret " + name + " is not available.");
      }
      key = secret.get();
    }
    HttpRequest request;
    try {
      HttpRequest.Builder builder =
          HttpRequest.newBuilder(uri.get())
              .timeout(timeout)
              .header("Content-Type", "application/json")
              .POST(
                  HttpRequest.BodyPublishers.ofByteArray(
                      JsonBodies.JSON.writeValueAsBytes(body.get())));
      headers(builder, instance, key);
      request = builder.build();
    } catch (IllegalArgumentException e) {
      return failed(AiFailure.PROVIDER_ERROR, HttpSourceConnector.INVALID_ADDRESS);
    }
    HttpResponse<byte[]> response;
    try {
      response = http.send(request, timeout);
    } catch (Exception e) {
      List<String> hidden = key == null ? List.of() : List.of(key);
      return failed(
          AiFailure.UNREACHABLE, FailureReasons.of(e, uri.get(), timings, timeout, hidden));
    }
    int status = response.statusCode();
    if (status < HTTP_OK_MIN || status > HTTP_OK_MAX) {
      return failed(AiFailure.PROVIDER_ERROR, "HTTP " + status);
    }
    return new Exchange(response.body(), null, null);
  }

  private static Exchange failed(AiFailure failure, String reason) {
    return new Exchange(null, failure, cut(reason));
  }

  private static AiEmbedding.Failed embedFailed(String reason) {
    return new AiEmbedding.Failed(AiFailure.PROVIDER_ERROR, cut(reason));
  }

  private String notOffered(String operation) {
    return kind().key() + " does not offer " + operation;
  }

  private void kind(ConnectorInstanceView instance) {
    if (instance.kind() != kind()) {
      throw new IllegalArgumentException(
          "connector instance " + instance.id() + " is not of kind " + kind().key());
    }
  }

  /** The body as a JSON object when it is UTF-8 JSON holding one object, else {@code null}. */
  private static JsonNode object(byte[] body) {
    return JsonBodies.decode(body == null ? new byte[0] : body)
        .flatMap(JsonBodies::object)
        .orElse(null);
  }

  /** The node as a vector when it is an array of numbers, else {@code null}. */
  private static float[] vector(JsonNode node) {
    if (node == null || !node.isArray()) {
      return null;
    }
    float[] vector = new float[node.size()];
    for (int i = 0; i < node.size(); i++) {
      JsonNode number = node.get(i);
      if (!number.isNumber()) {
        return null;
      }
      vector[i] = number.floatValue();
    }
    return vector;
  }

  /** The key goes into a header as is, so it holds only characters a header value can hold. */
  private static boolean headerSafe(String secret) {
    for (int i = 0; i < secret.length(); i++) {
      char c = secret.charAt(i);
      if ((c < ' ' && c != '\t') || c == DELETE || c > LAST_HEADER_CHAR) {
        return false;
      }
    }
    return true;
  }

  private static String cut(String reason) {
    return reason.codePointCount(0, reason.length()) <= REASON_MAX
        ? reason
        : reason.substring(0, reason.offsetByCodePoints(0, REASON_MAX));
  }

  private static void required(Object... arguments) {
    for (Object argument : arguments) {
      if (argument == null) {
        throw new IllegalArgumentException("a required argument is missing");
      }
    }
  }

  private static void elements(List<?> list) {
    if (list.isEmpty() || list.stream().anyMatch(element -> element == null)) {
      throw new IllegalArgumentException("a list argument is empty or holds null");
    }
  }
}
