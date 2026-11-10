package vigil.aigateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import vigil.aigateway.api.AiGatewayImport;
import vigil.aigateway.api.AiGatewayPart;
import vigil.aigateway.api.AiGatewayService;
import vigil.aigateway.api.AiPart;
import vigil.aigateway.api.AiTask;
import vigil.aigateway.api.AnswerFinish;
import vigil.aigateway.api.CompletionRequest;
import vigil.aigateway.api.CompletionResult;
import vigil.aigateway.api.DataCategory;
import vigil.aigateway.api.EmbeddingRequest;
import vigil.aigateway.api.EmbeddingResult;
import vigil.aigateway.api.ProviderLocation;
import vigil.aigateway.api.UnavailableReason;
import vigil.aigateway.api.UsageOutcome;
import vigil.connectors.api.ConnectorImport;
import vigil.shared.Ids;
import vigil.shared.TenantId;
import vigil.testsupport.TestSecrets;
import vigil.testsupport.VigilDatabase;
import vigil.testsupport.VigilTestApp;

class AiGatewayIT {

  private static final String CLAUDE = "/v1/messages";
  private static final String OPENAI = "/v1/responses";
  private static final String LOCAL = "/api/chat";
  private static final String EMBED = "/api/embed";
  private static final JsonNode SCHEMA =
      AiFixture.YAML.readTree("{type: object, properties: {summary: {type: string}}}");
  private static final String CLAUDE_ANSWER =
      "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"summary\\\":\\\"claude\\\"}\"}],"
          + "\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":1000,\"output_tokens\":100}}";
  private static final String OPENAI_ANSWER =
      "{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":"
          + "[{\"type\":\"output_text\",\"text\":\"{\\\"summary\\\":\\\"openai\\\"}\"}]}],"
          + "\"usage\":{\"input_tokens\":500,\"output_tokens\":50}}";
  private static final String LOCAL_ANSWER =
      "{\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"summary\\\":\\\"local\\\"}\"},"
          + "\"done_reason\":\"stop\",\"prompt_eval_count\":300,\"eval_count\":30}";

  private static WireMockServer wiremock;
  private static VigilTestApp app;
  private static AiGatewayService gateway;
  private static JdbcClient jdbc;
  private static String local;
  private static String closed;

