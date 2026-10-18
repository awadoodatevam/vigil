package vigil.connectors;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The common reading rules of received monitoring bodies (P-10 spec Section 6.6). */
final class JsonBodies {

  static final JsonMapper JSON = JsonMapper.builder().build();
  static final String NOT_UTF_8 = "the body is not valid UTF-8";
  static final String NOT_AN_OBJECT = "the body is not a JSON object";

  private JsonBodies() {}

  /** The body decoded as strict UTF-8, or empty when it does not decode. */
  static Optional<String> decode(byte[] body) {
    if (body == null) {
      throw new IllegalArgumentException("body: required");
    }
    try {
      return Optional.of(
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(body))
              .toString());
    } catch (CharacterCodingException e) {
      return Optional.empty();
    }
  }

  /** The text's one JSON value when it is an object (whitespace around it allowed), or empty. */
  static Optional<JsonNode> object(String text) {
    try (JsonParser parser = JSON.createParser(text)) {
      JsonNode node = JSON.readTree(parser);
      if (node == null || !node.isObject() || parser.nextToken() != null) {
        return Optional.empty();
      }
      return Optional.of(node);
    } catch (JacksonException e) {
      return Optional.empty();
    }
  }

  /** The value of {@code field} when it is a JSON string, else {@code null}. */
  static String string(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value != null && value.isString() ? value.stringValue() : null;
  }

  /** The value of {@code field} when it is a non-empty JSON string, else {@code null}. */
  static String present(JsonNode node, String field) {
    String value = string(node, field);
    return value == null || value.isEmpty() ? null : value;
  }
}
