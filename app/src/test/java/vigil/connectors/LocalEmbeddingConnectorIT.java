package vigil.connectors;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
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
import vigil.connectors.Recordings.ProviderCall;
import vigil.connectors.api.AiEmbedding;
import vigil.connectors.api.AiProviderConnector;
import vigil.connectors.api.ConnectorKind;

class LocalEmbeddingConnectorIT {

  private static final String PATH = "/api/embed";
  private static AiConnectorApp ai;
  private static AiProviderConnector local;

  @BeforeAll
  static void start() {
    ai = new AiConnectorApp();
    local = ai.connector(ConnectorKind.LOCAL_EMBEDDING);
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

  private static AiEmbedding embed(String handle, ProviderCall call) {
    JsonNode request = call.request();
    List<String> texts = new ArrayList<>();
    request.get("input").forEach(text -> texts.add(text.stringValue()));
    return local.embed(ai.tenantId, ai.instance(handle), request.get("model").stringValue(), texts);
  }

  private static LoggedRequest onlyRequest() {
    List<LoggedRequest> requests = ai.wiremock.findAll(anyRequestedFor(anyUrl()));
    assertThat(requests).hasSize(1);
    return requests.getFirst();
  }

  @Test
  @DisplayName("AR-G-24 the local embedding call as recorded, and its vectors read")
  void embed() {
    ProviderCall call = Recordings.provider("local-embed", "0002-storm-1.json");
    answer(call.responseBody());
    AiEmbedding result = embed("local-embed", call);
    JsonNode sent = Recordings.tree(onlyRequest().getBodyAsString());
    assertThat(sent.propertyNames()).containsExactly("model", "input", "keep_alive");
    assertThat(sent).isEqualTo(call.request());
    assertThat(result).isInstanceOf(AiEmbedding.Embedded.class);
    AiEmbedding.Embedded embedded = (AiEmbedding.Embedded) result;
    assertThat(embedded.vectors()).hasSize(1);
    assertThat(embedded.vectors().getFirst()).hasSize(768);
    assertThat(embedded.inputUnits()).isEqualTo(74);

    ProviderCall past = Recordings.provider("local-embed", "0001-past-1.json");
    answer(past.responseBody());
    AiEmbedding.Embedded fifty = (AiEmbedding.Embedded) embed("local-embed", past);
    assertThat(fifty.vectors()).hasSize(50);
    assertThat(fifty.inputUnits()).isEqualTo(1598);
  }

  @Test
  @DisplayName("PM-K-10 no credential is sent; keep_alive comes from the instance's settings")
  void settings() {
    ProviderCall call = Recordings.provider("local-embed", "0002-storm-1.json");
    answer(call.responseBody());
    assertThat(embed("local-embed-300", call)).isInstanceOf(AiEmbedding.Embedded.class);
    LoggedRequest request = onlyRequest();
    assertThat(request.containsHeader("Authorization")).isFalse();
    assertThat(request.containsHeader("x-api-key")).isFalse();
    assertThat(Recordings.tree(request.getBodyAsString()).get("keep_alive").intValue())
        .isEqualTo(300);
  }

  @Test
  @DisplayName("AR-T-17 the local embedding runtime is reached as Ollama /api/embed")
  void ollamaEmbed() {
    ProviderCall call = Recordings.provider("local-embed", "0004-storm-2.json");
    answer(call.responseBody());
    assertThat(embed("local-embed", call)).isInstanceOf(AiEmbedding.Embedded.class);
    ai.wiremock.verify(1, postRequestedFor(urlEqualTo(PATH)));
    ai.wiremock.verify(1, anyRequestedFor(anyUrl()));
  }
}
