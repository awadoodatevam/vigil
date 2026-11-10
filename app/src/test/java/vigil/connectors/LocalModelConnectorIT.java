package vigil.connectors;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import vigil.connectors.Recordings.ProviderCall;
import vigil.connectors.api.AiCompletion;
import vigil.connectors.api.AiFinish;
import vigil.connectors.api.AiProviderConnector;
import vigil.connectors.api.ConnectorKind;

class LocalModelConnectorIT {

  private static final String PATH = "/api/chat";
  private static AiConnectorApp ai;
  private static AiProviderConnector local;

  @BeforeAll
  static void start() {
    ai = new AiConnectorApp();
    local = ai.connector(ConnectorKind.LOCAL_MODEL);
  }

  @AfterAll
  static void stop() {
    ai.close();
  }

  @BeforeEach
  void reset() {
    ai.wiremock.resetAll();
  }

  private static void answer(String body) {
    ai.wiremock.stubFor(
        post(urlEqualTo(PATH))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json; charset=utf-8")
                    .withBody(body)));
  }

  private static AiCompletion complete(ProviderCall call) {
    JsonNode request = call.request();
    return local.complete(
        ai.tenantId,
        ai.instance("local-llm"),
        request.get("model").stringValue(),
        request.get("messages").get(0).get("content").stringValue(),
        AiConnectorApp.TWO_PARTS,
        AiConnectorApp.SL_T_1_SCHEMA_NAME,
        request.get("format"),
        request.get("options").get("num_predict").intValue());
  }

  private static LoggedRequest onlyRequest() {
    List<LoggedRequest> requests = ai.wiremock.findAll(anyRequestedFor(anyUrl()));
    assertThat(requests).hasSize(1);
    return requests.getFirst();
  }

  @Test
  @DisplayName("AR-G-23 the local model chat call as recorded, and its answer read")
  void chat() {
    ProviderCall call = Recordings.provider("local-llm", "0001-chat-1.json");
    answer(call.responseBody());
    AiCompletion result = complete(call);
    JsonNode sent = Recordings.tree(onlyRequest().getBodyAsString());
    ObjectNode expected = (ObjectNode) call.request();
    ((ObjectNode) expected.get("messages").get(1))
        .put("content", AiConnectorApp.TWO_PARTS_RENDERED);
    assertThat(sent.propertyNames())
        .containsExactly("model", "messages", "format", "stream", "think", "keep_alive", "options");
    assertThat(sent.get("options").propertyNames())
        .containsExactly("temperature", "seed", "num_ctx", "num_predict");
    assertThat(sent).isEqualTo(expected);
    String text = Recordings.tree(call.responseBody()).get("message").get("content").stringValue();
    assertThat(result).isEqualTo(new AiCompletion.Answered(text, AiFinish.COMPLETE, 5704, 951));

    ObjectNode truncated = (ObjectNode) Recordings.tree(call.responseBody());
    truncated.put("done_reason", "length");
    answer(truncated.toString());
    assertThat(complete(call))
        .isEqualTo(new AiCompletion.Answered(text, AiFinish.TRUNCATED, 5704, 951));
  }

  @Test
  @DisplayName("PM-K-09 the local model is called without a credential")
  void noCredential() {
    ProviderCall call = Recordings.provider("local-llm", "0001-chat-1.json");
    answer(call.responseBody());
    assertThat(ai.instance("local-llm").credentialRefId()).isNull();
    assertThat(complete(call)).isInstanceOf(AiCompletion.Answered.class);
    LoggedRequest request = onlyRequest();
    assertThat(request.containsHeader("Authorization")).isFalse();
    assertThat(request.containsHeader("x-api-key")).isFalse();
    String body = request.getBodyAsString();
    assertThat(body).doesNotContain(ai.secrets.value("claude"), ai.secrets.value("openai"));
  }

  @Test
  @DisplayName(
      "AR-T-16 the local model runtime is reached as Ollama /api/chat, one call per answer")
  void ollamaChat() {
    ProviderCall call = Recordings.provider("local-llm", "0002-chat-2.json");
    answer(call.responseBody());
    assertThat(complete(call)).isInstanceOf(AiCompletion.Answered.class);
    ai.wiremock.verify(1, postRequestedFor(urlEqualTo(PATH)));
    ai.wiremock.verify(1, anyRequestedFor(anyUrl()));
  }
}
