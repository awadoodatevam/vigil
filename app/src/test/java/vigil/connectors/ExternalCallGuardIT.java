package vigil.connectors;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import vigil.aigateway.AiFixture;
import vigil.aigateway.api.AiGatewayImport;
import vigil.aigateway.api.AiGatewayService;
import vigil.aigateway.api.AiPart;
import vigil.aigateway.api.AiTask;
import vigil.aigateway.api.CompletionRequest;
import vigil.aigateway.api.DataCategory;
import vigil.aigateway.api.EmbeddingRequest;
import vigil.connectors.api.AiPromptPart;
import vigil.connectors.api.AiProviderConnector;
import vigil.connectors.api.ConnectionTestResult;
import vigil.connectors.api.ConnectorImport;
import vigil.connectors.api.ConnectorInstanceView;
import vigil.connectors.api.ConnectorKind;
import vigil.connectors.api.ConnectorService;
import vigil.connectors.api.ConnectorsPart;
import vigil.connectors.api.MessageLine;
import vigil.connectors.api.MonitoringSourceConnector;
import vigil.connectors.api.NotificationChannelConnector;
import vigil.connectors.api.ParseResult;
import vigil.connectors.api.TargetConnector;
import vigil.connectors.api.TargetResult;
import vigil.connectors.api.TicketLine;
import vigil.connectors.api.TicketingConnector;
import vigil.diagnostics.api.DiagnosticsStepHandler;
import vigil.incidents.api.EscalationCause;
import vigil.notifications.api.ApprovalRecipient;
import vigil.notifications.api.NotificationsService;
import vigil.notifications.api.NotificationsStepHandler;
import vigil.routing.api.RoutingStepHandler;
import vigil.runner.api.OperationRunState;
import vigil.runner.api.OperationRunView;
import vigil.runner.api.PermissionKind;
import vigil.runner.api.RunRequest;
import vigil.runner.api.RunnerService;
import vigil.shared.Actor;
import vigil.shared.Ids;
import vigil.shared.Key;
import vigil.shared.StepCall;
import vigil.shared.TenantId;
import vigil.testsupport.TestSecrets;
import vigil.testsupport.VigilDatabase;
import vigil.testsupport.VigilTestApp;
import vigil.ticketing.api.TicketingService;
import vigil.ticketing.api.TicketingStepHandler;

class ExternalCallGuardIT {

  private static WireMockServer wiremock;
  private static VigilTestApp app;
  private static ConnectorService connectors;
  private static TransactionTemplate transaction;
  private static Map<ConnectorKind, MonitoringSourceConnector> beans;
  private static TenantId tenantA;
  private static Map<String, UUID> ids;