  @BeforeAll
  static void start() throws Exception {
    wiremock = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort());
    wiremock.start();
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      closed = "http://127.0.0.1:" + socket.getLocalPort();
    }
    local = "http://127.0.0.1:" + wiremock.port();
    VigilDatabase database = VigilDatabase.fresh();
    TestSecrets secrets = TestSecrets.create(database, AiFixture.secrets());
    app = VigilTestApp.start(database, secrets.folder(), List.of());
    app.awaitStarted();
    gateway = app.context().getBean(AiGatewayService.class);
    jdbc = app.context().getBean(JdbcClient.class);
  }

  @AfterAll
  static void stop() {
    app.close();
    wiremock.stop();
  }

  @BeforeEach
  void reset() {
    wiremock.resetAll();
  }

  /** A new tenant with part 3 at the given addresses (WireMock by default) and part 7 changed. */
  private static TenantId tenant(Map<String, String> addresses, UnaryOperator<AiGatewayPart> part) {
    Map<String, String> all = new LinkedHashMap<>();
    AiFixture.HANDLES.forEach(handle -> all.put(handle, addresses.getOrDefault(handle, local)));
    TenantId tenantId = new TenantId(Ids.newId());
    Instant now = Instant.now();
    Map<String, UUID> handles =
        app.context()
            .getBean(ConnectorImport.class)
            .importPart(tenantId, AiFixture.connectors(all), now, Map.of());
    app.context()
        .getBean(AiGatewayImport.class)
        .importPart(tenantId, part.apply(AiFixture.demoPart()), now, handles);
    return tenantId;
  }

  private static TenantId tenant() {
    return tenant(Map.of(), part -> part);
  }

  private static void answer(String path, int status, String body) {
    wiremock.stubFor(
        post(urlEqualTo(path))
            .willReturn(
                aResponse()
                    .withStatus(status)
                    .withHeader("Content-Type", "application/json")
                    .withBody(body)));
  }

  private static CompletionRequest request(TenantId tenantId, List<AiPart> parts) {
    return new CompletionRequest(
        tenantId,
        AiTask.SL_T_2,
        Ids.newId(),
        Ids.newId(),
        "Write a summary.",
        parts,
        "sl_t_2",
        SCHEMA,
        600);
  }

  private static CompletionRequest request(TenantId tenantId) {
    return request(
        tenantId, List.of(new AiPart("Alert count and types", DataCategory.ALERT_DATA, "7")));
  }

  /** The usage records of a request group in insertion order. */
  private static List<Map<String, Object>> usage(TenantId tenantId, UUID requestGroupId) {
    return jdbc.sql(
            "SELECT task, provider_key, provider_location, model_name, request_group_id,"
                + " incident_id, fallback_position, data_categories::text AS data_categories,"
                + " outcome, input_units, output_units, cost, started_at, finished_at"
                + " FROM ai_gateway.ai_usage_record WHERE tenant_id = :t AND request_group_id = :g"
                + " ORDER BY id")
        .param("t", tenantId.value())
        .param("g", requestGroupId)
        .query()
        .listOfRows();
  }

  private static long usageCount(TenantId tenantId) {
    return jdbc.sql("SELECT count(*) FROM ai_gateway.ai_usage_record WHERE tenant_id = :t")
        .param("t", tenantId.value())
        .query(Long.class)
        .single();
  }

  private static List<String> outcomes(List<Map<String, Object>> records) {
    List<String> outcomes = new ArrayList<>();
    for (Map<String, Object> record : records) {
      outcomes.add(
          record.get("provider_key")
              + " "
              + record.get("fallback_position")
              + " "
              + record.get("outcome"));
    }
    return outcomes;
  }

  @Test
  @DisplayName("PM-M18-04 one usage record with units and cost for the provider that answers")
  void firstProviderAnswers() {
    TenantId tenantId = tenant();
    answer(CLAUDE, 200, CLAUDE_ANSWER);
    CompletionRequest request = request(tenantId);
    CompletionResult result = gateway.complete(request);
    assertThat(result)
        .isEqualTo(
            new CompletionResult.Answered(
                "claude",
                ProviderLocation.EXTERNAL,
                "claude-sonnet-5-5",
                1,
                "{\"summary\":\"claude\"}",
                AnswerFinish.COMPLETE,
                1000,
                100));
    List<Map<String, Object>> records = usage(tenantId, request.requestGroupId());
    assertThat(records).hasSize(1);
    Map<String, Object> record = records.getFirst();
    assertThat(record.get("task")).isEqualTo("SL-T-2");
    assertThat(record.get("provider_key")).isEqualTo("claude");
    assertThat(record.get("provider_location")).isEqualTo("external");
    assertThat(record.get("model_name")).isEqualTo("claude-sonnet-5-5");
    assertThat(record.get("fallback_position")).isEqualTo(1L);
    assertThat(record.get("outcome")).isEqualTo("succeeded");
    assertThat(record.get("input_units")).isEqualTo(1000L);
    assertThat(record.get("output_units")).isEqualTo(100L);
    assertThat((BigDecimal) record.get("cost")).isEqualByComparingTo("0.003000");
  }

  @Test
  @DisplayName("PM-M18-03 fallback on unreachable and provider_error to the provider that answers")
  void fallback() {
    TenantId tenantId = tenant(Map.of("claude-api", closed), part -> part);
    answer(OPENAI, 500, "{}");
    answer(LOCAL, 200, LOCAL_ANSWER);
    CompletionRequest request = request(tenantId);
    CompletionResult result = gateway.complete(request);
    assertThat(result).isInstanceOf(CompletionResult.Answered.class);
    CompletionResult.Answered answered = (CompletionResult.Answered) result;
    assertThat(answered.providerKey()).isEqualTo("local-llm");
    assertThat(answered.location()).isEqualTo(ProviderLocation.IN_HOUSE);
    assertThat(answered.fallbackPosition()).isEqualTo(3);
    assertThat(answered.outputText()).isEqualTo("{\"summary\":\"local\"}");
    assertThat(outcomes(usage(tenantId, request.requestGroupId())))
        .containsExactly(
            "claude 1 unreachable", "openai 2 provider_error", "local-llm 3 succeeded");
  }

  @Test
  @DisplayName("SL-A2-05 unavailable when no provider answers, also when one was refused")
  void unavailable() {
    TenantId tenantId = tenant(Map.of("claude-api", closed), part -> part);
    answer(OPENAI, 500, "{}");
    answer(LOCAL, 503, "{}");
    CompletionRequest request = request(tenantId);
    assertThat(gateway.complete(request))
        .isEqualTo(new CompletionResult.Unavailable(UnavailableReason.NO_PROVIDER_REACHABLE));
    assertThat(outcomes(usage(tenantId, request.requestGroupId())))
        .containsExactly(
            "claude 1 unreachable", "openai 2 provider_error", "local-llm 3 provider_error");

    TenantId refusing =
        tenant(
            Map.of(),
            part ->
                AiFixture.rules(
                    part,
                    rules -> {
                      rules.put("alert_data", List.of("local-llm", "openai"));
                      return rules;
                    }));
    CompletionRequest second = request(refusing);
    assertThat(gateway.complete(second))
        .isEqualTo(new CompletionResult.Unavailable(UnavailableReason.NO_PROVIDER_REACHABLE));
    assertThat(outcomes(usage(refusing, second.requestGroupId())))
        .containsExactly(
            "claude 1 refused_by_data_routing",
            "openai 2 provider_error",
            "local-llm 3 provider_error");
  }

  @Test
  @DisplayName("DM-R-69 a truncated or refused answer is a result: no further provider is tried")
  void truncatedAndRefused() {
    TenantId tenantId = tenant();
    answer(OPENAI, 200, OPENAI_ANSWER);
    answer(
        CLAUDE,
        200,
        "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"sum\"}],\"stop_reason\":\"max_tokens\","
            + "\"usage\":{\"input_tokens\":10,\"output_tokens\":5}}");
    CompletionRequest request = request(tenantId);
    CompletionResult.Answered truncated = (CompletionResult.Answered) gateway.complete(request);
    assertThat(truncated.providerKey()).isEqualTo("claude");
    assertThat(truncated.finish()).isEqualTo(AnswerFinish.TRUNCATED);
    assertThat(truncated.outputText()).isEqualTo("{\"sum");
    answer(
        CLAUDE,
        200,
        "{\"content\":[],\"stop_reason\":\"refusal\","
            + "\"usage\":{\"input_tokens\":10,\"output_tokens\":0}}");
    CompletionResult.Answered refused = (CompletionResult.Answered) gateway.complete(request);
    assertThat(refused.providerKey()).isEqualTo("claude");
    assertThat(refused.finish()).isEqualTo(AnswerFinish.REFUSED);
    assertThat(refused.outputText()).isNull();
    wiremock.verify(0, postRequestedFor(urlEqualTo(OPENAI)));
    assertThat(outcomes(usage(tenantId, request.requestGroupId())))
        .containsExactly("claude 1 succeeded", "claude 1 succeeded");
  }

  @Test
  @DisplayName("DM-R-45 a provider not allowed for every category of the request is not sent to")
  void dataRouting() {
    TenantId tenantId =
        tenant(
            Map.of(),
            part ->
                AiFixture.rules(
                    part,
                    rules -> {
                      rules.put("runbook_text", List.of("local-llm", "openai"));
                      return rules;
                    }));
    answer(CLAUDE, 200, CLAUDE_ANSWER);
    answer(OPENAI, 200, OPENAI_ANSWER);
    CompletionRequest request =
        request(
            tenantId,
            List.of(
                new AiPart("Member alerts", DataCategory.ALERT_DATA, "alerts"),
                new AiPart("RB-DB-01", DataCategory.RUNBOOK_TEXT, "runbook")));
    CompletionResult.Answered answered = (CompletionResult.Answered) gateway.complete(request);
    assertThat(answered.providerKey()).isEqualTo("openai");
    assertThat(answered.fallbackPosition()).isEqualTo(2);
    wiremock.verify(0, postRequestedFor(urlEqualTo(CLAUDE)));
    List<Map<String, Object>> records = usage(tenantId, request.requestGroupId());
    assertThat(outcomes(records))
        .containsExactly("claude 1 refused_by_data_routing", "openai 2 succeeded");
    assertThat(records.getFirst().get("input_units")).isEqualTo(0L);
    assertThat(records.getFirst().get("output_units")).isEqualTo(0L);
    assertThat((BigDecimal) records.getFirst().get("cost")).isEqualByComparingTo("0");
  }

  @Test
  @DisplayName("PM-M18-02 nothing is sent when data routing allows no provider")
  void noProviderAllowed() {
    TenantId tenantId =
        tenant(
            Map.of(),
            part ->
                AiFixture.rules(
                    part,
                    rules -> {
                      rules.put("alert_data", List.of());
                      return rules;
                    }));
    answer(CLAUDE, 200, CLAUDE_ANSWER);
    CompletionRequest request = request(tenantId);
    assertThat(gateway.complete(request))
        .isEqualTo(new CompletionResult.Unavailable(UnavailableReason.NO_PROVIDER_ALLOWED));
    wiremock.verify(0, anyRequestedFor(anyUrl()));
    assertThat(outcomes(usage(tenantId, request.requestGroupId())))
        .containsExactly(
            "claude 1 refused_by_data_routing",
            "openai 2 refused_by_data_routing",
            "local-llm 3 refused_by_data_routing");
  }

  @Test
  @DisplayName("PM-M18-01 the providers are tried in the order of the task assignment")
  void assignmentOrder() {
    TenantId tenantId = tenant();
    jdbc.sql(
            "UPDATE ai_gateway.task_assignment SET provider_keys ="
                + " CAST('[\"local-llm\",\"openai\",\"claude\"]' AS jsonb)"
                + " WHERE tenant_id = :t AND task = 'SL-T-2'")
        .param("t", tenantId.value())
        .update();
    answer(CLAUDE, 200, CLAUDE_ANSWER);
    answer(OPENAI, 500, "{}");
    answer(LOCAL, 500, "{}");
    CompletionRequest request = request(tenantId);
    CompletionResult.Answered answered = (CompletionResult.Answered) gateway.complete(request);
    assertThat(answered.providerKey()).isEqualTo("claude");
    assertThat(answered.fallbackPosition()).isEqualTo(3);
    assertThat(outcomes(usage(tenantId, request.requestGroupId())))
        .containsExactly(
            "local-llm 1 provider_error", "openai 2 provider_error", "claude 3 succeeded");
  }

  @Test
  @DisplayName("DM-E-74 the record carries the sorted categories, group, incident and times")
  void usageRecord() {
    TenantId tenantId = tenant();
    answer(CLAUDE, 200, CLAUDE_ANSWER);
    CompletionRequest request =
        request(
            tenantId,
            List.of(
                new AiPart("RB-DB-01", DataCategory.RUNBOOK_TEXT, "runbook"),
                new AiPart("Member alerts", DataCategory.ALERT_DATA, "alerts"),
                new AiPart("RB-DB-02", DataCategory.RUNBOOK_TEXT, "runbook")));
    gateway.complete(request);
    Map<String, Object> record = usage(tenantId, request.requestGroupId()).getFirst();
    assertThat(AiFixture.YAML.readTree((String) record.get("data_categories")).toString())
        .isEqualTo("[\"alert_data\",\"runbook_text\"]");
    assertThat(record.get("request_group_id")).isEqualTo(request.requestGroupId());
    assertThat(record.get("incident_id")).isEqualTo(request.incidentId());
    Timestamp started = (Timestamp) record.get("started_at");
    Timestamp finished = (Timestamp) record.get("finished_at");
    assertThat(started).isBeforeOrEqualTo(finished);
  }

  @Test
  @DisplayName("AR-G-10 a request that breaks an argument rule throws and stores nothing")
  void arguments() {
    TenantId tenantId = tenant();
    answer(CLAUDE, 200, CLAUDE_ANSWER);
    CompletionRequest valid = request(tenantId);
    List<AiPart> parts = valid.parts();
    List<CompletionRequest> invalid =
        List.of(
            new CompletionRequest(
                tenantId, AiTask.SL_T_3, Ids.newId(), null, "s", parts, "n", SCHEMA, 1),
            new CompletionRequest(
                tenantId, AiTask.SL_T_1, Ids.newId(), null, null, parts, "n", SCHEMA, 1),
            new CompletionRequest(
                tenantId, AiTask.SL_T_1, Ids.newId(), null, "s", List.of(), "n", SCHEMA, 1),
            new CompletionRequest(
                tenantId, AiTask.SL_T_1, Ids.newId(), null, "s", parts, "n", SCHEMA, 0),
            new CompletionRequest(tenantId, AiTask.SL_T_1, null, null, "s", parts, "n", SCHEMA, 1),
            new CompletionRequest(
                tenantId,
                AiTask.SL_T_1,
                Ids.newId(),
                null,
                "s",
                List.of(new AiPart("label", DataCategory.ALERT_DATA, null)),
                "n",
                SCHEMA,
                1));
    for (CompletionRequest request : invalid) {
      assertThatThrownBy(() -> gateway.complete(request))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(
            () ->
                gateway.embed(
                    new EmbeddingRequest(
                        tenantId, "local-embed", Ids.newId(), null, Set.of(), List.of("t"))))
        .isInstanceOf(IllegalArgumentException.class);
    wiremock.verify(0, anyRequestedFor(anyUrl()));
    assertThat(usageCount(tenantId)).isZero();
  }

  @Test
  @DisplayName("DM-E-72 SL-T-3 embeds with the named provider: one record, output units 0")
  void embed() {
    TenantId tenantId = tenant();
    answer(EMBED, 200, "{\"embeddings\":[[0.5,0.25],[1,2]],\"prompt_eval_count\":12}");
    EmbeddingRequest request =
        new EmbeddingRequest(
            tenantId,
            "local-embed",
            Ids.newId(),
            null,
            Set.of(DataCategory.INCIDENT_HISTORY),
            List.of("one", "two"));
    EmbeddingResult result = gateway.embed(request);
    assertThat(result).isInstanceOf(EmbeddingResult.Embedded.class);
    EmbeddingResult.Embedded embedded = (EmbeddingResult.Embedded) result;
    assertThat(embedded.providerKey()).isEqualTo("local-embed");
    assertThat(embedded.modelName()).isEqualTo("embeddinggemma");
    assertThat(embedded.inputUnits()).isEqualTo(12);
    assertThat(embedded.vectors()).hasSize(2);
    assertThat(embedded.vectors().get(0)).containsExactly(0.5f, 0.25f);
    List<Map<String, Object>> records = usage(tenantId, request.requestGroupId());
    assertThat(outcomes(records)).containsExactly("local-embed 1 succeeded");
    assertThat(records.getFirst().get("task")).isEqualTo("SL-T-3");
    assertThat(records.getFirst().get("input_units")).isEqualTo(12L);
    assertThat(records.getFirst().get("output_units")).isEqualTo(0L);
    assertThat((BigDecimal) records.getFirst().get("cost")).isEqualByComparingTo("0");
    assertThat(records.getFirst().get("incident_id")).isNull();
  }

  @Test
  @DisplayName("DM-E-72 SL-T-3 has no fallback: a kind not allowed, routing and unreachable")
  void embedUnavailable() {
    TenantId tenantId =
        tenant(
            Map.of("local-embed", closed),
            part ->
                AiFixture.rules(
                    part,
                    rules -> {
                      rules.put("incident_history", List.of("claude", "local-embed", "local-llm"));
                      return rules;
                    }));
    answer("/v1/embeddings", 200, "{\"data\":[{\"index\":0,\"embedding\":[1]}]}");
    Set<DataCategory> history = Set.of(DataCategory.INCIDENT_HISTORY);
    EmbeddingRequest claude =
        new EmbeddingRequest(tenantId, "claude", Ids.newId(), null, history, List.of("t"));
    assertThat(gateway.embed(claude))
        .isEqualTo(new EmbeddingResult.Unavailable(UsageOutcome.PROVIDER_ERROR));
    assertThat(usage(tenantId, claude.requestGroupId())).isEmpty();

    EmbeddingRequest openai =
        new EmbeddingRequest(tenantId, "openai-embed", Ids.newId(), null, history, List.of("t"));
    assertThat(gateway.embed(openai))
        .isEqualTo(new EmbeddingResult.Unavailable(UsageOutcome.REFUSED_BY_DATA_ROUTING));
    assertThat(outcomes(usage(tenantId, openai.requestGroupId())))
        .containsExactly("openai-embed 1 refused_by_data_routing");

    EmbeddingRequest unreachable =
        new EmbeddingRequest(tenantId, "local-embed", Ids.newId(), null, history, List.of("t"));
    assertThat(gateway.embed(unreachable))
        .isEqualTo(new EmbeddingResult.Unavailable(UsageOutcome.UNREACHABLE));
    assertThat(outcomes(usage(tenantId, unreachable.requestGroupId())))
        .containsExactly("local-embed 1 unreachable");
    wiremock.verify(0, anyRequestedFor(anyUrl()));
  }
}
