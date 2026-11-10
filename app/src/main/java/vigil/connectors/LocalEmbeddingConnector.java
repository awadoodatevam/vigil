package vigil.connectors;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import vigil.connectors.api.ConnectorInstanceView;
import vigil.connectors.api.ConnectorKind;

/**
 * The {@code local_embedding} connector (PM-K-10, AR-K-10): the Ollama {@code /api/embed} call of
 * AR-G-24 (AR-T-17), without a credential.
 */
@Component
class LocalEmbeddingConnector extends AiConnector {

  LocalEmbeddingConnector(
      ConnectorRecords records, FileSecrets secrets, ConnectorHttp http, ConnectorTimings timings) {
    super(records, secrets, http, timings);
  }

  @Override
  public ConnectorKind kind() {
    return ConnectorKind.LOCAL_EMBEDDING;
  }

  @Override
  boolean sendsKey() {
    return false;
  }

  @Override
  String embedPath() {
    return "/api/embed";
  }

  @Override
  ObjectNode embedBody(ConnectorInstanceView instance, String model, List<String> texts) {
    ObjectNode body = newObject();
    body.put("model", model);
    ArrayNode input = body.putArray("input");
    texts.forEach(input::add);
    body.set("keep_alive", setting(instance, "keep_alive"));
    return body;
  }

  @Override
  List<JsonNode> vectorNodes(JsonNode answer) {
    JsonNode embeddings = answer.get("embeddings");
    if (embeddings == null || !embeddings.isArray()) {
      return null;
    }
    List<JsonNode> nodes = new ArrayList<>();
    embeddings.forEach(nodes::add);
    return nodes;
  }

  @Override
  long embedUnits(JsonNode answer) {
    return units(answer.get("prompt_eval_count"));
  }
}
