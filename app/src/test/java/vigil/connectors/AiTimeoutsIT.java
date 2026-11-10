package vigil.connectors;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.JsonNode;
import vigil.connectors.api.AiCompletion;
import vigil.connectors.api.AiEmbedding;
import vigil.connectors.api.AiFailure;
import vigil.connectors.api.ConnectorKind;

class AiTimeoutsIT {

  private static final int DELAY_MS = 6_000;
  private static AiConnectorApp ai;

  /** Each AI timeout its own short value: chat 1 s, local chat 2 s, embed 3 s and 4 s. */
  static class ShortTimings {

    @Bean
    @Primary
    ConnectorTimings shortAiTimings() {
      return new ConnectorTimings(
          Duration.ofSeconds(1),
          Duration.ofSeconds(2),
          Duration.ofSeconds(1),
          Duration.ofSeconds(2),
          Duration.ofSeconds(3),
          Duration.ofSeconds(4));
    }
  }

  @BeforeAll
  static void start() {
    ai = new AiConnectorApp(ShortTimings.class);
    for (String path :
        List.of("/v1/messages", "/v1/responses", "/api/chat", "/v1/embeddings", "/api/embed")) {
      ai.wiremock.stubFor(
          post(urlEqualTo(path))
              .willReturn(aResponse().withStatus(200).withFixedDelay(DELAY_MS).withBody("{}")));
    }
  }

  @AfterAll
  static void stop() {
    ai.close();
  }

  private static AiCompletion complete(ConnectorKind kind, String handle) {
    JsonNode schema = AiConnectorApp.slT1Schema();
    return ai.connector(kind)
        .complete(
            ai.tenantId,
            ai.instance(handle),
            "model",
            "system",
            AiConnectorApp.TWO_PARTS,
            AiConnectorApp.SL_T_1_SCHEMA_NAME,
            schema,
            100);
  }

  private static AiEmbedding embed(ConnectorKind kind, String handle, List<String> texts) {
    return ai.connector(kind).embed(ai.tenantId, ai.instance(handle), "model", texts);
  }

  @Test
  @DisplayName("AR-TM-01 each AI call ends unreachable after its own whole-call timeout")
  void timeouts() {
    assertThat(complete(ConnectorKind.CLAUDE, "claude-api"))
        .isEqualTo(new AiCompletion.Failed(AiFailure.UNREACHABLE, "No answer within 1 s."));
    assertThat(complete(ConnectorKind.OPENAI, "openai-api"))
        .isEqualTo(new AiCompletion.Failed(AiFailure.UNREACHABLE, "No answer within 1 s."));
    assertThat(complete(ConnectorKind.LOCAL_MODEL, "local-llm"))
        .isEqualTo(new AiCompletion.Failed(AiFailure.UNREACHABLE, "No answer within 2 s."));
    assertThat(embed(ConnectorKind.LOCAL_EMBEDDING, "local-embed", List.of("one")))
        .isEqualTo(new AiEmbedding.Failed(AiFailure.UNREACHABLE, "No answer within 3 s."));
    assertThat(embed(ConnectorKind.OPENAI, "openai-embed-api", List.of("one", "two")))
        .isEqualTo(new AiEmbedding.Failed(AiFailure.UNREACHABLE, "No answer within 4 s."));
    String closed = "Could not connect to 127.0.0.1:" + ai.closedPort + ".";
    assertThat(complete(ConnectorKind.CLAUDE, "claude-api-closed"))
        .isEqualTo(new AiCompletion.Failed(AiFailure.UNREACHABLE, closed));
    assertThat(embed(ConnectorKind.LOCAL_EMBEDDING, "local-embed-closed", List.of("one")))
        .isEqualTo(new AiEmbedding.Failed(AiFailure.UNREACHABLE, closed));
  }

  @Test
  @DisplayName("AR-TM-01 the production timings are 5, 10, 90, 120, 30 and 90 s")
  void production() {
    ConnectorTimings timings = new ConnectorConfiguration().connectorTimings();
    assertThat(
            List.of(
                timings.connect(),
                timings.monitoringTest(),
                timings.chat(),
                timings.localChat(),
                timings.embedOne(),
                timings.embedBatch()))
        .containsExactly(
            Duration.ofSeconds(5),
            Duration.ofSeconds(10),
            Duration.ofSeconds(90),
            Duration.ofSeconds(120),
            Duration.ofSeconds(30),
            Duration.ofSeconds(90));
  }
}
