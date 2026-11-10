package vigil.connectors;

import java.time.Duration;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import vigil.connectors.api.AiCompletion;
import vigil.connectors.api.AiFinish;
import vigil.connectors.api.ConnectorInstanceView;
import vigil.connectors.api.ConnectorKind;

/**
 * The {@code local_model} connector (PM-K-09, AR-K-09): the Ollama {@code /api/chat} call of
 * AR-G-23 (AR-T-16), without a credential.
 */
@Component
class LocalModelConnector extends AiConnector {

  LocalModelConnector(
      ConnectorRecords records, FileSecrets secrets, ConnectorHttp http, ConnectorTimings timings) {
    super(records, secrets, http, timings);
  }

  @Override
  public ConnectorKind kind() {
    return ConnectorKind.LOCAL_MODEL;
  }

  @Override
  boolean sendsKey() {
    return false;
  }

  @Override
  String completePath() {
    return "/api/chat";
  }

  @Override
  Duration completeTimeout() {
    return timings.localChat();
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
    ArrayNode messages = body.putArray("messages");
    ObjectNode system = messages.addObject();
    system.put("role", "system");
    system.put("content", systemText);
    ObjectNode user = messages.addObject();
    user.put("role", "user");
    user.put("content", userText);
    body.set("format", outputSchema);
    body.put("stream", false);
    body.put("think", false);
    body.set("keep_alive", setting(instance, "keep_alive"));
    ObjectNode options = body.putObject("options");
    options.put("temperature", 0);
    options.put("seed", 7);
    options.set("num_ctx", setting(instance, "num_ctx"));
    options.put("num_predict", maxOutputUnits);
    return body;
  }

  @Override
  AiCompletion.Answered completeAnswer(JsonNode answer) {
    String text = string(answer.get("message"), "content");
    AiFinish finish =
        "length".equals(string(answer, "done_reason")) ? AiFinish.TRUNCATED : AiFinish.COMPLETE;
    return new AiCompletion.Answered(
        text == null || text.isEmpty() ? null : text,
        finish,
        units(answer.get("prompt_eval_count")),
        units(answer.get("eval_count")));
  }
}
