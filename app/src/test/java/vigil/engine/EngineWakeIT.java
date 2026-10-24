package vigil.engine;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
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
import vigil.actions.api.ApprovalDecided;
import vigil.engine.api.EndReason;
import vigil.engine.api.RunState;
import vigil.engine.api.RunView;
import vigil.engine.api.StepExecutionState;
import vigil.engine.api.WaitReason;
import vigil.incidents.api.IncidentAcknowledged;
import vigil.incidents.api.IncidentService;
import vigil.incidents.api.IncidentState;
import vigil.incidents.api.IncidentStateChanged;
import vigil.intake.IntakeFixture;
import vigil.intake.api.AlertStateChanged;
import vigil.intake.api.AlertStatus;
import vigil.intake.api.Health;
import vigil.intake.api.SourceHealthChanged;
import vigil.shared.Ids;
import vigil.shared.StepCall.StepType;
import vigil.shared.TenantId;
import vigil.shared.Waiting;
import vigil.shared.Waiting.WakeEvent;
import vigil.tenancy.api.TenantAliasChanged;
import vigil.tenancy.api.TenantService;
import vigil.testsupport.DoubleDelivery;
import vigil.testsupport.MutableClock;
import vigil.testsupport.TestSecrets;
import vigil.testsupport.TestTenants;
import vigil.testsupport.VigilDatabase;
import vigil.testsupport.VigilTestApp;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EngineWakeIT {

  private static final MutableClock CLOCK = new MutableClock(Instant.parse("2026-10-24T10:00:00Z"));
  private static final String HOST_A = "a.engine-wake.test";
  private static final String HOST_C = "c.engine-wake.test";
  private static final String ALERTNAME = "NodeFilesystemFull";
  private static final Duration STAYS = Duration.ofSeconds(3);

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
  private EngineFixture a;
  private EngineFixture c;
  private TenantId b;
  private TestHandlers handlers;
  private TransactionTemplate tx;
  private TenantService tenants;
  private int component;

  @BeforeAll
  void start() {
    wiremock = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort());
    wiremock.start();
    IntakeFixture.stub(wiremock);
    VigilDatabase database = VigilDatabase.fresh();
    postgres = database.postgres();
    TestSecrets secrets = IntakeFixture.secrets(database, "", "-c");
    app =
        VigilTestApp.start(
            database, secrets.folder(), List.of(), TestBeans.class, TestHandlers.Beans.class);
    app.awaitStarted();
    String address = "http://127.0.0.1:" + wiremock.port();
    a = EngineFixture.create(app, secrets, "Tenant A", HOST_A, address, "");
    c = EngineFixture.create(app, secrets, "Tenant C", HOST_C, address, "-c");
    b = new TestTenants(app).tenant("Tenant B").create().id();
    handlers = app.context().getBean(TestHandlers.class);
    tx = new TransactionTemplate(app.context().getBean(PlatformTransactionManager.class));
    tenants = app.context().getBean(TenantService.class);
  }

  @AfterAll
  void stop() {
    app.close();
    wiremock.stop();
  }

  private UUID openIncident(EngineFixture fixture) {
    component++;
    return fixture.openIncident(CLOCK, "nowhere-w" + component, "i-1", ALERTNAME);
  }

  private UUID formingIncident(EngineFixture fixture) {
    component++;
    return fixture.formingIncident("nowhere-w" + component, "i-1", ALERTNAME);
  }

  private <T> T listener(Class<T> type) {
    return AopTestUtils.getUltimateTargetObject(app.context().getBean(type));
  }

  private void deliver(Runnable listenerCall) {
    tx.executeWithoutResult(status -> listenerCall.run());
  }

  private static Waiting waiting(WakeEvent... events) {
    return new Waiting(Set.of(events), null, "{}");
  }

  /** Every row of the engine's tables, as text. */
  private List<String> engineRows() {
    return postgres
        .sql(
            "SELECT t::text FROM (SELECT to_jsonb(r)::text AS t FROM engine.workflow_run r"
                + " UNION ALL SELECT to_jsonb(s)::text FROM engine.step_execution s"
                + " UNION ALL SELECT to_jsonb(d)::text FROM engine.durable_timer d) rows"
                + " ORDER BY t")
        .query(String.class)
        .list();
  }

  private UUID runA;
  private UUID runF;
  private UUID incidentF;

  @Test
  @Order(1)
  @DisplayName("AR-Q-11 wakes are not lost: cases (a), (b), (d) and (f)")
  void wakesNotLost() {
    // (a) An event listed for the step arrives while its step is running.
    handlers.reset();
    handlers.answer(StepType.RECOVERY_CHECK, call -> waiting(WakeEvent.ALERT_STATE_CHANGED));
    handlers.block(StepType.RECOVERY_CHECK);
    runA = a.start(openIncident(a), TestContents.linear("recovery_check"));
    handlers.awaitBlocked(StepType.RECOVERY_CHECK, runA);
    deliver(
        () ->
            listener(EngineIntakeListener.class)
                .onAlertStateChanged(
                    new AlertStateChanged(a.tenant, Ids.newId(), AlertStatus.FIRING)));
    handlers.release(StepType.RECOVERY_CHECK);
    EngineFixture.await(
        () ->
            handlers.calls(runA).size() == 2
                && handlers.calls(runA).get(1).end() != null
                && a.run(runA).state() == RunState.WAITING);
    List<TestHandlers.Call> calls = handlers.calls(runA);
    assertThat(calls.get(0).operation).isEqualTo("start");
    assertThat(calls.get(1).operation).isEqualTo("evaluate");
    assertThat(calls.get(1).call.wakeReason().value()).isEqualTo("event:alert_state_changed");
    IntakeFixture.sleep(STAYS);
    assertThat(handlers.calls(runA)).hasSize(2);
    assertThat(a.run(runA).state()).isEqualTo(RunState.WAITING);
    assertThat(a.run(runA).wakePending()).isFalse();

    // (b) The incident leaves forming while the Trigger completes, and while the run waits.
    handlers.reset();
    UUID forming = formingIncident(a);
    UUID runB =
        tx.execute(
            status -> {
              UUID run = a.start(forming, TestContents.linear("ticket"));
              CLOCK.advance(Duration.ofSeconds(41));
              IncidentService incidents = app.context().getBean(IncidentService.class);
              EngineFixture.await(
                  () ->
                      incidents.incident(a.tenant, forming).orElseThrow().state()
                          == IncidentState.OPEN);
              return run;
            });
    EngineFixture.await(() -> a.steps(runB).contains("s-1"));
    a.awaitRunState(runB, RunState.FINISHED);
    UUID waitingIncident = formingIncident(a);
    UUID runB2 = a.start(waitingIncident, TestContents.linear("ticket"));
    RunView formingRun = a.awaitRunState(runB2, RunState.WAITING);
    assertThat(formingRun.waitReason()).isEqualTo(WaitReason.INCIDENT_FORMING);
    CLOCK.advance(Duration.ofSeconds(41));
    EngineFixture.await(() -> a.steps(runB2).contains("s-1"));
    a.awaitRunState(runB2, RunState.FINISHED);

    // (d) A wake and a duplicate hand-off during a retry wait.
    handlers.reset();
    handlers.failAlways(StepType.TICKET);
    UUID incidentD = openIncident(a);
    UUID runD = a.start(incidentD, TestContents.linear("ticket"));
    EngineFixture.await(
        () -> a.steps(runD).contains("s-1") && a.execution(runD, "s-1").failedAttempts() == 1);
    deliver(
        () ->
            listener(EngineIncidentListener.class)
                .onStateChanged(
                    new IncidentStateChanged(
                        a.tenant, incidentD, IncidentState.FORMING, IncidentState.OPEN)));
    a.handOff(runD);
    IntakeFixture.sleep(STAYS);
    assertThat(handlers.calls(runD)).hasSize(1);
    for (int n = 2; n <= 4; n++) {
      int attempt = n;
      CLOCK.advance(Duration.ofSeconds(List.of(5, 15, 45).get(n - 2)));
      a.handOff(runD);
      if (n < 4) {
        EngineFixture.await(() -> a.execution(runD, "s-1").failedAttempts() == attempt);
      }
    }
    RunView failed = a.awaitRunState(runD, RunState.FAILED);
    assertThat(failed.endReason()).isEqualTo(EndReason.STEP_FAILED_REPEATEDLY);
    assertThat(handlers.calls(runD)).hasSize(4);

    // (f) A tenant becomes dormant while one of its runs is in a step call.
    handlers.reset();
    handlers.block(StepType.TICKET);
    incidentF = openIncident(a);
    runF = a.start(incidentF, TestContents.linear("ticket", "ticket"));
    handlers.awaitBlocked(StepType.TICKET, runF);
    tenants.setAlias(HOST_A, b, "test");
    EngineFixture.await(() -> !EngineFixture.isScheduled(app, a.tenant));
    handlers.release(StepType.TICKET);
    EngineFixture.await(() -> a.execution(runF, "s-1").state() == StepExecutionState.COMPLETED);
    IntakeFixture.sleep(STAYS);
    RunView stopped = a.run(runF);
    assertThat(stopped.state()).isEqualTo(RunState.RUNNING);
    assertThat(stopped.nextStepId()).isEqualTo("s-2");
    assertThat(a.steps(runF)).containsExactly("s-trigger", "s-1");
  }

  @Test
  @Order(2)
  @DisplayName("DM-R-70 the engine works only for active tenants")
  void activeTenantsOnly() {
    assertThat(tenants.isActive(a.tenant)).isFalse();
    List<String> before = engineRows();
    RunView run = a.run(runF);
    deliver(
        () ->
            listener(EngineIncidentListener.class)
                .onStateChanged(
                    new IncidentStateChanged(
                        a.tenant, incidentF, IncidentState.FORMING, IncidentState.OPEN)));
    deliver(
        () ->
            listener(EngineIntakeListener.class)
                .onAlertStateChanged(
                    new AlertStateChanged(a.tenant, Ids.newId(), AlertStatus.FIRING)));
    assertThat(engineRows()).isEqualTo(before);
    assertThat(a.run(runF).wakePending()).isEqualTo(run.wakePending());
    assertThat(a.run(runF).wakeReason()).isEqualTo(run.wakeReason());
  }

  @Test
  @Order(3)
  @DisplayName("AR-R-19 a run stopped while its tenant was dormant goes on when it is active again")
  void activeAgain() {
    assertThat(EngineFixture.isScheduled(app, a.tenant)).isFalse();
    tenants.setAlias(HOST_A, a.tenant, "test");
    EngineFixture.await(() -> EngineFixture.isScheduled(app, a.tenant));
    EngineFixture.await(() -> a.steps(runF).contains("s-2"));
    a.awaitRunState(runF, RunState.FINISHED);
    assertThat(a.outputs(runF)).containsExactly("next", "done", "done");
  }

  @Test
  @Order(4)
  @DisplayName("DM-R-67 one evaluation at a time; a wake during a call leads to one more")
  void oneAtATime() {
    List<TestHandlers.Call> calls = handlers.calls(runA);
    assertThat(calls.size()).isGreaterThanOrEqualTo(2);
    for (int n = 1; n < calls.size(); n++) {
      assertThat(calls.get(n - 1).end()).isNotNull();
      assertThat(calls.get(n).start).isAfterOrEqualTo(calls.get(n - 1).end());
    }
  }

  @Test
  @Order(5)
  @DisplayName("AR-R-20 a hand-off is only a signal: the stored state decides")
  void handOffSignal() {
    handlers.reset();
    UUID run = c.start(openIncident(c), TestContents.linear("wait"));
    EngineFixture.await(() -> c.run(run).state() == RunState.WAITING);
    RunView waiting = c.run(run);
    assertThat(waiting.waitReason()).isEqualTo(WaitReason.STEP_WAITING);
    assertThat(waiting.wakePending()).isFalse();
    c.handOff(run);
    IntakeFixture.sleep(STAYS);
    assertThat(c.execution(run, "s-1").evaluationCount()).isEqualTo(1);
    CLOCK.advance(Duration.ofSeconds(61));
    c.awaitRunState(run, RunState.FINISHED);
  }

  /** Starts a run on a new incident of C, blocked in its first call. */
  private UUID blockedRun(StepType type, String content) {
    handlers.reset();
    handlers.block(type);
    UUID run = c.start(openIncident(c), content);
    handlers.awaitBlocked(type, run);
    return run;
  }

  private void doubleDelivery(Runnable deliverOnce) {
    DoubleDelivery.assertSameResult(postgres, "engine", tx, deliverOnce);
  }

  private void releaseAndFinish(StepType type, UUID run) {
    handlers.release(type);
    c.awaitRunState(run, RunState.FINISHED);
  }

  private void eachWake(StepType type, String content, Consumer<UUID> deliverOnce) {
    UUID run = blockedRun(type, content);
    doubleDelivery(() -> deliverOnce.accept(run));
    assertThat(c.run(run).wakePending()).isTrue();
    releaseAndFinish(type, run);
  }

  @Test
  @Order(6)
  @DisplayName("AR-Q-05 the engine's listeners give the same result when an event comes twice")
  void doubleDeliveries() {
    EngineIntakeListener intake = listener(EngineIntakeListener.class);
    EngineIncidentListener incident = listener(EngineIncidentListener.class);
    EngineApprovalListener approval = listener(EngineApprovalListener.class);
    EngineAliasListener alias = listener(EngineAliasListener.class);
    eachWake(
        StepType.RECOVERY_CHECK,
        TestContents.linear("recovery_check"),
        run ->
            intake.onAlertStateChanged(
                new AlertStateChanged(c.tenant, Ids.newId(), AlertStatus.RESOLVED)));
    eachWake(
        StepType.RECOVERY_CHECK,
        TestContents.linear("recovery_check"),
        run ->
            intake.onSourceHealthChanged(
                new SourceHealthChanged(c.tenant, Ids.newId(), Health.STALE)));
    eachWake(
        StepType.TICKET,
        TestContents.linear("ticket"),
        run ->
            incident.onAcknowledged(
                new IncidentAcknowledged(c.tenant, c.run(run).incidentId(), 1)));
    eachWake(
        StepType.TICKET,
        TestContents.linear("ticket"),
        run ->
            incident.onStateChanged(
                new IncidentStateChanged(
                    c.tenant, c.run(run).incidentId(), IncidentState.FORMING, IncidentState.OPEN)));
    eachWake(
        StepType.APPROVAL,
        TestContents.linear("approval"),
        run ->
            approval.on(
                new ApprovalDecided(
                    c.tenant, Ids.newId(), run, c.run(run).currentStepExecutionId())));

    for (IncidentState to : List.of(IncidentState.MERGED, IncidentState.CLOSED)) {
      handlers.reset();
      handlers.answer(StepType.RECOVERY_CHECK, call -> waiting());
      UUID incidentId = openIncident(c);
      UUID run = c.start(incidentId, TestContents.linear("recovery_check"));
      c.awaitRunState(run, RunState.WAITING);
      doubleDelivery(
          () ->
              incident.onStateChanged(
                  new IncidentStateChanged(c.tenant, incidentId, IncidentState.OPEN, to)));
      RunView cancelled = c.run(run);
      assertThat(cancelled.state()).isEqualTo(RunState.CANCELLED);
      assertThat(cancelled.endReason())
          .isEqualTo(
              to == IncidentState.MERGED ? EndReason.INCIDENT_MERGED : EndReason.INCIDENT_CLOSED);
      assertThat(handlers.cancels().stream().filter(cancel -> cancel.runId().equals(run)))
          .hasSize(1);
    }

    Set<TenantId> before = app.context().getBean(EngineWork.class).scheduledTenants();
    doubleDelivery(() -> alias.on(new TenantAliasChanged(HOST_C, c.tenant, null)));
    assertThat(app.context().getBean(EngineWork.class).scheduledTenants()).isEqualTo(before);
  }
}
