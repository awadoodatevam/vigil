package vigil.connectors;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import vigil.connectors.Recordings.ProviderCall;
import vigil.connectors.api.AiCompletion;
import vigil.connectors.api.AiEmbedding;
import vigil.connectors.api.AiFailure;
import vigil.connectors.api.AiFinish;
import vigil.connectors.api.AiProviderConnector;
import vigil.connectors.api.ConnectorKind;

class OpenAiConnectorIT {

  private static final String RESPONSES = "/v1/responses";
  private static final String EMBEDDINGS = "/v1/embeddings";
  private static AiConnectorApp ai;
  private static AiProviderConnector openai;

  @BeforeAll
  static void start() {
    ai = new AiConnectorApp();
    openai = ai.connector(ConnectorKind.OPENAI);
  }

  @AfterAll
  static void stop() {
    ai.close();
  }

  @BeforeEach
  void reset() {
    ai.wiremock.resetAll();
  }

  private static void answer(String path, int status, String body) {
    ai.wiremock.stubFor(
        post(urlEqualTo(path))
            .willReturn(
                aResponse()
                    .withStatus(status)
                    .withHeader("Content-Type", "application/json")
                    .withBody(body)));
  }

  private static AiCompletion complete(String handle, ProviderCall call) {
    JsonNode request = call.request();
    JsonNode format = request.get("text").get("format");
    return openai.complete(
        ai.tenantId,
        ai.instance(handle),
        request.get("model").stringValue(),
        request.get("instructions").stringValue(),
        AiConnectorApp.TWO_PARTS,
        format.get("name").stringValue(),
        format.get("schema"),
        request.get("max_output_tokens").intValue());
  }

  private static AiEmbedding embed(String handle, ProviderCall call) {
    JsonNode request = call.request();
    List<String> texts = new ArrayList<>();
    request.get("input").forEach(text -> texts.add(text.stringValue()));
    return openai.embed(
        ai.tenantId, ai.instance(handle), request.get("model").stringValue(), texts);
  }

  private static LoggedRequest onlyRequest() {
    List<LoggedRequest> requests = ai.wiremock.findAll(anyRequestedFor(anyUrl()));
    assertThat(requests).hasSize(1);
    return requests.getFirst();
  }

  @Test
  @DisplayName("AR-G-21 the OpenAI Responses call as recorded, and its answer read")
  void responses() {
    ProviderCall call = Recordings.provider("openai", "0001-sl-t-1.json");
    answer(RESPONSES, call.status(), call.responseBody());
    AiCompletion result = complete("openai-api", call);
    LoggedRequest request = onlyRequest();
    assertThat(request.getUrl()).isEqualTo(RESPONSES);
    assertThat(request.getHeader("Authorization"))
        .isEqualTo("Bearer " + ai.secrets.value("openai"));
    JsonNode sent = Recordings.tree(request.getBodyAsString());
    JsonNode recorded = call.request();
    assertThat(sent.propertyNames())
        .containsExactly("model", "instructions", "input", "max_output_tokens", "text");
    for (String field : List.of("model", "instructions", "max_output_tokens", "text")) {
      assertThat(sent.get(field)).as(field).isEqualTo(recorded.get(field));
    }
    assertThat(sent.get("input").stringValue()).isEqualTo(AiConnectorApp.TWO_PARTS_RENDERED);
    JsonNode output = Recordings.tree(call.responseBody()).get("output");
    assertThat(output.get(0).get("type").stringValue()).isEqualTo("reasoning");
    assertThat(output.get(1).get("type").stringValue()).isEqualTo("message");
    String text = output.get(1).get("content").get(0).get("text").stringValue();
    assertThat(result).isEqualTo(new AiCompletion.Answered(text, AiFinish.COMPLETE, 5192, 270));

    ai.wiremock.resetAll();
    ProviderCall truncated = Recordings.provider("openai", "0003-truncated.json");
    answer(RESPONSES, truncated.status(), truncated.responseBody());
    assertThat(complete("openai-api", truncated))
        .isEqualTo(new AiCompletion.Answered(null, AiFinish.TRUNCATED, 5192, 16));

    answer(
        RESPONSES,
        200,
        "{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":"
            + "[{\"type\":\"refusal\",\"refusal\":\"I cannot help.\"}]}],"
            + "\"usage\":{\"input_tokens\":12,\"output_tokens\":4}}");
    assertThat(complete("openai-api", truncated))
        .isEqualTo(new AiCompletion.Answered(null, AiFinish.REFUSED, 12, 4));
  }

