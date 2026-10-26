package vigil.workflows;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vigil.correlation.CorrelationFixture;
import vigil.engine.EngineFixture;
import vigil.engine.TestHandlers;
import vigil.engine.api.EndReason;
import vigil.engine.api.EngineService;
import vigil.engine.api.RunMode;
import vigil.engine.api.RunState;
import vigil.engine.api.RunView;
import vigil.engine.api.StepExecutionView;
import vigil.engine.api.WaitReason;
import vigil.incidents.api.IncidentCreated;
import vigil.incidents.api.IncidentService;
import vigil.incidents.api.IncidentState;
import vigil.incidents.api.IncidentStateChanged;
import vigil.incidents.api.Severity;
import vigil.intake.IntakeFixture;
import vigil.intake.api.AlertView;
import vigil.routing.api.EvaluationView;
import vigil.routing.api.RequestedBy;
import vigil.routing.api.RoutingService;
import vigil.shared.Ids;
import vigil.shared.StepCall.StepType;
import vigil.shared.TenantId;
import vigil.shared.Waiting;
import vigil.testsupport.DoubleDelivery;
import vigil.testsupport.MutableClock;
import vigil.testsupport.TestSecrets;
import vigil.testsupport.TestTenants;
import vigil.testsupport.VigilDatabase;
import vigil.testsupport.VigilTestApp;
import vigil.workflows.api.WorkflowImport;
import vigil.workflows.api.WorkflowService;
import vigil.workflows.api.WorkflowVersionView;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WorkflowRunIT {

  private static final MutableClock CLOCK = new MutableClock(Instant.parse("2026-10-24T10:00:00Z"));
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String HOST_A = "a.workflow-run.test";
  private static final String HOST_B = "b.workflow-run.test";
  private static final String ALERTNAME = "NodeFilesystemFull";

  /** The test clock. */
  static class TestBeans {

    @Bean
    @Primary
    Clock testClock() {
      return CLOCK;
    }
  }

  private WireMockServer wiremock;
  private JdbcClient postgres;
  private VigilTestApp app;
  private WorkflowFixture a;
  private WorkflowFixture b;
  private TenantId d;
  private TestHandlers handlers;
  private TransactionTemplate tx;
  private EngineService engine;
  private RoutingService routing;
  private IncidentService incidents;
  private WorkflowService workflows;

  @BeforeAll
  void start() {
    wiremock = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort());
    wiremock.start();
    IntakeFixture.stub(wiremock);
    VigilDatabase database = VigilDatabase.fresh();
    postgres = database.postgres();
    TestSecrets secrets = IntakeFixture.secrets(database, "", "-b");
    app =
        VigilTestApp.start(
            database, secrets.folder(), List.of(), TestBeans.class, TestHandlers.Beans.class);
    app.awaitStarted();
    String address = "http://127.0.0.1:" + wiremock.port();
    a = WorkflowFixture.create(app, secrets, "Tenant A", HOST_A, address, "", true);
    b = WorkflowFixture.create(app, secrets, "Tenant B", HOST_B, address, "-b", false);
    d = new TestTenants(app).tenant("Tenant D").create().id();
    app.context()
        .getBean(WorkflowImport.class)
        .importPart(d, WorkflowFixture.demoPart(), Instant.now(), Map.of());
    handlers = app.context().getBean(TestHandlers.class);
    tx = new TransactionTemplate(app.context().getBean(PlatformTransactionManager.class));
    engine = app.context().getBean(EngineService.class);
    routing = app.context().getBean(RoutingService.class);
    incidents = app.context().getBean(IncidentService.class);
    workflows = app.context().getBean(WorkflowService.class);
  }

  @AfterAll
  void stop() {
    app.close();
    wiremock.stop();
  }

  private WorkflowsIncidentListener listener() {
    return AopTestUtils.getUltimateTargetObject(
        app.context().getBean(WorkflowsIncidentListener.class));
  }

  private void deliver(Runnable listenerCall) {
    tx.executeWithoutResult(status -> listenerCall.run());
  }

  private static JsonNode tree(String json) {
    return JSON.readTree(json);
  }

  private UUID incident(WorkflowFixture fixture, AlertView alert) {
    fixture.engineFixture.correlation.awaitDecided();
    return incidents.incidentOfAlert(fixture.tenant, alert.id()).orElseThrow();
  }

  private RunView run(UUID run) {
    return a.engineFixture.run(run);
  }

  private RunView awaitState(UUID run, RunState state) {
    return a.engineFixture.awaitRunState(run, state);
  }

  private List<EvaluationView> workflowEvaluations(TenantId tenant, UUID incident) {
    return routing.evaluations(tenant, incident).stream()
        .filter(evaluation -> evaluation.requestedBy() == RequestedBy.WORKFLOWS)
        .toList();
  }

  /** The step id of the run's current step execution. */
  private String currentStep(UUID runId) {
    RunView view = run(runId);
    return a.engineFixture.executions(runId).stream()
        .filter(execution -> execution.id().equals(view.currentStepExecutionId()))
        .map(StepExecutionView::stepId)
        .findFirst()
        .orElse(null);
  }

  private boolean waitingAt(UUID runId, String stepId) {
    RunView view = run(runId);
    return view.state() == RunState.WAITING
        && view.waitReason() == WaitReason.STEP_WAITING
        && stepId.equals(currentStep(runId));
  }

  private boolean publicationCompleted(UUID incident) {
    return postgres
            .sql(
                "SELECT count(*) FROM platform.event_publication WHERE listener_id LIKE :listener"
                    + " AND event_type = :type AND serialized_event LIKE :incident"
                    + " AND completion_date IS NOT NULL")
            .param("listener", "%" + WorkflowsIncidentListener.class.getName() + "%")
            .param("type", IncidentCreated.class.getName())
            .param("incident", "%" + incident + "%")
            .query(Long.class)
            .single()
        == 1;
  }

  private UUID i1;
  private UUID i2;
  private UUID i4;
  private UUID r1;
  private UUID r2;
  private UUID r4;
  private UUID r5;

  @Test
  @Order(1)
  @DisplayName("PM-M11-07 an incident created: the routing evaluation, then a live run")
  void incidentCreated() {
    handlers.reset();
    AlertView s =
        a.engineFixture.correlation.postZabbix(
            "sms-queue", "lab-vas-01", "SMS queue growing on route r1");
    i1 = incident(a, s);
    r1 = a.awaitRun(i1, 1);
    WorkflowVersionView active = workflows.activeVersion(a.tenant).orElseThrow();
    assertThat(engine.runs(a.tenant, i1)).hasSize(1);
    RunView run = run(r1);
    assertThat(run.mode()).isEqualTo(RunMode.LIVE);
    assertThat(run.episode()).isEqualTo(1);
    assertThat(run.workflowVersionId()).isEqualTo(active.id());
    assertThat(active.number()).isEqualTo(1);
    assertThat(tree(run.contentJson())).isEqualTo(tree(active.contentJson()));
    assertThat(workflowEvaluations(a.tenant, i1))
        .singleElement()
        .satisfies(evaluation -> assertThat(evaluation.applied()).isTrue());
  }

  @Test
  @Order(2)
  @DisplayName("DM-R-16 runs only for forming or open incidents; a merged incident's run cancelled")
  void formingAndMerged() {
    EngineFixture.await(
        () ->
            run(r1).state() == RunState.WAITING
                && run(r1).waitReason() == WaitReason.INCIDENT_FORMING);
    CLOCK.advance(Duration.ofSeconds(10));
    AlertView bundle =
        a.engineFixture.correlation.postAlertmanager(
            "api-bundle", "api-bundle-1", "BundleApiErrors");
    i2 = incident(a, bundle);
    assertThat(i2).isNotEqualTo(i1);
    r2 = a.awaitRun(i2, 1);
    awaitState(r2, RunState.WAITING);
    CLOCK.advance(Duration.ofSeconds(5));
    a.engineFixture.correlation.postZabbix(
        "db-subscriber", "lab-db-01", "Database write errors on schema s1");
    a.engineFixture.correlation.awaitDecided();
    a.engineFixture.correlation.awaitState(i2, IncidentState.MERGED);
    RunView merged = awaitState(r2, RunState.CANCELLED);
    assertThat(merged.endReason()).isEqualTo(EndReason.INCIDENT_MERGED);
    CLOCK.advance(Duration.ofSeconds(40));
    a.engineFixture.correlation.awaitState(i1, IncidentState.OPEN);
    RunView finished = awaitState(r1, RunState.FINISHED);
    assertThat(finished.endReason()).isEqualTo(EndReason.PATH_ENDED);
    assertThat(a.engineFixture.steps(r1))
        .containsExactly(
            "s-trigger",
            "s-severity",
            "s-diagnose",
            "s-investigate",
            "s-summarise",
            "s-ticket",
            "s-notify",
            "s-wait-ack",
            "s-similar",
            "s-approve",
            "s-action",
            "s-recover",
            "s-resolve");
    assertThat(a.engineFixture.outputs(r1))
        .containsExactly(
            "next",
            "p1",
            "done",
            "done",
            "done",
            "done",
            "sent",
            "next",
            "done",
            "approved",
            "succeeded",
            "recovered",
            "done");
  }

  @Test
  @Order(3)
  @DisplayName("DM-R-22 a reopen starts a new live run of the active version")
  void reopen() {
    handlers.answer(StepType.RECOVERY_CHECK, call -> new Waiting(Set.of(), null, "{}"));
    CLOCK.advance(Duration.ofSeconds(400));
    AlertView s2 =
        a.engineFixture.correlation.postZabbix(
            "db-subscriber", "lab-db-01", "Database write errors on schema s2");
    i4 = incident(a, s2);
    assertThat(i4).isNotEqualTo(i1);
    r4 = a.awaitRun(i4, 1);
    awaitState(r4, RunState.WAITING);
    CLOCK.advance(Duration.ofSeconds(41));
    a.engineFixture.correlation.awaitState(i4, IncidentState.OPEN);
    assertThat(incidents.incident(a.tenant, i4).orElseThrow().severity()).isEqualTo(Severity.P1);
    EngineFixture.await(() -> waitingAt(r4, "s-recover"));
    a.engineFixture.correlation.resolve(i4);
    AlertView balance =
        a.engineFixture.correlation.postAlertmanager(
            "api-balance", "api-balance-1", "BalanceApiErrors");
    assertThat(incident(a, balance)).isEqualTo(i4);
    r5 = a.awaitRun(i4, 2);
    EngineFixture.await(() -> waitingAt(r5, "s-recover"));
    assertThat(incidents.incident(a.tenant, i4).orElseThrow().state())
        .isEqualTo(IncidentState.OPEN);
    assertThat(run(r5).episode()).isEqualTo(2);
    RunView cancelled = run(r4);
    assertThat(cancelled.state()).isEqualTo(RunState.CANCELLED);
    assertThat(cancelled.endReason()).isEqualTo(EndReason.INCIDENT_REOPENED);
    assertThat(workflowEvaluations(a.tenant, i4)).hasSize(1);
  }

  @Test
  @Order(4)
  @DisplayName("SL-B-03 the runs of the demo workflow run on its version's content")
  void versionContent() {
    WorkflowVersionView active = workflows.activeVersion(a.tenant).orElseThrow();
    assertThat(tree(run(r1).contentJson())).isEqualTo(tree(active.contentJson()));
    assertThat(tree(run(r5).contentJson())).isEqualTo(tree(active.contentJson()));
    assertThat(run(r1).workflowVersionId()).isEqualTo(active.id());
    assertThat(run(r5).workflowVersionId()).isEqualTo(active.id());
    assertThat(run(r1).state()).isEqualTo(RunState.FINISHED);
  }

  /** What the repeated deliveries of the DM-E50-R1 check left: runs and evaluations. */
  private record Recorded(List<RunView> runs, List<EvaluationView> evaluations) {}

  private Recorded recorded(UUID incident) {
    return new Recorded(engine.runs(a.tenant, incident), routing.evaluations(a.tenant, incident));
  }

  private Recorded createdBefore;
  private Recorded createdAfter;
  private Recorded reopenBefore;
  private Recorded reopenAfter;
  private Recorded resolvedBefore;
  private Recorded resolvedAfter;

  @Test
  @Order(5)
  @DisplayName("DM-E50-R1 a repeated incident event starts no second run")
  void repeatedEvents() {
    WorkflowsIncidentListener listener = listener();
    createdBefore = recorded(i1);
    deliver(() -> listener.onCreated(new IncidentCreated(a.tenant, i1)));
    createdAfter = recorded(i1);
    assertThat(createdAfter).isEqualTo(createdBefore);
    IncidentStateChanged reopened =
        new IncidentStateChanged(a.tenant, i4, IncidentState.RESOLVED, IncidentState.OPEN);
    reopenBefore = recorded(i4);
    deliver(() -> listener.onStateChanged(reopened));
    reopenAfter = recorded(i4);
    assertThat(reopenAfter.runs()).hasSize(2);
    assertThat(reopenAfter).isEqualTo(reopenBefore);
    a.engineFixture.correlation.resolve(i4);
    resolvedBefore = recorded(i4);
    deliver(() -> listener.onStateChanged(reopened));
    resolvedAfter = recorded(i4);
    assertThat(incidents.incident(a.tenant, i4).orElseThrow().state())
        .isEqualTo(IncidentState.RESOLVED);
    assertThat(resolvedAfter.runs().stream().map(RunView::id).toList())
        .isEqualTo(resolvedBefore.runs().stream().map(RunView::id).toList());
    assertThat(resolvedAfter.evaluations()).isEqualTo(resolvedBefore.evaluations());
  }

  @Test
  @Order(6)
  @DisplayName("DM-R-66 the workflows listener gives the same result when an event comes twice")
  void sameResult() {
    assertThat(createdAfter.runs()).hasSize(1);
    assertThat(createdAfter.runs().getFirst().id()).isEqualTo(r1);
    assertThat(createdAfter).isEqualTo(createdBefore);
    assertThat(reopenAfter.runs().stream().map(RunView::id).toList()).containsExactly(r4, r5);
    assertThat(reopenAfter).isEqualTo(reopenBefore);
    assertThat(resolvedAfter.runs().stream().map(RunView::id).toList()).containsExactly(r4, r5);
    assertThat(resolvedAfter.evaluations()).isEqualTo(resolvedBefore.evaluations());
  }

  private UUID i6;
  private UUID i7;

  @Test
  @Order(7)
  @DisplayName("AR-Q-05 WorkflowsIncidentListener: incident created delivered twice")
  void doubleDelivery() {
    CorrelationFixture correlation = b.engineFixture.correlation;
    i6 = incident(b, correlation.postAlertmanager("nowhere-z", "i-1", ALERTNAME));
    CLOCK.advance(Duration.ofSeconds(41));
    correlation.awaitState(i6, IncidentState.OPEN);
    correlation.resolve(i6);
    i7 = incident(b, correlation.postAlertmanager("nowhere-x", "i-1", ALERTNAME));
    assertThat(i7).isNotEqualTo(i6);
    assertThat(incidents.incident(b.tenant, i7).orElseThrow().state())
        .isEqualTo(IncidentState.FORMING);
    EngineFixture.await(() -> publicationCompleted(i6) && publicationCompleted(i7));
    assertThat(engine.runs(b.tenant, i6)).isEmpty();
    assertThat(engine.runs(b.tenant, i7)).isEmpty();
    app.context()
        .getBean(WorkflowImport.class)
        .importPart(b.tenant, WorkflowFixture.demoPart(), Instant.now(), Map.of());
    WorkflowsIncidentListener listener = listener();
    DoubleDelivery.assertSameResult(
        postgres, "routing", tx, () -> listener.onCreated(new IncidentCreated(b.tenant, i7)));
    assertThat(workflowEvaluations(b.tenant, i7)).hasSize(1);
    assertThat(engine.runs(b.tenant, i7))
        .singleElement()
        .satisfies(run -> assertThat(run.episode()).isEqualTo(1));
  }

  @Test
  @Order(8)
  @DisplayName("DM-R-70 vigil-workflows starts runs only for active tenants")
  void activeTenantsOnly() {
    WorkflowsIncidentListener listener = listener();
    UUID incidentD = Ids.newId();
    assertThat(workflows.workflow(d)).isPresent();
    deliver(() -> listener.onCreated(new IncidentCreated(d, incidentD)));
    assertThat(engine.runs(d, incidentD)).isEmpty();
    assertThat(routing.evaluations(d, incidentD)).isEmpty();
    List<EvaluationView> before = routing.evaluations(b.tenant, i6);
    deliver(() -> listener.onCreated(new IncidentCreated(b.tenant, i6)));
    assertThat(incidents.incident(b.tenant, i6).orElseThrow().state())
        .isEqualTo(IncidentState.RESOLVED);
    assertThat(engine.runs(b.tenant, i6)).isEmpty();
    assertThat(routing.evaluations(b.tenant, i6)).isEqualTo(before);
    assertThat(workflowEvaluations(b.tenant, i6)).isEmpty();
  }
}
