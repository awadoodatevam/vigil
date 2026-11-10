package vigil.connectors;

import java.net.http.HttpRequest;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import vigil.connectors.api.AiCompletion;
import vigil.connectors.api.AiFinish;
import vigil.connectors.api.ConnectorInstanceView;
import vigil.connectors.api.ConnectorKind;

/** The {@code claude} connector (PM-K-07, AR-K-07): the Messages call of AR-G-20. */
@Component
class ClaudeConnector extends AiConnector {

  ClaudeConnector(
      ConnectorRecords records, FileSecrets secrets, ConnectorHttp http, ConnectorTimings timings) {
    super(records, secrets, http, timings);
  }

  @Override
  public ConnectorKind kind() {
    return ConnectorKind.CLAUDE;
  }

  @Override
  boolean sendsKey() {
    return true;
  }

  @Override
  String completePath() {
    return "/v1/messages";
  }

  @Override
  void headers(HttpRequest.Builder request, ConnectorInstanceView instance, String key) {
    JsonNode version = setting(instance, "anthropic_version");
    if (!version.isString()) {
      throw new IllegalStateException(
          "connector instance " + instance.id() + " has no anthropic_version setting");
    }
    request.header("x-api-key", key).header("anthropic-version", version.stringValue());
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
    body.put("max_tokens", maxOutputUnits);
    body.put("system", systemText);
    ObjectNode message = body.putArray("messages").addObject();
    message.put("role", "user");
    message.put("content", userText);
    ObjectNode format = body.putObject("output_config").putObject("format");
    format.put("type", "json_schema");
    format.set("schema", outputSchema);
    return body;
  }

  @Override
  AiCompletion.Answered completeAnswer(JsonNode answer) {
    String text = null;
    JsonNode content = answer.get("content");
    if (content != null && content.isArray()) {
      for (JsonNode item : content) {
        if ("text".equals(string(item, "type"))) {
          text = string(item, "text");
          break;
        }
      }
    }
    String stopReason = string(answer, "stop_reason");
    AiFinish finish =
        "refusal".equals(stopReason)
            ? AiFinish.REFUSED
            : "max_tokens".equals(stopReason) ? AiFinish.TRUNCATED : AiFinish.COMPLETE;
    JsonNode usage = answer.get("usage");
    return new AiCompletion.Answered(
        text,
        finish,
        units(usage == null ? null : usage.get("input_tokens")),
        units(usage == null ? null : usage.get("output_tokens")));
  }
}
