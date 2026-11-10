package vigil.connectors;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
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
import vigil.connectors.Recordings.ProviderCall;
import vigil.connectors.api.AiCompletion;
import vigil.connectors.api.AiFailure;
import vigil.connectors.api.AiFinish;
import vigil.connectors.api.AiProviderConnector;
import vigil.connectors.api.ConnectorKind;

class ClaudeConnectorIT {

  private static final String PATH = "/v1/messages";
  private static AiConnectorApp ai;
  private static AiProviderConnector claude;

  @BeforeAll
  static void start() {
    ai = new AiConnectorApp();
    claude = ai.connector(ConnectorKind.CLAUDE);
  }

  @AfterAll
  static void stop() {
    ai.close();
  }

  @BeforeEach
  void reset() {
    ai.wiremock.resetAll();
  }

  private static void answer(int status, String body) {
    ai.wiremock.stubFor(
        post(urlEqualTo(PATH))
            .willReturn(
                aResponse()
                    .withStatus(status)
                    .withHeader("Content-Type", "application/json")
                    .withBody(body)));
  }

  private static void answer(ProviderCall call) {
    answer(call.status(), call.responseBody());
  }

  private static AiCompletion complete(String handle, ProviderCall call) {
    JsonNode request = call.request();
    return claude.complete(
        ai.tenantId,
        ai.instance(handle),
        request.get("model").stringValue(),
        request.get("system").stringValue(),
        AiConnectorApp.TWO_PARTS,
        AiConnectorApp.SL_T_1_SCHEMA_NAME,
        request.get("output_config").get("format").get("schema"),
        request.get("max_tokens").intValue());
  }

  private static LoggedRequest onlyRequest() {
    List<LoggedRequest> requests = ai.wiremock.findAll(anyRequestedFor(anyUrl()));
    assertThat(requests).hasSize(1);
    return requests.getFirst();
  }

  @Test
  @DisplayName("AR-G-20 the Claude Messages call as recorded, and its answer read")
  void messages() {
    ProviderCall call = Recordings.provider("claude", "0001-sl-t-1.json");
    answer(call);
    AiCompletion result = complete("claude-api", call);
    LoggedRequest request = onlyRequest();
    assertThat(request.getMethod().getName()).isEqualTo("POST");
    assertThat(request.getUrl()).isEqualTo(PATH);
    assertThat(request.getHeader("x-api-key")).isEqualTo(ai.secrets.value("claude"));
    assertThat(request.getHeader("anthropic-version")).isEqualTo("2023-06-01");
    assertThat(request.getHeader("Content-Type")).isEqualTo("application/json");
    JsonNode sent = Recordings.tree(request.getBodyAsString());
    JsonNode recorded = call.request();
    assertThat(sent.propertyNames())
        .containsExactly("model", "max_tokens", "system", "messages", "output_config");
    for (String field : List.of("model", "max_tokens", "system", "output_config")) {
      assertThat(sent.get(field)).as(field).isEqualTo(recorded.get(field));
    }
    assertThat(sent.get("messages")).hasSize(1);
    assertThat(sent.get("messages").get(0).get("role"))
        .isEqualTo(recorded.get("messages").get(0).get("role"));
    assertThat(sent.get("messages").get(0).get("content").stringValue())
        .isEqualTo(AiConnectorApp.TWO_PARTS_RENDERED);
    String text =
        Recordings.tree(call.responseBody()).get("content").get(0).get("text").stringValue();
    assertThat(result).isEqualTo(new AiCompletion.Answered(text, AiFinish.COMPLETE, 8969, 472));
  }

  @Test
  @DisplayName("AR-G-13 truncated, refused, error, rate-limited and unreadable answers")
  void answers() {
    ProviderCall truncated = Recordings.provider("claude", "0003-truncated.json");
    answer(truncated);
    assertThat(complete("claude-api", truncated))
        .isEqualTo(
            new AiCompletion.Answered(
                "{\"likely_cause\":\"The /data volume on the", AiFinish.TRUNCATED, 8969, 16));
    answer(
        200,
        "{\"content\":[{\"type\":\"text\",\"text\":\"I cannot help.\"}],\"stop_reason\":\"refusal\","
            + "\"usage\":{\"input_tokens\":10,\"output_tokens\":3}}");
    assertThat(complete("claude-api", truncated))
        .isEqualTo(new AiCompletion.Answered("I cannot help.", AiFinish.REFUSED, 10, 3));
    ProviderCall badKey = Recordings.provider("claude", "0004-bad-key.json");
    answer(badKey);
    assertThat(complete("claude-api", truncated))
        .isEqualTo(new AiCompletion.Failed(AiFailure.PROVIDER_ERROR, "HTTP 401"));
    answer(429, "{\"type\":\"error\"}");
    assertThat(complete("claude-api", truncated))
        .isEqualTo(new AiCompletion.Failed(AiFailure.PROVIDER_ERROR, "HTTP 429"));
    answer(200, "not json");
    assertThat(complete("claude-api", truncated))
        .isEqualTo(new AiCompletion.Answered(null, AiFinish.COMPLETE, 0, 0));
  }

  @Test
  @DisplayName("PM-K-07 a missing secret is provider_error and no request is sent")
  void missingSecret() {
    ProviderCall call = Recordings.provider("claude", "0001-sl-t-1.json");
    answer(call);
    String key = ai.secrets.value("claude");
    ai.secrets.remove("claude");
    try {
      assertThat(complete("claude-api", call))
          .isEqualTo(
              new AiCompletion.Failed(AiFailure.PROVIDER_ERROR, "Secret claude is not available."));
      ai.wiremock.verify(0, anyRequestedFor(anyUrl()));
    } finally {
      ai.secrets.replace("claude", key);
    }
  }

  @Test
  @DisplayName(
      "AR-K-07 anthropic_version from the instance's settings, the key from its credential")
  void settingsAndKey() {
    ProviderCall call = Recordings.provider("claude", "0001-sl-t-1.json");
    answer(call);
    assertThat(complete("claude-version", call)).isInstanceOf(AiCompletion.Answered.class);
    LoggedRequest request = onlyRequest();
    assertThat(request.getHeader("anthropic-version")).isEqualTo("2099-01-01");
    assertThat(request.getHeader("x-api-key")).isEqualTo(ai.secrets.value("claude"));
  }
}
