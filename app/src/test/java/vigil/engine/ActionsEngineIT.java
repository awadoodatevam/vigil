package vigil.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import vigil.actions.ActionsFixture;
import vigil.actions.api.ActionsService;
import vigil.actions.api.Decision;
import vigil.engine.api.EngineService;
import vigil.engine.api.RunState;
import vigil.engine.api.RunView;
import vigil.intake.IntakeFixture;
import vigil.notifications.NotificationsApp;
import vigil.notifications.api.ApprovalRecipient;
import vigil.notifications.api.DeliveryAddressView;
import vigil.notifications.api.NotificationRecordView;
import vigil.notifications.api.NotificationsService;
import vigil.notifications.api.SendOutcome;
import vigil.runner.LabTarget;
import vigil.shared.Ids;
import vigil.shared.Key;
import vigil.shared.StepCall.StepType;
import vigil.shared.TenantId;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ActionsEngineIT {

  static final Duration WAIT = Duration.ofSeconds(60);
  static final BlockingNotifications NOTIFICATIONS = new BlockingNotifications();

  /**
   * The test handlers of the other modules; the test handler and the test reads of vigil-actions
   * removed, so its real beans serve the engine; a blocking notifications service.
   */
  static class ActionsBeans {

    @Bean
    static BeanDefinitionRegistryPostProcessor withRealActions() {
      return registry -> {
        registry.removeBeanDefinition("testActionsHandler");
        registry.removeBeanDefinition("testActionRuns");
      };
    }

    @Bean
    @Primary
    NotificationsService blockingNotifications(
        @Qualifier("notifications") NotificationsService real) {
      NOTIFICATIONS.real = real;
      return NOTIFICATIONS;
    }
  }

  /** Delegates to the real bean; approval requests wait while the test holds them. */
  static final class BlockingNotifications implements NotificationsService {
    volatile NotificationsService real;
    volatile CountDownLatch hold;
    volatile CountDownLatch entered;

    @Override
    public List<DeliveryAddressView> deliveryAddresses(TenantId tenantId) {
      return real.deliveryAddresses(tenantId);
    }

    @Override
    public List<NotificationRecordView> records(TenantId tenantId, UUID incidentId) {
      return real.records(tenantId, incidentId);
    }

    @Override
    public SendOutcome notifyEscalation(
        TenantId tenantId,
        UUID incidentId,
        UUID escalationId,
        long level,
        List<Key> teamKeys,
        vigil.incidents.api.EscalationCause cause,
        Duration timer) {
      return real.notifyEscalation(
          tenantId, incidentId, escalationId, level, teamKeys, cause, timer);
    }

    @Override
    public SendOutcome notifyApprovalRequest(
        TenantId tenantId,
        UUID incidentId,
        UUID approvalRequestId,
        List<ApprovalRecipient> recipients,
        String actionName,
        String targetName,
        Instant expiresAt) {
      CountDownLatch release = hold;
      if (release != null) {
        entered.countDown();
        try {
          release.await(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      return real.notifyApprovalRequest(
          tenantId, incidentId, approvalRequestId, recipients, actionName, targetName, expiresAt);
    }
  }

  private NotificationsApp servers;
  private EngineService engine;
  private ActionsService actions;
  private TestHandlers handlers;
  private ActionsFixture f;

  @BeforeAll
  void start() {
    servers = NotificationsApp.start(TestHandlers.Beans.class, ActionsBeans.class);
    engine = servers.app.context().getBean(EngineService.class);
    actions = servers.app.context().getBean(ActionsService.class);
    handlers = servers.app.context().getBean(TestHandlers.class);
    f = ActionsFixture.create(servers, "Engine actions");
  }

  @AfterAll
  void stop() {
    servers.close();
  }

  private static void await(BooleanSupplier condition) {
    long deadline = System.nanoTime() + WAIT.toNanos();
    while (!condition.getAsBoolean()) {
      assertThat(System.nanoTime()).as("condition met within %s", WAIT).isLessThan(deadline);
      IntakeFixture.sleep(Duration.ofMillis(100));
    }
  }

  private RunView run(UUID run) {
    return engine.run(f.tenant, run).orElseThrow();
  }

  private UUID startRun(UUID incident) {
    return engine
        .startLiveRun(
            f.tenant,
            EngineFixture.WORKFLOWS,
            incident,
            1,
            Ids.newId(),
            TestContents.linear("approval", "action", "recovery_check"),
            false)
        .orElseThrow();
  }

  private List<Map<String, Object>> requests(UUID run) {
    return f.rowsOfRun("approval_request", run);
  }

  @Test
  @DisplayName("AR-Q-11 a cancel while start of an Approval step runs leaves the request cancelled")
  void cancelDuringStart() throws InterruptedException {
    NOTIFICATIONS.entered = new CountDownLatch(1);
    NOTIFICATIONS.hold = new CountDownLatch(1);
    CountDownLatch release = NOTIFICATIONS.hold;
    UUID incident = f.openIncident();
    f.match(incident);
    UUID run = startRun(incident);
    try {
      assertThat(NOTIFICATIONS.entered.await(60, TimeUnit.SECONDS)).isTrue();
      UUID reopened =
          engine
              .startLiveRun(
                  f.tenant,
                  EngineFixture.WORKFLOWS,
                  incident,
                  2,
                  Ids.newId(),
                  TestContents.waitUntilEvent(60),
                  true)
              .orElseThrow();
      assertThat(run(run).state()).isEqualTo(RunState.CANCELLED);
      assertThat(reopened).isNotEqualTo(run);
    } finally {
      NOTIFICATIONS.hold = null;
      release.countDown();
    }
    await(() -> "cancelled".equals(requests(run).getFirst().get("state")));
    IntakeFixture.sleep(Duration.ofSeconds(2));
    assertThat(requests(run)).hasSize(1);
    assertThat(requests(run).getFirst().get("state")).isEqualTo("cancelled");
    assertThat(run(run).state()).isEqualTo(RunState.CANCELLED);
  }

  @Test
  @DisplayName(
      "DM-R-34 Approval, decision and Action with the guard; the recovery check key passed")
  void demoPath() {
    LabTarget lab = LabTarget.shared();
    lab.inject();
    try {
      UUID incident = f.openIncident();
      f.match(incident);
      UUID run = startRun(incident);
      await(() -> !requests(run).isEmpty() && run(run).state() == RunState.WAITING);
      Map<String, Object> request = requests(run).getFirst();
      assertThat(request.get("state")).isEqualTo("pending");
      actions.decide(
          f.tenant,
          (UUID) request.get("id"),
          f.userId("dbops-engineer"),
          Decision.APPROVE,
          (String) request.get("snapshot_hash"));
      await(
          () ->
              handlers.calls(run).stream()
                  .anyMatch(call -> call.call.stepType() == StepType.RECOVERY_CHECK));
      List<Map<String, Object>> executions = f.rowsOfRun("action_execution", run);
      assertThat(executions).hasSize(1);
      assertThat(executions.getFirst().get("state")).isEqualTo("succeeded");
      TestHandlers.Call recovery =
          handlers.calls(run).stream()
              .filter(call -> call.call.stepType() == StepType.RECOVERY_CHECK)
              .findFirst()
              .orElseThrow();
      assertThat(recovery.call.recoveryCheckKey()).isEqualTo(new Key("rc-subscriber-db"));
      await(() -> run(run).state() == RunState.FINISHED);
    } finally {
      lab.clear();
    }
  }
}
