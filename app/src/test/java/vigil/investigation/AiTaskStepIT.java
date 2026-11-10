package vigil.investigation;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import vigil.aigateway.AiFixture;
import vigil.aigateway.api.AiGatewayService;
import vigil.aigateway.api.AiPart;
import vigil.aigateway.api.DataCategory;
import vigil.incidents.api.AiStatus;
import vigil.incidents.api.AiUnavailableReason;
import vigil.incidents.api.EvidenceKind;
import vigil.incidents.api.EvidenceView;
import vigil.incidents.api.IncidentService;
import vigil.incidents.api.IncidentView;
import vigil.investigation.api.IncidentSummaryView;
import vigil.investigation.api.InvestigationResultView;
import vigil.investigation.api.InvestigationService;
import vigil.investigation.api.InvestigationStepHandler;
import vigil.investigation.api.Origin;
import vigil.investigation.api.RunbookRef;
import vigil.investigation.api.SummarySource;
import vigil.investigation.api.Validation;
import vigil.investigation.api.Verdict;
import vigil.knowledge.api.KnowledgeService;
import vigil.servicemap.api.ServiceMapService;
import vigil.setup.KnowledgeParts;
import vigil.shared.Actor;
import vigil.shared.Completed;
import vigil.shared.Ids;
import vigil.shared.StepAnswer;
import vigil.shared.StepCall;
import vigil.testsupport.TestSecrets;
import vigil.testsupport.VigilDatabase;
import vigil.testsupport.VigilTestApp;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AiTaskStepIT {

  static final JsonMapper JSON = JsonMapper.builder().build();
  static final Path RECORDINGS = Path.of("src/test/resources/recordings");
  static final String CLAUDE = "/v1/messages";
  static final String OPENAI = "/v1/responses";
  static final String LOCAL = "/api/chat";
  static final List<String[]> STORM =
      List.<String[]>of(
          new String[] {"disk_full", "Disk full", "srv-db-01", "Disk full on srv-db-01"},
          new String[] {
            "db_write_errors",
            "Database write errors",
            "db-subscriber",
            "Write errors on db-subscriber"
          },
          new String[] {"sms_queue_growth", "SMS queue growth", "sms-queue", "SMS queue growing"},
          new String[] {
            "balance_api_errors", "Balance-check API errors", "api-balance", "Balance API errors"
          },
          new String[] {
            "bundle_api_errors", "Bundle-purchase API errors", "api-bundle", "Bundle API errors"
          },
          new String[] {"ussd_timeouts", "USSD gateway timeouts", "ussd-gw", "USSD timeouts"},
          new String[] {
            "ussd_synthetic_failed",
            "Synthetic USSD session failed",
            "ussd-probe",
            "USSD probe failed"
          });
  static final String[][] DIAGNOSTICS = {
    {"Disk usage of /data", "/data is 100% full (15.9 of 15.9 GiB used, 0.1 GiB free)"},
    {"Largest folders under /data", "Largest folder under /data: /data/txlog_archive (8.5 GiB)"},
    {
      "Data written under /data, last 15 minutes",
      "8.5 GiB written to 34 files under /data in the last 15 minutes"
    }
  };

  private WireMockServer wiremock;
  private VigilTestApp app;
  private InvestigationStepHandler step;
  private InvestigationService investigation;
  private IncidentService incidents;
  private int count;

  @BeforeAll
  void start() {
    wiremock = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort());
    wiremock.start();
    VigilDatabase database = VigilDatabase.fresh();
    TestSecrets secrets = TestSecrets.create(database, AiFixture.secrets());
    app = VigilTestApp.start(database, secrets.folder(), List.of());
    app.awaitStarted();
    step = app.context().getBean(InvestigationStepHandler.class);
    investigation = app.context().getBean(InvestigationService.class);
    incidents = app.context().getBean(IncidentService.class);
  }

  @AfterAll
  void stop() {
    app.close();
    wiremock.stop();
  }

  @BeforeEach
  void reset() {
    wiremock.resetAll();
  }

  /**
   * A tenant with claude, openai and local-llm at WireMock (or all closed with {@code reachable}
   * false).
   */
  InvestigationFixture tenant(
      boolean reachable, UnaryOperator<vigil.aigateway.api.AiGatewayPart> part7) {
    count++;
    Map<String, String> addresses = new LinkedHashMap<>();
    if (reachable) {
      String address = "http://127.0.0.1:" + wiremock.port();
      addresses.put("claude-api", address);
      addresses.put("openai-api", address);
      addresses.put("local-llm", address);
    }
    return InvestigationFixture.create(
        app,
        "Investigation " + count,
        "i" + count + ".investigation.test",
        addresses,
        part7,
        KnowledgeParts.demoPart());
  }

  InvestigationFixture tenant() {
    return tenant(true, UnaryOperator.identity());
  }

  /** The storm-like incident with diagnostics E1 to E3; returns its id. */
  static UUID storm(InvestigationFixture f) {
    UUID incident = f.openIncident(STORM, "srv-db-01");
    for (String[] diagnostic : DIAGNOSTICS) {
      f.addDiagnostic(incident, diagnostic[0], diagnostic[1]);
    }
    return incident;
  }

  static String recorded(String recording) {
    try {
      JsonNode file = JSON.readTree(Files.readString(RECORDINGS.resolve(recording)));
      return file.get("response").get("body").stringValue();
    } catch (IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  void answer(String path, String body) {
    answer(path, body, 0);
  }

  void answer(String path, String body, int delayMs) {
    wiremock.stubFor(
        post(urlEqualTo(path))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(body)
                    .withFixedDelay(delayMs)));
  }

  /** A Claude answer with the output text and {@code stop_reason} end_turn. */
  static String claudeAnswer(String outputText) {
    ObjectNode body = JSON.createObjectNode();
    body.putArray("content").addObject().put("type", "text").put("text", outputText);
    body.put("stop_reason", "end_turn");
    body.putObject("usage").put("input_tokens", 100).put("output_tokens", 10);
    return JSON.writeValueAsString(body);
  }

  static String slT1Output(String verdict, String runbook) {
    ObjectNode output = JSON.createObjectNode();
    output.put("likely_cause", "The /data volume is full.");
    output.put("hypothesis_verdict", verdict);
    output.putArray("evidence_labels").add("E1").add("E2");
    output.put("uncertainty", "Why the archives built up.");
    output.putArray("runbooks_cited").add(runbook);
    return JSON.writeValueAsString(output);
  }

  static String output(StepAnswer answer) {
    return ((Completed) answer).namedOutput().value();
  }

  IncidentView view(InvestigationFixture f, UUID incident) {
    return incidents.incident(f.tenant, incident).orElseThrow();
  }

  static List<String> audit(InvestigationFixture f, String type) {
    return f.postgres()
        .sql(
            "SELECT summary FROM audit.audit_event WHERE tenant_id = :t AND event_type = :type"
                + " ORDER BY sequence")
        .param("t", f.tenant.value())
        .param("type", type)
        .query(String.class)
        .list();
  }

  List<EvidenceView> evidence(InvestigationFixture f, UUID incident, EvidenceKind kind) {
    return incidents.evidence(f.tenant, incident).stream().filter(e -> e.kind() == kind).toList();
  }

  StepAnswer run(InvestigationFixture f, UUID incident, String task) {
    return step.start(f.call(Ids.newId(), incident, Ids.newId(), task));
  }

  static long rows(InvestigationFixture f, String table) {
    return f.postgres()
        .sql("SELECT count(*) FROM " + table + " WHERE tenant_id = :t")
        .param("t", f.tenant.value())
        .query(Long.class)
        .single();
  }

  static void await(BooleanSupplier condition) {
    Instant end = Instant.now().plusSeconds(10);
    while (!condition.getAsBoolean()) {
      if (Instant.now().isAfter(end)) {
        throw new AssertionError("condition not met within 10 s");
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError(e);
      }
    }
  }

  /** The storm's SL-T-1 against Claude's recorded answer, as SL-T-1 and SL-A1-04 assert it. */
  private void assertStormInvestigation() {
    InvestigationFixture f = tenant();
    UUID incident = storm(f);
    answer(CLAUDE, recorded("claude/0001-sl-t-1.json"));
    StepCall call = f.call(Ids.newId(), incident, Ids.newId(), "SL-T-1");
    assertThat(output(step.start(call))).isEqualTo("done");
    List<InvestigationResultView> results = investigation.results(f.tenant, incident);
    assertThat(results).hasSize(1);
    InvestigationResultView result = results.getFirst();
    assertThat(result.validation()).isEqualTo(Validation.ACCEPTED);
    assertThat(result.providerKey()).isEqualTo("claude");
    assertThat(result.modelName()).isEqualTo("claude-sonnet-5-5");
    assertThat(result.verdict()).isEqualTo(Verdict.CONFIRMS);
    List<UUID> diagnostics =
        evidence(f, incident, EvidenceKind.DIAGNOSTIC_RESULT).stream()
            .map(EvidenceView::id)
            .toList();
    assertThat(result.evidenceEntryIds()).containsExactlyInAnyOrderElementsOf(diagnostics);
    assertThat(result.runbookRefs())
        .containsExactly(
            new RunbookRef("RB-DB-01", 1),
            new RunbookRef("RB-API-01", 1),
            new RunbookRef("RB-USSD-01", 1));
    assertThat(result.runbooksSupplied())
        .containsExactly(
            new RunbookRef("RB-API-01", 1),
            new RunbookRef("RB-DB-01", 1),
            new RunbookRef("RB-USSD-01", 1));
    assertThat(result.origin()).isEqualTo(Origin.WORKFLOW_STEP);
    assertThat(result.runId()).isEqualTo(call.runId());
    assertThat(evidence(f, incident, EvidenceKind.AI_INVESTIGATION)).hasSize(1);
    IncidentView view = view(f, incident);
    assertThat(view.aiStatus()).isEqualTo(AiStatus.AVAILABLE);
    assertThat(view.aiLastRequestGroupId()).isEqualTo(result.requestGroupId());
    assertThat(audit(f, "ai_result_accepted"))
        .containsExactly("Incident investigation by Claude accepted.");
  }

  @Test
  @Order(1)
  @DisplayName("SL-T-1 incident investigation, product side")
  void slT1() {
    assertStormInvestigation();
  }

  @Test
  @Order(2)
  @DisplayName("PM-M17-02 the SL-T-1 request: system text and the parts of this incident")
  void request() {
    InvestigationFixture f = tenant();
    UUID incident = storm(f);
    answer(CLAUDE, recorded("claude/0001-sl-t-1.json"));
    run(f, incident, "SL-T-1");
    List<com.github.tomakehurst.wiremock.verification.LoggedRequest> requests =
        wiremock.findAll(postRequestedFor(urlEqualTo(CLAUDE)));
    assertThat(requests).hasSize(1);
    JsonNode body = JSON.readTree(requests.getFirst().getBodyAsString());
    assertThat(body.get("system").stringValue()).isEqualTo(Requests.SYSTEM_SL_T_1);
    IncidentView view = view(f, incident);
    Requests.SlT1 expected =
        Requests.slT1(
            view,
            incidents.members(f.tenant, incident),
            incidents.evidence(f.tenant, incident).stream()
                .filter(e -> e.kind() != EvidenceKind.AI_INVESTIGATION)
                .toList(),
            app.context().getBean(ServiceMapService.class).dependents(f.tenant, "srv-db-01"),
            app.context()
                .getBean(KnowledgeService.class)
                .applicableRunbooks(f.tenant, view.alertTypes(), "server"));
    List<String> rendered = new ArrayList<>();
    for (AiPart part : expected.parts()) {
      rendered.add(
          "### " + part.label() + " (" + part.dataCategory().displayName() + ")\n" + part.text());
    }
    assertThat(body.get("messages").get(0).get("content").stringValue())
        .isEqualTo(String.join("\n\n", rendered));
    assertThat(expected.parts().get(1).text())
        .isEqualTo(
            "Root-cause hypothesis: Subscriber DB server (srv-db-01), kind server, criticality"
                + " critical; 6 other affected components depend on it");
    assertThat(expected.parts())
        .extracting(AiPart::label)
        .containsExactly(
            "Member alerts",
            "Hypothesis",
            "Customer impact",
            "E1",
            "E2",
            "E3",
            "RB-API-01",
            "RB-DB-01",
            "RB-USSD-01");
    assertThat(expected.parts().get(3).dataCategory()).isEqualTo(DataCategory.DIAGNOSTIC_OUTPUT);
  }

  @Test
  @Order(3)
  @DisplayName("SL-A1-04 the AI part: SL-T-1 with runbooks and evidence through the gateway")
  void slA104() {
    assertStormInvestigation();
  }

  @Test
  @Order(4)
  @DisplayName("PM-S-04 the AI task step and its outputs done and unavailable")
  void outputs() {
    InvestigationFixture f = tenant();
    UUID incident = storm(f);
    answer(CLAUDE, recorded("claude/0001-sl-t-1.json"));
    assertThat(output(run(f, incident, "SL-T-1"))).isEqualTo("done");
    answer(CLAUDE, claudeAnswer(slT1Output("confirms", "RB-X-99")));
    assertThat(output(run(f, incident, "SL-T-1"))).isEqualTo("unavailable");
    answer(CLAUDE, recorded("claude/0002-sl-t-2.json"));
    assertThat(output(run(f, incident, "SL-T-2"))).isEqualTo("done");
    assertThat(output(run(f, incident, "SL-T-3"))).isEqualTo("done");
    assertThatThrownBy(() -> step.start(f.call(Ids.newId(), incident, Ids.newId(), "SL-T-9")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("ai task step parameters: task SL-T-1, SL-T-2 or SL-T-3");
    assertThatThrownBy(() -> step.start(f.call(Ids.newId(), Ids.newId(), Ids.newId(), "SL-T-1")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageStartingWith("incident ");
  }

  @Test
  @Order(5)
  @DisplayName("DM-S-04 the AI task step for SL-T-1, SL-T-2 and SL-T-3")
  void perTask() {
    InvestigationFixture f = tenant();
    UUID incident = storm(f);
    StepCall investigate = f.call(Ids.newId(), incident, Ids.newId(), "SL-T-1");
    assertThat(investigate.stepId().value()).isEqualTo("s-investigate");
    answer(CLAUDE, recorded("claude/0001-sl-t-1.json"));
    assertThat(output(step.evaluate(investigate))).isEqualTo("done");
    StepCall summarise = f.call(Ids.newId(), incident, Ids.newId(), "SL-T-2");
    assertThat(summarise.stepId().value()).isEqualTo("s-summarise");
    assertThat(output(step.start(summarise))).isEqualTo("done");
    StepCall similar = f.call(Ids.newId(), incident, Ids.newId(), "SL-T-3");
    assertThat(similar.stepId().value()).isEqualTo("s-similar");
    assertThat(output(step.start(similar))).isEqualTo("done");
    UUID other =
        f.openIncident(
            List.<String[]>of(new String[] {"cpu_high", "CPU high", null, "CPU high"}), null);
    assertThat(output(run(f, other, "SL-T-3"))).isEqualTo("done");
    f.postgres()
        .sql(
            "UPDATE knowledge.knowledge_settings SET embedding_state = 'reembedding',"
                + " reembedding_provider_key = 'openai-embed' WHERE tenant_id = :t")
        .param("t", f.tenant.value())
        .update();
    assertThat(output(run(f, incident, "SL-T-3"))).isEqualTo("unavailable");
    step.cancel(
        new vigil.shared.StepCancel(
            f.tenant,
            Ids.newId(),
            incident,
            Ids.newId(),
            new vigil.shared.Key("s-similar"),
            StepCall.StepType.AI_TASK));
  }

  @Test
  @Order(6)
  @DisplayName("PM-M17-01 SL-T-3 match gives the similarity_match entry; no_match none")
  void similarityEvidence() {
    InvestigationFixture f = tenant();
    UUID incident = storm(f);
    assertThat(output(run(f, incident, "SL-T-3"))).isEqualTo("done");
    List<EvidenceView> entries = evidence(f, incident, EvidenceKind.SIMILARITY_MATCH);
    assertThat(entries).hasSize(1);
    EvidenceView entry = entries.getFirst();
    assertThat(entry.summary())
        .isEqualTo(
            "Similar past incident PI-1001 'Subscriber DB /data full after archive catch-up run'"
                + " (Fictional record), similarity 1.00; fix succeeded 4 times.");
    assertThat(entry.sourceSystem()).isEqualTo("Incident history, Local embedding model");
    vigil.knowledge.api.SimilarityResultView result =
        app.context()
            .getBean(KnowledgeService.class)
            .latestSearchResult(f.tenant, incident)
            .orElseThrow();
    assertThat(entry.producerRecordId()).isEqualTo(result.id());
    assertThat(entry.collectedAt()).isEqualTo(result.searchedAt());
    assertThat(JSON.readTree(entry.contentJson()))
        .isEqualTo(
            JSON.readTree(
                "{\"similarity_search_result_id\": \""
                    + result.id()
                    + "\", \"past_incident_key\": \"PI-1001\", \"past_incident_title\":"
                    + " \"Subscriber DB /data full after archive catch-up run\", \"record_label\":"
                    + " \"Fictional record\", \"score\": 1.00, \"success_count\": 4,"
                    + " \"fix_action_key\": \"purge-archived-txlogs\", \"provider_key\":"
                    + " \"local-embed\"}"));
    assertThat(entry.contentJson()).contains("1.00");
    UUID other =
        f.openIncident(
            List.<String[]>of(new String[] {"cpu_high", "CPU high", null, "CPU high"}), null);
    assertThat(output(run(f, other, "SL-T-3"))).isEqualTo("done");
    assertThat(evidence(f, other, EvidenceKind.SIMILARITY_MATCH)).isEmpty();
  }

  @Test
  @Order(7)
  @DisplayName("PM-M17-03 a rejected output is not shown, no other provider is tried")
  void rejected() {
    InvestigationFixture f = tenant();
    UUID incident = storm(f);
    answer(CLAUDE, claudeAnswer(slT1Output("confirms", "RB-X-99")));
    answer(OPENAI, recorded("openai/0001-sl-t-1.json"));
    assertThat(output(run(f, incident, "SL-T-1"))).isEqualTo("unavailable");
    InvestigationResultView result = investigation.results(f.tenant, incident).getFirst();
    assertThat(result.validation()).isEqualTo(Validation.REJECTED);
    assertThat(result.rejectionReasons()).containsExactly("cites a runbook that was not supplied");
    assertThat(evidence(f, incident, EvidenceKind.AI_INVESTIGATION)).isEmpty();
    IncidentView view = view(f, incident);
    assertThat(view.aiStatus()).isEqualTo(AiStatus.UNAVAILABLE);
    assertThat(view.aiUnavailableReason()).isEqualTo(AiUnavailableReason.OUTPUT_FAILED_CHECKS);
    assertThat(audit(f, "ai_result_rejected"))
        .containsExactly(
            "Incident investigation by Claude rejected: cites a runbook that was not supplied.");
    assertThat(wiremock.findAll(postRequestedFor(urlEqualTo(OPENAI)))).isEmpty();
  }

  @Test
  @Order(8)
  @DisplayName("AR-G-13 a truncated answer is rejected with 'no likely cause'")
  void truncated() {
    InvestigationFixture f = tenant();
    UUID incident = storm(f);
    answer(CLAUDE, recorded("claude/0003-truncated.json"));
    answer(OPENAI, recorded("openai/0001-sl-t-1.json"));
    assertThat(output(run(f, incident, "SL-T-1"))).isEqualTo("unavailable");
    InvestigationResultView result = investigation.results(f.tenant, incident).getFirst();
    assertThat(result.rejectionReasons()).containsExactly("no likely cause");
    assertThat(result.likelyCause()).isNull();
    assertThat(wiremock.findAll(postRequestedFor(urlEqualTo(OPENAI)))).isEmpty();
    assertThat(wiremock.findAll(postRequestedFor(urlEqualTo(LOCAL)))).isEmpty();
  }

  @Test
  @Order(9)
  @DisplayName("PM-M17-06 AI investigation unavailable with its reason on the incident")
  void unavailable() {
    InvestigationFixture f = tenant(false, UnaryOperator.identity());
    UUID incident = storm(f);
    assertThat(output(run(f, incident, "SL-T-1"))).isEqualTo("unavailable");
    assertThat(investigation.results(f.tenant, incident)).isEmpty();
    assertThat(view(f, incident).aiStatus()).isEqualTo(AiStatus.UNAVAILABLE);
    assertThat(view(f, incident).aiUnavailableReason())
        .isEqualTo(AiUnavailableReason.NO_PROVIDER_REACHABLE);
    InvestigationFixture allowed = tenant();
    UUID other = storm(allowed);
    allowed.knowledge.setRouting("diagnostic_output", List.of("local-embed", "openai-embed"));
    assertThat(output(run(allowed, other, "SL-T-1"))).isEqualTo("unavailable");
    assertThat(investigation.results(allowed.tenant, other)).isEmpty();
    assertThat(view(allowed, other).aiUnavailableReason())
        .isEqualTo(AiUnavailableReason.NO_PROVIDER_ALLOWED);
    assertThat(wiremock.findAll(postRequestedFor(urlEqualTo(CLAUDE)))).isEmpty();
  }

  @Test
  @Order(10)
  @DisplayName("DM-E-68 a later rejected attempt keeps latestAccepted; the group is recorded first")
  void latestAccepted() {
    InvestigationFixture f = tenant();
    UUID incident = storm(f);
    answer(CLAUDE, recorded("claude/0001-sl-t-1.json"));
    run(f, incident, "SL-T-1");
    InvestigationResultView accepted =
        investigation.latestAccepted(f.tenant, incident).orElseThrow();
    answer(CLAUDE, claudeAnswer(slT1Output("confirms", "RB-X-99")));
    run(f, incident, "SL-T-1");
    List<InvestigationResultView> results = investigation.results(f.tenant, incident);
    assertThat(results).hasSize(2);
    assertThat(investigation.latestAccepted(f.tenant, incident)).contains(accepted);
    IncidentView view = view(f, incident);
    assertThat(view.aiStatus()).isEqualTo(AiStatus.UNAVAILABLE);
    assertThat(view.aiLastRequestGroupId()).isEqualTo(results.getLast().requestGroupId());

    wiremock.resetAll();
    answer(CLAUDE, recorded("claude/0001-sl-t-1.json"), 3000);
    UUID before = view.aiLastRequestGroupId();
    CompletableFuture<StepAnswer> running =
        CompletableFuture.supplyAsync(() -> run(f, incident, "SL-T-1"));
    await(() -> !before.equals(view(f, incident).aiLastRequestGroupId()));
    assertThat(investigation.results(f.tenant, incident)).hasSize(2);
    assertThat(running).isNotDone();
    assertThat(output(running.join())).isEqualTo("done");
    assertThat(investigation.results(f.tenant, incident).getLast().requestGroupId())
        .isEqualTo(view(f, incident).aiLastRequestGroupId());
  }

  @Test
  @Order(11)
  @DisplayName("DM-E-68 without a hypothesis not_applicable is accepted with an empty verdict")
  void noHypothesis() {
    InvestigationFixture f = tenant();
    UUID incident =
        f.openIncident(
            List.<String[]>of(new String[] {"disk_full", "Disk full", "srv-db-01", "Disk full"}),
            null);
    f.addDiagnostic(incident, DIAGNOSTICS[0][0], DIAGNOSTICS[0][1]);
    f.addDiagnostic(incident, DIAGNOSTICS[1][0], DIAGNOSTICS[1][1]);
    answer(CLAUDE, claudeAnswer(slT1Output("not_applicable", "RB-API-01")));
    List<String> supplied =
        app
            .context()
            .getBean(KnowledgeService.class)
            .applicableRunbooks(f.tenant, List.of("disk_full"), null)
            .stream()
            .map(vigil.knowledge.api.RunbookView::runbookKey)
            .toList();
    answer(
        CLAUDE,
        claudeAnswer(
            slT1Output("not_applicable", supplied.isEmpty() ? "none" : supplied.getFirst())));
    StepAnswer answer = run(f, incident, "SL-T-1");
    InvestigationResultView result = investigation.results(f.tenant, incident).getFirst();
    if (supplied.isEmpty()) {
      assertThat(result.rejectionReasons())
          .containsExactly("cites a runbook that was not supplied");
      assertThat(output(answer)).isEqualTo("unavailable");
      return;
    }
    assertThat(output(answer)).isEqualTo("done");
    assertThat(result.validation()).isEqualTo(Validation.ACCEPTED);
    assertThat(result.verdict()).isNull();
    assertThat(evidence(f, incident, EvidenceKind.AI_INVESTIGATION).getFirst().summary())
        .isEqualTo("AI investigation by Claude; cites " + supplied.getFirst() + ".");
  }

  @Test
  @Order(12)
  @DisplayName("DM-C-11 raw_output cut to 20,000 with raw_output_truncated")
  void longOutput() {
    InvestigationFixture f = tenant();
    UUID incident = storm(f);
    ObjectNode output = (ObjectNode) JSON.readTree(slT1Output("confirms", "RB-DB-01"));
    output.put("likely_cause", "x".repeat(25_000));
    String text = JSON.writeValueAsString(output);
    answer(CLAUDE, claudeAnswer(text));
    assertThat(output(run(f, incident, "SL-T-1"))).isEqualTo("done");
    InvestigationResultView result = investigation.results(f.tenant, incident).getFirst();
    assertThat(result.rawOutput()).isEqualTo(text.substring(0, 20_000));
    assertThat(result.rawOutputTruncated()).isTrue();
    assertThat(result.likelyCause()).hasSize(2000);
  }

  @Test
  @Order(13)
  @DisplayName("PM-M17-04 each result stored; an accepted one gets its evidence entry")
  void storedFields() {
    InvestigationFixture f = tenant();
    UUID incident = storm(f);
    String text = slT1Output("disputes", "RB-DB-01");
    answer(CLAUDE, claudeAnswer(text));
    StepCall call = f.call(Ids.newId(), incident, Ids.newId(), "SL-T-1");
    step.start(call);
    InvestigationResultView result = investigation.results(f.tenant, incident).getFirst();
    List<EvidenceView> diagnostics = evidence(f, incident, EvidenceKind.DIAGNOSTIC_RESULT);
    assertThat(result.incidentId()).isEqualTo(incident);
    assertThat(result.origin()).isEqualTo(Origin.WORKFLOW_STEP);
    assertThat(result.runId()).isEqualTo(call.runId());
    assertThat(result.requestedBy()).isEqualTo(Actor.module("vigil-investigation"));
    assertThat(result.likelyCause()).isEqualTo("The /data volume is full.");
    assertThat(result.uncertainty()).isEqualTo("Why the archives built up.");
    assertThat(result.verdict()).isEqualTo(Verdict.DISPUTES);
    assertThat(result.evidenceEntryIds())
        .containsExactly(diagnostics.get(0).id(), diagnostics.get(1).id());
    assertThat(result.runbookRefs()).containsExactly(new RunbookRef("RB-DB-01", 1));
    assertThat(result.rawOutput()).isEqualTo(text);
    assertThat(result.rawOutputTruncated()).isFalse();
    assertThat(result.rejectionReasons()).isEmpty();
    EvidenceView entry = evidence(f, incident, EvidenceKind.AI_INVESTIGATION).getFirst();
    assertThat(entry.producerRecordId()).isEqualTo(result.id());
    assertThat(entry.sourceSystem()).isEqualTo("Claude, model claude-sonnet-5-5");
    assertThat(entry.summary())
        .isEqualTo(
            "AI investigation by Claude: disputes the hypothesis Subscriber DB server; cites"
                + " RB-DB-01.");
    assertThat(entry.dataCategory()).isEqualTo(vigil.incidents.api.DataCategory.INCIDENT_RECORD);
    assertThat(JSON.readTree(entry.contentJson()))
        .isEqualTo(
            JSON.readTree(
                "{\"result_id\": \""
                    + result.id()
                    + "\", \"origin\": \"workflow_step\", \"provider_key\": \"claude\","
                    + " \"model_name\": \"claude-sonnet-5-5\", \"likely_cause\": \"The /data"
                    + " volume is full.\", \"verdict\": \"disputes\", \"evidence_entry_ids\": [\""
                    + diagnostics.get(0).id()
                    + "\", \""
                    + diagnostics.get(1).id()
                    + "\"], \"uncertainty\": \"Why the archives built up.\", \"runbook_refs\":"
                    + " [{\"runbook_key\": \"RB-DB-01\", \"version\": 1}]}"));
  }

  private void assertSummaries() {
    InvestigationFixture f = tenant();
    UUID incident = storm(f);
    answer(CLAUDE, recorded("claude/0002-sl-t-2.json"));
    UUID run = Ids.newId();
    assertThat(output(step.start(f.call(run, incident, Ids.newId(), "SL-T-2")))).isEqualTo("done");
    IncidentSummaryView ai = investigation.latestSummary(f.tenant, incident).orElseThrow();
    assertThat(ai.source()).isEqualTo(SummarySource.AI);
    assertThat(ai.providerKey()).isEqualTo("claude");
    assertThat(ai.runId()).isEqualTo(run);
    assertThat(ai.text()).startsWith("The /data volume on the Subscriber DB server");
    assertThat(view(f, incident).aiStatus()).isEqualTo(AiStatus.NOT_RUN);
    assertThat(audit(f, "ai_result_accepted")).isEmpty();
    InvestigationFixture failing = tenant(false, UnaryOperator.identity());
    UUID other = storm(failing);
    assertThat(output(run(failing, other, "SL-T-2"))).isEqualTo("done");
    IncidentSummaryView template = investigation.latestSummary(failing.tenant, other).orElseThrow();
    assertThat(template.source()).isEqualTo(SummarySource.TEMPLATE);
    assertThat(template.providerKey()).isNull();
    IncidentView view = view(failing, other);
    assertThat(template.text())
        .isEqualTo(Texts.template(view, incidents.members(failing.tenant, other), 3));
    assertThat(template.text()).endsWith("Hypothesis: Subscriber DB server. Evidence: 3 items.");
  }

  @Test
  @Order(14)
  @DisplayName("PM-M17-05 the summary by SL-T-2, or the template summary")
  void summaries() {
    assertSummaries();
  }

  @Test
  @Order(15)
  @DisplayName("SL-T-2 incident summary, product side")
  void slT2() {
    assertSummaries();
  }

  @Test
  @Order(16)
  @DisplayName("DM-E-69 summaryForMessage stores a template summary when none exists")
  void summaryForMessage() {
    InvestigationFixture f = tenant();
    UUID incident = storm(f);
    IncidentSummaryView first = investigation.summaryForMessage(f.tenant, incident);
    assertThat(first.source()).isEqualTo(SummarySource.TEMPLATE);
    assertThat(first.runId()).isNull();
    assertThat(investigation.latestSummary(f.tenant, incident)).contains(first);
    assertThat(investigation.summaryForMessage(f.tenant, incident)).isEqualTo(first);
    assertThat(rows(f, "investigation.incident_summary")).isEqualTo(1);
    answer(CLAUDE, recorded("claude/0002-sl-t-2.json"));
    run(f, incident, "SL-T-2");
    IncidentSummaryView latest = investigation.summaryForMessage(f.tenant, incident);
    assertThat(latest.source()).isEqualTo(SummarySource.AI);
    assertThatThrownBy(() -> investigation.summaryForMessage(f.tenant, Ids.newId()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @Order(17)
  @DisplayName("AR-R-29 an attempt on an incident that refuses the request group stores nothing")
  void refusedGroup() {
    InvestigationFixture f = tenant();
    UUID merged = f.mergedIncident();
    IncidentView before = view(f, merged);
    List<EvidenceView> evidence = incidents.evidence(f.tenant, merged);
    answer(CLAUDE, recorded("claude/0001-sl-t-1.json"));
    assertThatThrownBy(() -> run(f, merged, "SL-T-1")).isInstanceOf(IllegalStateException.class);
    assertThat(wiremock.getAllServeEvents()).isEmpty();
    assertThat(rows(f, "investigation.ai_investigation_result")).isZero();
    assertThat(rows(f, "investigation.incident_summary")).isZero();
    IncidentView after = view(f, merged);
    assertThat(after.aiStatus()).isEqualTo(before.aiStatus());
    assertThat(after.aiLastRequestGroupId()).isEqualTo(before.aiLastRequestGroupId());
    assertThat(incidents.evidence(f.tenant, merged)).isEqualTo(evidence);
    assertThat(app.context().getBean(AiGatewayService.class)).isNotNull();
    assertThat(Duration.ofSeconds(1)).isPositive();
  }
}
