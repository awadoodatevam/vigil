package vigil.connectors;

import java.net.http.HttpRequest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import vigil.connectors.api.AiCompletion;
import vigil.connectors.api.AiFinish;
import vigil.connectors.api.ConnectorInstanceView;
import vigil.connectors.api.ConnectorKind;

/**
 * The {@code openai} connector (PM-K-08, AR-K-08): the Responses call of AR-G-21 and the embeddings
 * call of AR-G-22; one kind for the instances {@code openai-api} and {@code openai-embed-api}.
 */
@Component
class OpenAiConnector extends AiConnector {

  OpenAiConnector(
      ConnectorRecords records, FileSecrets secrets, ConnectorHttp http, ConnectorTimings timings) {
    super(records, secrets, http, timings);
  }

  @Override
  public ConnectorKind kind() {
    return ConnectorKind.OPENAI;
  }

  @Override
  boolean sendsKey() {
    return true;
  }

  @Override
  String completePath() {
    return "/v1/responses";
  }

  @Override
  String embedPath() {
    return "/v1/embeddings";
  }

  @Override
  void headers(HttpRequest.Builder request, ConnectorInstanceView instance, String key) {
    request.header("Authorization", "Bearer " + key);
  }

  @Override
  ObjectNode completeBody(
      ConnectorInstanceView instance,
      String model,
      String systemText,
      String userText,
      String outputSchemaName,
      JsonNode outputSchema,
      int maxOutputUnits) {
    ObjectNode body = newObject();
    body.put("model", model);
    body.put("instructions", systemText);
    body.put("input", userText);
    body.put("max_output_tokens", maxOutputUnits);
    ObjectNode format = body.putObject("text").putObject("format");
    format.put("type", "json_schema");
    format.put("name", outputSchemaName);
    format.set("schema", outputSchema);
    format.put("strict", true);
    return body;
  }

  @Override
  ObjectNode embedBody(ConnectorInstanceView instance, String model, List<String> texts) {
    ObjectNode body = newObject();
    body.put("model", model);
    ArrayNode input = body.putArray("input");
    texts.forEach(input::add);
    return body;
  }

  @Override
  AiCompletion.Answered completeAnswer(JsonNode answer) {
    StringBuilder text = new StringBuilder();
    boolean found = false;
    boolean refused = false;
    JsonNode output = answer.get("output");
    if (output != null && output.isArray()) {
      for (JsonNode item : output) {
        JsonNode content = item.isObject() ? item.get("content") : null;
        if (!"message".equals(string(item, "type")) || content == null || !content.isArray()) {
          continue;
        }
        for (JsonNode part : content) {
          String type = string(part, "type");
          if ("refusal".equals(type)) {
            refused = true;
          } else if ("output_text".equals(type) && string(part, "text") != null) {
            text.append(string(part, "text"));
            found = true;
          }
        }
      }
    }
    AiFinish finish =
        refused
            ? AiFinish.REFUSED
            : "incomplete".equals(string(answer, "status"))
                ? AiFinish.TRUNCATED
                : AiFinish.COMPLETE;
    JsonNode usage = answer.get("usage");
    return new AiCompletion.Answered(
        found ? text.toString() : null,
        finish,
        units(usage == null ? null : usage.get("input_tokens")),
        units(usage == null ? null : usage.get("output_tokens")));
  }

  /** {@code data[i].embedding} ordered by {@code data[i].index}, which are exactly 0 to n−1. */
  @Override
  List<JsonNode> vectorNodes(JsonNode answer) {
    JsonNode data = answer.get("data");
    if (data == null || !data.isArray()) {
      return null;
    }
    int n = data.size();
    JsonNode[] ordered = new JsonNode[n];
    Set<Integer> seen = new HashSet<>();
    for (JsonNode item : data) {
      JsonNode index = item.isObject() ? item.get("index") : null;
      if (index == null
          || !index.isIntegralNumber()
          || !index.canConvertToInt()
          || index.intValue() < 0
          || index.intValue() >= n
          || !seen.add(index.intValue())) {
        return null;
      }
      ordered[index.intValue()] = item.get("embedding");
    }
    return Arrays.asList(ordered);
  }

  @Override
  long embedUnits(JsonNode answer) {
    JsonNode usage = answer.get("usage");
    return units(usage == null ? null : usage.get("prompt_tokens"));
  }
}