  @BeforeAll
  static void start() {
    wiremock = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort());
    wiremock.start();
    VigilDatabase database = VigilDatabase.fresh();
    TestSecrets secrets =
        TestSecrets.create(
            database,
            Map.of(
                "zabbix-api", TestSecrets.randomToken(),
                "alertmanager-password", TestSecrets.randomToken(),
                "claude", TestSecrets.randomToken(),
                "openai", TestSecrets.randomToken()));
    app = VigilTestApp.start(database, secrets.folder(), List.of());
    app.awaitStarted();
    connectors = app.context().getBean(ConnectorService.class);
    transaction = new TransactionTemplate(app.context().getBean(PlatformTransactionManager.class));
    beans = new LinkedHashMap<>();
    app.context()
        .getBeansOfType(MonitoringSourceConnector.class)
        .values()
        .forEach(bean -> beans.put(bean.kind(), bean));
    tenantA = new TenantId(Ids.newId());
    String local = "http://127.0.0.1:" + wiremock.port();
    ids =
        app.context()
            .getBean(ConnectorImport.class)
            .importPart(
                tenantA,
                new ConnectorsPart(
                    List.of(
                        TestParts.credential("za", "external_api", "secret:zabbix-api"),
                        TestParts.credential("aa", "external_api", "secret:alertmanager-password")),
                    List.of(
                        TestParts.connector(
                            "zabbix-ok",
                            "zabbix",
                            local,
                            "{\"api_path\":\"/api_jsonrpc.php\"}",
                            "za"),
                        TestParts.connector(
                            "alertmanager-ok",
                            "alertmanager",
                            local,
                            "{\"username\":\"vigil\"}",
                            "aa"))),
                Instant.now(),
                Map.of());
    wiremock.stubFor(
        post(urlEqualTo(ConnectionTestIT.ZABBIX_PATH))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withBody("{\"jsonrpc\":\"2.0\",\"result\":[],\"id\":1}")));
    wiremock.stubFor(
        get(urlEqualTo(ConnectionTestIT.ALERTMANAGER_PATH))
            .willReturn(aResponse().withStatus(200)));
    wiremock.stubFor(
        post(urlEqualTo("/v1/messages"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withBody(
                        "{\"content\":[{\"type\":\"text\",\"text\":\"{}\"}],"
                            + "\"stop_reason\":\"end_turn\"}")));
    wiremock.stubFor(
        post(urlEqualTo("/v1/responses"))
            .willReturn(aResponse().withStatus(200).withBody("{\"status\":\"completed\"}")));
    wiremock.stubFor(
        post(urlEqualTo("/v1/embeddings"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withBody("{\"data\":[{\"index\":0,\"embedding\":[1.0]}]}")));
    wiremock.stubFor(
        post(urlEqualTo("/api/chat"))
            .willReturn(
                aResponse().withStatus(200).withBody("{\"message\":{\"content\":\"{}\"}}")));
    wiremock.stubFor(
        post(urlEqualTo("/api/embed"))
            .willReturn(aResponse().withStatus(200).withBody("{\"embeddings\":[[1.0]]}")));
  }

  /** A tenant with the five AI instances of the demo at WireMock and the demo part 7. */
  private record AiTenant(TenantId tenantId, Map<String, UUID> handles) {

    ConnectorInstanceView instance(String handle) {
      return connectors.instance(tenantId, handles.get(handle)).orElseThrow();
    }
  }

  private static AiTenant aiTenant() {
    TenantId tenantId = new TenantId(Ids.newId());
    Map<String, UUID> handles =
        app.context()
            .getBean(ConnectorImport.class)
            .importPart(
                tenantId,
                AiFixture.connectors("http://127.0.0.1:" + wiremock.port()),
                Instant.now(),
                Map.of());
    app.context()
        .getBean(AiGatewayImport.class)
        .importPart(tenantId, AiFixture.demoPart(), Instant.now(), handles);
    return new AiTenant(tenantId, handles);
  }

  private static AiProviderConnector aiConnector(ConnectorKind kind) {
    return app.context().getBeansOfType(AiProviderConnector.class).values().stream()
        .filter(bean -> bean.kind() == kind)
        .findFirst()
        .orElseThrow();
  }

  private static final List<AiPromptPart> AI_PARTS =
      List.of(new AiPromptPart("Member alerts", "Alert data", "alerts"));

  private static Supplier<Object> aiComplete(AiTenant tenant, ConnectorKind kind, String handle) {
    return () ->
        aiConnector(kind)
            .complete(
                tenant.tenantId(),
                tenant.instance(handle),
                "model",
                "system",
                AI_PARTS,
                "sl_t_2",
                Recordings.tree("{\"type\":\"object\"}"),
                10);
  }

  private static Supplier<Object> aiEmbed(AiTenant tenant, ConnectorKind kind, String handle) {
    return () ->
        aiConnector(kind).embed(tenant.tenantId(), tenant.instance(handle), "model", List.of("t"));
  }

  private static Supplier<Object> gatewayComplete(AiTenant tenant) {
    return () ->
        app.context()
            .getBean(AiGatewayService.class)
            .complete(
                new CompletionRequest(
                    tenant.tenantId(),
                    AiTask.SL_T_2,
                    Ids.newId(),
                    null,
                    "system",
                    List.of(new AiPart("Title", DataCategory.INCIDENT_RECORD, "title")),
                    "sl_t_2",
                    Recordings.tree("{\"type\":\"object\"}"),
                    10));
  }

  private static Supplier<Object> gatewayEmbed(AiTenant tenant) {
    return () ->
        app.context()
            .getBean(AiGatewayService.class)
            .embed(
                new EmbeddingRequest(
                    tenant.tenantId(),
                    "local-embed",
                    Ids.newId(),
                    null,
                    Set.of(DataCategory.INCIDENT_HISTORY),
                    List.of("t")));
  }

  private static long usageRecords(AiTenant tenant) {
    return app.context()
        .getBean(JdbcClient.class)
        .sql("SELECT count(*) FROM ai_gateway.ai_usage_record WHERE tenant_id = :t")
        .param("t", tenant.tenantId().value())
        .query(Long.class)
        .single();
  }

  @Test
  @DisplayName(
      "AR-Q-04 the AI gateway and AI connector entry points refuse to run in a transaction")
  void aiEntryPoints() {
    AiTenant tenant = aiTenant();
    assertThat(app.context().getBeansOfType(AiProviderConnector.class)).hasSize(4);
    Map<String, Supplier<Object>> entryPoints = new LinkedHashMap<>();
    entryPoints.put("AiGatewayService.complete", gatewayComplete(tenant));
    entryPoints.put("AiGatewayService.embed", gatewayEmbed(tenant));
    entryPoints.put("claude complete", aiComplete(tenant, ConnectorKind.CLAUDE, "claude-api"));
    entryPoints.put("claude embed", aiEmbed(tenant, ConnectorKind.CLAUDE, "claude-api"));
    entryPoints.put("openai complete", aiComplete(tenant, ConnectorKind.OPENAI, "openai-api"));
    entryPoints.put("openai embed", aiEmbed(tenant, ConnectorKind.OPENAI, "openai-embed-api"));
    entryPoints.put(
        "local_model complete", aiComplete(tenant, ConnectorKind.LOCAL_MODEL, "local-llm"));
    entryPoints.put("local_model embed", aiEmbed(tenant, ConnectorKind.LOCAL_MODEL, "local-llm"));
    entryPoints.put(
        "local_embedding complete",
        aiComplete(tenant, ConnectorKind.LOCAL_EMBEDDING, "local-embed"));
    entryPoints.put(
        "local_embedding embed", aiEmbed(tenant, ConnectorKind.LOCAL_EMBEDDING, "local-embed"));
    for (Map.Entry<String, Supplier<Object>> entry : entryPoints.entrySet()) {
      wiremock.resetRequests();
      assertThatThrownBy(() -> transaction.execute(status -> entry.getValue().get()))
          .as(entry.getKey())
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("external call inside a transaction");
      wiremock.verify(0, anyRequestedFor(anyUrl()));
    }
    assertThat(usageRecords(tenant)).isZero();
  }

  @Test
  @DisplayName("AR-R-09 outside a transaction the AI gateway and AI connectors make their calls")
  void aiOutsideTransaction() {
    AiTenant tenant = aiTenant();
    Map<String, Supplier<Object>> calls = new LinkedHashMap<>();
    calls.put("AiGatewayService.complete", gatewayComplete(tenant));
    calls.put("AiGatewayService.embed", gatewayEmbed(tenant));
    calls.put("claude complete", aiComplete(tenant, ConnectorKind.CLAUDE, "claude-api"));
    calls.put("openai complete", aiComplete(tenant, ConnectorKind.OPENAI, "openai-api"));
    calls.put("openai embed", aiEmbed(tenant, ConnectorKind.OPENAI, "openai-embed-api"));
    calls.put("local_model complete", aiComplete(tenant, ConnectorKind.LOCAL_MODEL, "local-llm"));
    calls.put(
        "local_embedding embed", aiEmbed(tenant, ConnectorKind.LOCAL_EMBEDDING, "local-embed"));
    for (Map.Entry<String, Supplier<Object>> entry : calls.entrySet()) {
      wiremock.resetRequests();
      Object result = entry.getValue().get();
      assertThat(result.toString()).as(entry.getKey()).doesNotContain("Failed", "Unavailable");
      wiremock.verify(1, anyRequestedFor(anyUrl()));
    }
    assertThat(usageRecords(tenant)).isEqualTo(2);
  }

  @AfterAll
  static void stop() {
    app.close();
    wiremock.stop();
  }

  private static ConnectorInstanceView instance(String handle) {
    return connectors.instance(tenantA, ids.get(handle)).orElseThrow();
  }

  @Test
  @DisplayName("AR-Q-04 every external call entry point refuses to run inside a transaction")
  void entryPoints() {
    assertThat(beans.keySet())
        .containsExactlyInAnyOrder(ConnectorKind.ZABBIX, ConnectorKind.ALERTMANAGER);
    assertThat(app.context().getBeansOfType(MonitoringSourceConnector.class)).hasSize(2);
    Map<String, Supplier<ConnectionTestResult>> entryPoints = new LinkedHashMap<>();
    entryPoints.put(
        "ConnectorService.testConnection",
        () -> connectors.testConnection(tenantA, ids.get("zabbix-ok")));
    entryPoints.put(
        "MonitoringSourceConnector.test of zabbix",
        () -> beans.get(ConnectorKind.ZABBIX).test(instance("zabbix-ok")));
    entryPoints.put(
        "MonitoringSourceConnector.test of alertmanager",
        () -> beans.get(ConnectorKind.ALERTMANAGER).test(instance("alertmanager-ok")));
    for (Map.Entry<String, Supplier<ConnectionTestResult>> entry : entryPoints.entrySet()) {
      wiremock.resetRequests();
      assertThatThrownBy(() -> transaction.execute(status -> entry.getValue().get()))
          .as(entry.getKey())
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("external call inside a transaction");
      wiremock.verify(0, anyRequestedFor(anyUrl()));
      assertThat(entry.getValue().get())
          .as(entry.getKey())
          .isEqualTo(new ConnectionTestResult.Passed());
      wiremock.verify(1, anyRequestedFor(anyUrl()));
    }
  }

  @Test
  @DisplayName("AR-R-09 parse is not an external call and does not read the content type")
  void parseIsNotGuarded() {
    byte[] body = Recordings.bytes(Recordings.body("0001-zabbix-lab.json"));
    ParseResult inside =
        transaction.execute(
            status -> connectors.parse(ConnectorKind.ZABBIX, body, "application/json"));
    assertThat(inside).isInstanceOf(ParseResult.Parsed.class);
    ParseResult expected = ZabbixPayloads.parse(body);
    for (String contentType : Arrays.asList("application/json", "text/plain", null)) {
      assertThat(connectors.parse(ConnectorKind.ZABBIX, body, contentType)).isEqualTo(expected);
    }
    assertThat(inside).isEqualTo(expected);
  }

  @Test
  @DisplayName("AR-Q-04 the ticketing entry points refuse to run inside a transaction")
  void ticketingEntryPoints() {
    TicketingConnector jira = app.context().getBean(TicketingConnector.class);
    TicketingStepHandler step = app.context().getBean(TicketingStepHandler.class);
    TicketingService ticketing = app.context().getBean(TicketingService.class);
    ConnectorInstanceView instance =
        new ConnectorInstanceView(
            tenantA,
            Ids.newId(),
            "guard",
            ConnectorKind.JIRA_CLOUD,
            "http://127.0.0.1:" + wiremock.port(),
            JsonMapper.builder().build().readTree("{\"project_key\":\"NOC\"}"),
            null,
            true);
    List<TicketLine> lines = List.of(TicketLine.of("line"));
    StepCall call =
        new StepCall(
            tenantA,
            Ids.newId(),
            Ids.newId(),
            Ids.newId(),
            new Key("s-ticket"),
            StepCall.StepType.TICKET,
            "{\"operation\": \"create\"}",
            1,
            null,
            new StepCall.WakeReason("start"),
            null,
            null);
    Map<String, Supplier<Object>> entryPoints = new LinkedHashMap<>();
    entryPoints.put("create", () -> jira.create(tenantA, instance, "t", lines, "vigil-x"));
    entryPoints.put("addComment", () -> jira.addComment(tenantA, instance, "NOC-1", lines));
    entryPoints.put("transition", () -> jira.transition(tenantA, instance, "NOC-1", "Done"));
    entryPoints.put("status", () -> jira.status(tenantA, instance, "NOC-1"));
    entryPoints.put("findByLabel", () -> jira.findByLabel(tenantA, instance, "vigil-x"));
    entryPoints.put("TicketingStepHandler.start", () -> step.start(call));
    entryPoints.put("TicketingStepHandler.evaluate", () -> step.evaluate(call));
    entryPoints.put(
        "TicketingService.recordEscalation",
        () ->
            ticketing.recordEscalation(
                tenantA,
                Ids.newId(),
                Ids.newId(),
                1,
                List.of(),
                EscalationCause.ESCALATE_NOW,
                null));
    for (Map.Entry<String, Supplier<Object>> entry : entryPoints.entrySet()) {
      wiremock.resetRequests();
      assertThatThrownBy(() -> transaction.execute(status -> entry.getValue().get()))
          .as(entry.getKey())
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("external call inside a transaction");
      wiremock.verify(0, anyRequestedFor(anyUrl()));
    }
  }

  @Test
  @DisplayName("AR-Q-04 the notification entry points refuse to run inside a transaction")
  void notificationEntryPoints() {
    NotificationsStepHandler notify = app.context().getBean(NotificationsStepHandler.class);
    RoutingStepHandler escalate = app.context().getBean(RoutingStepHandler.class);
    NotificationsService notifications = app.context().getBean(NotificationsService.class);
    StepCall notification =
        guardCall(StepCall.StepType.NOTIFICATION, "{\"recipients\": [\"owner_team\"]}");
    StepCall escalation = guardCall(StepCall.StepType.ESCALATION, "{\"mode\": \"escalate_now\"}");
    Map<String, Supplier<Object>> entryPoints = new LinkedHashMap<>();
    entryPoints.put("NotificationsStepHandler.start", () -> notify.start(notification));
    entryPoints.put("NotificationsStepHandler.evaluate", () -> notify.evaluate(notification));
    entryPoints.put("RoutingStepHandler.start", () -> escalate.start(escalation));
    entryPoints.put("RoutingStepHandler.evaluate", () -> escalate.evaluate(escalation));
    entryPoints.put(
        "NotificationsService.notifyEscalation",
        () ->
            notifications.notifyEscalation(
                tenantA,
                Ids.newId(),
                Ids.newId(),
                1,
                List.of(new Key("noc-l1")),
                EscalationCause.ESCALATE_NOW,
                null));
    entryPoints.put(
        "NotificationsService.notifyApprovalRequest",
        () ->
            notifications.notifyApprovalRequest(
                tenantA,
                Ids.newId(),
                Ids.newId(),
                List.of(new ApprovalRecipient(new Key("dbops-lead"), new Key("db-ops"))),
                "action",
                "target",
                Instant.now()));
    List<MessageLine> lines = List.of(MessageLine.of("line"));
    for (NotificationChannelConnector channel :
        app.context().getBeansOfType(NotificationChannelConnector.class).values()) {
      ConnectorInstanceView instance =
          new ConnectorInstanceView(
              tenantA,
              Ids.newId(),
              "guard",
              channel.kind(),
              channel.kind() == ConnectorKind.EMAIL
                  ? "smtp://127.0.0.1:" + wiremock.port()
                  : "https://teams.microsoft.com",
              JsonMapper.builder()
                  .build()
                  .readTree("{\"from_address\":\"a@vigil.test\",\"starttls\":false}"),
              null,
              true);
      entryPoints.put(
          "NotificationChannelConnector.send " + channel.kind().key(),
          () -> channel.send(tenantA, instance, "a@vigil.test", null, "Techfinity", lines));
    }
    assertThat(entryPoints).hasSize(8);
    for (Map.Entry<String, Supplier<Object>> entry : entryPoints.entrySet()) {
      wiremock.resetRequests();
      assertThatThrownBy(() -> transaction.execute(status -> entry.getValue().get()))
          .as(entry.getKey())
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("external call inside a transaction");
      wiremock.verify(0, anyRequestedFor(anyUrl()));
    }
  }

  private static StepCall guardCall(StepCall.StepType type, String parameters) {
    return new StepCall(
        tenantA,
        Ids.newId(),
        Ids.newId(),
        Ids.newId(),
        new Key("s-guard"),
        type,
        parameters,
        1,
        null,
        new StepCall.WakeReason("start"),
        null,
        null);
  }

  private static RunRequest guardRequest(String requestId) {
    return new RunRequest(
        requestId, PermissionKind.DIAGNOSTIC, "fs_usage", "lab-db-01", "{\"mount\":\"/data\"}");
  }

  private static ConnectorInstanceView guardInstance() {
    return new ConnectorInstanceView(
        tenantA,
        Ids.newId(),
        "guard",
        ConnectorKind.LINUX_SERVER,
        "nowhere.invalid",
        JsonMapper.builder().build().readTree("{\"port\":22,\"host_key_sha256\":\"SHA256:x\"}"),
        null,
        true);
  }

  @Test
  @DisplayName("AR-Q-04 the runner and diagnostics entry points refuse to run inside a transaction")
  void runnerEntryPoints() {
    RunnerService runner = app.context().getBean(RunnerService.class);
    TargetConnector target = app.context().getBean(TargetConnector.class);
    DiagnosticsStepHandler diagnostics = app.context().getBean(DiagnosticsStepHandler.class);
    StepCall call =
        new StepCall(
            tenantA,
            Ids.newId(),
            Ids.newId(),
            Ids.newId(),
            new Key("s-diagnose"),
            StepCall.StepType.DIAGNOSTIC,
            "{\"diagnostics\": [\"disk_usage\"], \"target\": \"hypothesis_component\"}",
            1,
            null,
            new StepCall.WakeReason("start"),
            null,
            null);
    Map<String, Supplier<Object>> entryPoints = new LinkedHashMap<>();
    entryPoints.put(
        "RunnerService.run",
        () -> runner.run(tenantA, Actor.module("vigil-diagnostics"), guardRequest("guard-1")));
    entryPoints.put(
        "TargetConnector.run",
        () -> target.run(tenantA, guardInstance(), Ids.newId(), "true", Duration.ofSeconds(1)));
    entryPoints.put("DiagnosticsStepHandler.start", () -> diagnostics.start(call));
    entryPoints.put("DiagnosticsStepHandler.evaluate", () -> diagnostics.evaluate(call));
    for (Map.Entry<String, Supplier<Object>> entry : entryPoints.entrySet()) {
      assertThatThrownBy(() -> transaction.execute(status -> entry.getValue().get()))
          .as(entry.getKey())
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("external call inside a transaction");
    }
    JdbcClient jdbc = app.context().getBean(JdbcClient.class);
    assertThat(
            jdbc.sql("SELECT count(*) FROM runner.operation_run WHERE request_id = 'guard-1'")
                .query(Long.class)
                .single())
        .isZero();
    assertThat(
            jdbc.sql(
                    "SELECT count(*) FROM diagnostics.diagnostic_result"
                        + " WHERE step_execution_id = :s")
                .param("s", call.stepExecutionId())
                .query(Long.class)
                .single())
        .isZero();
  }

  @Test
  @DisplayName("AR-R-09 outside a transaction the runner entry points make their calls")
  void runnerOutsideTransaction() {
    OperationRunView run =
        app.context()
            .getBean(RunnerService.class)
            .run(tenantA, Actor.module("vigil-diagnostics"), guardRequest("guard-2"));
    assertThat(run.state()).isEqualTo(OperationRunState.REFUSED);
    assertThat(run.error()).isEqualTo("operation fs_usage is not an active operation");
    UUID credential = Ids.newId();
    assertThat(
            app.context()
                .getBean(TargetConnector.class)
                .run(tenantA, guardInstance(), credential, "true", Duration.ofSeconds(1)))
        .isEqualTo(
            new TargetResult.NotStarted("Credential reference " + credential + " not found."));
  }

  /** A tenant of {@code InvestigationFixture}: claude at WireMock, an open incident. */
  private static Object[] investigationTenant(String name) {
    vigil.investigation.InvestigationFixture f =
        vigil.investigation.InvestigationFixture.create(
            app,
            name,
            name.toLowerCase(java.util.Locale.ROOT).replace(' ', '-') + ".guard.test",
            Map.of("claude-api", "http://127.0.0.1:" + wiremock.port()),
            java.util.function.UnaryOperator.identity(),
            vigil.setup.KnowledgeParts.demoPart());
    UUID incident =
        f.openIncident(
            List.<String[]>of(
                new String[] {"disk_full", "Disk full", "srv-db-01", "Disk full on srv-db-01"}),
            "srv-db-01");
    return new Object[] {f, incident};
  }

  private static vigil.knowledge.api.SearchRequest search(TenantId tenant) {
    return new vigil.knowledge.api.SearchRequest(
        tenant, Ids.newId(), null, List.of("disk_full"), List.of(), "srv-db-01");
  }

  private static void claudeAnswer() {
    wiremock.stubFor(
        post(urlEqualTo("/v1/messages"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"summary\\\":"
                            + "\\\"s\\\"}\"}],\"stop_reason\":\"end_turn\","
                            + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}")));
  }

  @Test
  @DisplayName(
      "AR-Q-04 the knowledge and investigation entry points refuse to run in a transaction")
  void knowledgeAndInvestigationEntryPoints() {
    Object[] tenant = investigationTenant("Guard K1");
    vigil.investigation.InvestigationFixture f =
        (vigil.investigation.InvestigationFixture) tenant[0];
    UUID incident = (UUID) tenant[1];
    claudeAnswer();
    vigil.knowledge.api.KnowledgeService knowledge =
        app.context().getBean(vigil.knowledge.api.KnowledgeService.class);
    vigil.knowledge.api.KnowledgeImport knowledgeImport =
        app.context().getBean(vigil.knowledge.api.KnowledgeImport.class);
    vigil.investigation.api.InvestigationStepHandler step =
        app.context().getBean(vigil.investigation.api.InvestigationStepHandler.class);
    Map<String, Supplier<Object>> entryPoints = new LinkedHashMap<>();
    entryPoints.put("KnowledgeService.search", () -> knowledge.search(search(f.tenant)));
    entryPoints.put(
        "KnowledgeImport.importPart",
        () ->
            knowledgeImport.importPart(
                new TenantId(Ids.newId()),
                vigil.setup.KnowledgeParts.demoPart(),
                Instant.now(),
                Map.of()));
    entryPoints.put(
        "InvestigationStepHandler.start",
        () -> step.start(f.call(Ids.newId(), incident, Ids.newId(), "SL-T-1")));
    entryPoints.put(
        "InvestigationStepHandler.evaluate",
        () -> step.evaluate(f.call(Ids.newId(), incident, Ids.newId(), "SL-T-3")));
    vigil.testsupport.TestEmbedding embedding = vigil.testsupport.TestEmbedding.shared();
    for (Map.Entry<String, Supplier<Object>> entry : entryPoints.entrySet()) {
      wiremock.resetRequests();
      int before = embedding.requestCount();
      assertThatThrownBy(() -> transaction.execute(status -> entry.getValue().get()))
          .as(entry.getKey())
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("external call inside a transaction");
      wiremock.verify(0, anyRequestedFor(anyUrl()));
      assertThat(embedding.requestsSince(before)).as(entry.getKey()).isEmpty();
    }
  }

  @Test
  @DisplayName("AR-R-09 outside a transaction the knowledge and investigation calls are made")
  void knowledgeAndInvestigationOutsideTransaction() {
    Object[] tenant = investigationTenant("Guard K2");
    vigil.investigation.InvestigationFixture f =
        (vigil.investigation.InvestigationFixture) tenant[0];
    UUID incident = (UUID) tenant[1];
    claudeAnswer();
    vigil.testsupport.TestEmbedding embedding = vigil.testsupport.TestEmbedding.shared();
    int before = embedding.requestCount();
    assertThat(
            app.context()
                .getBean(vigil.knowledge.api.KnowledgeService.class)
                .search(search(f.tenant)))
        .isInstanceOf(vigil.knowledge.api.SearchResult.Searched.class);
    assertThat(embedding.requestsSince(before)).hasSize(1);
    vigil.investigation.api.InvestigationStepHandler step =
        app.context().getBean(vigil.investigation.api.InvestigationStepHandler.class);
    wiremock.resetRequests();
    step.start(f.call(Ids.newId(), incident, Ids.newId(), "SL-T-1"));
    wiremock.verify(1, postRequestedFor(urlEqualTo("/v1/messages")));
    before = embedding.requestCount();
    step.evaluate(f.call(Ids.newId(), incident, Ids.newId(), "SL-T-3"));
    assertThat(embedding.requestsSince(before)).hasSize(1);
    vigil.knowledge.KnowledgeFixture second =
        vigil.knowledge.KnowledgeFixture.create(
            app,
            "Guard K3",
            "guard-k3.guard.test",
            Map.of(),
            java.util.function.UnaryOperator.identity(),
            null);
    before = embedding.requestCount();
    app.context()
        .getBean(vigil.knowledge.api.KnowledgeImport.class)
        .importPart(second.tenant, vigil.setup.KnowledgeParts.demoPart(), Instant.now(), Map.of());
    assertThat(embedding.requestsSince(before)).hasSize(1);
  }
}