  @Test
  @DisplayName("AR-G-22 the OpenAI embeddings call as recorded, vectors in index order")
  void embeddings() {
    ProviderCall call = Recordings.provider("openai", "0004-embed.json");
    answer(EMBEDDINGS, call.status(), call.responseBody());
    AiEmbedding result = embed("openai-embed-api", call);
    LoggedRequest request = onlyRequest();
    assertThat(request.getUrl()).isEqualTo(EMBEDDINGS);
    assertThat(request.getHeader("Authorization"))
        .isEqualTo("Bearer " + ai.secrets.value("openai"));
    JsonNode sent = Recordings.tree(request.getBodyAsString());
    assertThat(sent.propertyNames()).containsExactly("model", "input");
    assertThat(sent).isEqualTo(call.request());
    JsonNode data = Recordings.tree(call.responseBody()).get("data");
    assertThat(result).isInstanceOf(AiEmbedding.Embedded.class);
    AiEmbedding.Embedded embedded = (AiEmbedding.Embedded) result;
    assertThat(embedded.inputUnits()).isEqualTo(116);
    assertThat(embedded.vectors()).hasSize(2);
    for (int i = 0; i < 2; i++) {
      assertThat(embedded.vectors().get(i)).hasSize(1536);
      assertThat(embedded.vectors().get(i)[0])
          .isEqualTo(data.get(i).get("embedding").get(0).floatValue());
      assertThat(data.get(i).get("index").intValue()).isEqualTo(i);
    }

    ObjectNode reversed = (ObjectNode) Recordings.tree(call.responseBody());
    ArrayNode items = reversed.putArray("data");
    items.add(data.get(1));
    items.add(data.get(0));
    answer(EMBEDDINGS, 200, reversed.toString());
    AiEmbedding.Embedded again = (AiEmbedding.Embedded) embed("openai-embed-api", call);
    assertThat(again.vectors()).hasSize(2);
    for (int i = 0; i < 2; i++) {
      assertThat(again.vectors().get(i)).isEqualTo(embedded.vectors().get(i));
    }

    ObjectNode one = (ObjectNode) Recordings.tree(call.responseBody());
    one.putArray("data").add(data.get(0));
    answer(EMBEDDINGS, 200, one.toString());
    assertThat(embed("openai-embed-api", call))
        .isEqualTo(
            new AiEmbedding.Failed(
                AiFailure.PROVIDER_ERROR, "The answer has 1 vectors for 2 texts."));
  }

  @Test
  @DisplayName("PM-K-08 the openai-api and openai-embed-api instances use the one openai kind")
  void oneKind() {
    assertThat(ai.instance("openai-api").kind()).isEqualTo(ConnectorKind.OPENAI);
    assertThat(ai.instance("openai-embed-api").kind()).isEqualTo(ConnectorKind.OPENAI);
    ProviderCall responses = Recordings.provider("openai", "0001-sl-t-1.json");
    ProviderCall embeddings = Recordings.provider("openai", "0004-embed.json");
    answer(RESPONSES, responses.status(), responses.responseBody());
    answer(EMBEDDINGS, embeddings.status(), embeddings.responseBody());
    for (String handle : List.of("openai-api", "openai-embed-api")) {
      assertThat(complete(handle, responses)).isInstanceOf(AiCompletion.Answered.class);
      assertThat(embed(handle, embeddings)).isInstanceOf(AiEmbedding.Embedded.class);
    }
  }
}
