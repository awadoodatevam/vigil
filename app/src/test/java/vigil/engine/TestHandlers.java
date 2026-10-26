package vigil.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.function.Function;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vigil.actions.api.ActionRuns;
import vigil.actions.api.ActionsStepHandler;
import vigil.diagnostics.api.DiagnosticsStepHandler;
import vigil.intake.IntakeFixture;
import vigil.investigation.api.InvestigationStepHandler;
import vigil.notifications.api.NotificationsStepHandler;
import vigil.recovery.api.RecoveryStepHandler;
import vigil.routing.api.RoutingStepHandler;
import vigil.shared.Completed;
import vigil.shared.Completed.ResultRef;
import vigil.shared.Ids;
import vigil.shared.Key;
import vigil.shared.StepAnswer;
import vigil.shared.StepCall;
import vigil.shared.StepCall.StepType;
import vigil.shared.StepCancel;
import vigil.shared.TenantId;
import vigil.ticketing.api.TicketingStepHandler;

/**
 * Test step handlers (P-17 spec Section 7): answers set per step type, blocks released by the test,
 * failures, and every call and cancel recorded. {@link Beans} gives the bean and one adapter bean
 * per handler interface and for {@code ActionRuns}.
 */
public final class TestHandlers {

  private static final Duration WAIT = Duration.ofSeconds(10);

  /** The proposal every run has by default. */
  public static final UUID PROPOSAL = UUID.fromString("00000000-0000-7000-8000-000000000017");

  /** One call: its input, operation, thread, transaction, start and (once returned) end. */
  public static final class Call {
    public final StepCall call;
    public final String operation;
    public final String thread;
    public final boolean virtualThread;
    public final boolean transactionActive;
    public final Instant start;
    private volatile Instant end;

    Call(StepCall call, String operation) {
      this.call = call;
      this.operation = operation;
      this.thread = Thread.currentThread().getName();
      this.virtualThread = Thread.currentThread().isVirtual();
      this.transactionActive = TransactionSynchronizationManager.isActualTransactionActive();
      this.start = Instant.now();
    }

    public Instant end() {
      return end;
    }
  }

  /** The beans of the test handlers; passed to {@code VigilTestApp.start}. */
  public static class Beans {

    @Bean
    TestHandlers testHandlers() {
      return new TestHandlers();
    }

    @Bean
    @Primary
    DiagnosticsStepHandler testDiagnosticsHandler(TestHandlers handlers) {
      return new DiagnosticsStepHandler() {
        public StepAnswer start(StepCall call) {
          return handlers.call(call, "start");
        }

        public StepAnswer evaluate(StepCall call) {
          return handlers.call(call, "evaluate");
        }

        public void cancel(StepCancel cancel) {
          handlers.cancels.add(cancel);
        }
      };
    }

    @Bean
    @Primary
    InvestigationStepHandler testInvestigationHandler(TestHandlers handlers) {
      return new InvestigationStepHandler() {
        public StepAnswer start(StepCall call) {
          return handlers.call(call, "start");
        }

        public StepAnswer evaluate(StepCall call) {
          return handlers.call(call, "evaluate");
        }

        public void cancel(StepCancel cancel) {
          handlers.cancels.add(cancel);
        }
      };
    }

    @Bean
    @Primary
    ActionsStepHandler testActionsHandler(TestHandlers handlers) {
      return new ActionsStepHandler() {
        public StepAnswer start(StepCall call) {
          return handlers.call(call, "start");
        }

        public StepAnswer evaluate(StepCall call) {
          return handlers.call(call, "evaluate");
        }

        public void cancel(StepCancel cancel) {
          handlers.cancels.add(cancel);
        }
      };
    }

    @Bean
    @Primary
    RecoveryStepHandler testRecoveryHandler(TestHandlers handlers) {
      return new RecoveryStepHandler() {
        public StepAnswer start(StepCall call) {
          return handlers.call(call, "start");
        }

        public StepAnswer evaluate(StepCall call) {
          return handlers.call(call, "evaluate");
        }

        public void cancel(StepCancel cancel) {
          handlers.cancels.add(cancel);
        }
      };
    }

    @Bean
    @Primary
    RoutingStepHandler testRoutingHandler(TestHandlers handlers) {
      return new RoutingStepHandler() {
        public StepAnswer start(StepCall call) {
          return handlers.call(call, "start");
        }

        public StepAnswer evaluate(StepCall call) {
          return handlers.call(call, "evaluate");
        }

        public void cancel(StepCancel cancel) {
          handlers.cancels.add(cancel);
        }
      };
    }

    @Bean
    @Primary
    NotificationsStepHandler testNotificationsHandler(TestHandlers handlers) {
      return new NotificationsStepHandler() {
        public StepAnswer start(StepCall call) {
          return handlers.call(call, "start");
        }

        public StepAnswer evaluate(StepCall call) {
          return handlers.call(call, "evaluate");
        }

        public void cancel(StepCancel cancel) {
          handlers.cancels.add(cancel);
        }
      };
    }

    @Bean
    @Primary
    TicketingStepHandler testTicketingHandler(TestHandlers handlers) {
      return new TicketingStepHandler() {
        public StepAnswer start(StepCall call) {
          return handlers.call(call, "start");
        }

        public StepAnswer evaluate(StepCall call) {
          return handlers.call(call, "evaluate");
        }

        public void cancel(StepCancel cancel) {
          handlers.cancels.add(cancel);
        }
      };
    }

    @Bean
    @Primary
    ActionRuns testActionRuns(TestHandlers handlers) {
      return new ActionRuns() {
        public Optional<UUID> proposal(TenantId tenantId, UUID runId) {
          return handlers.proposal.apply(runId);
        }

        public Optional<UUID> proposalOfRequest(TenantId tenantId, UUID approvalRequestId) {
          return handlers.proposalOfRequest.apply(approvalRequestId);
        }

        public Optional<UUID> lastExecutedStepExecution(TenantId tenantId, UUID runId) {
          return handlers.lastExecutedStepExecution.apply(runId);
        }

        public List<UUID> earlierApprovers(TenantId tenantId, UUID runId) {
          return handlers.earlierApprovers.apply(runId);
        }

        public Optional<Key> latestRecoveryCheckKey(TenantId tenantId, UUID runId) {
          return handlers.latestRecoveryCheckKey.apply(runId);
        }

        public Completed reconcile(StepCall call) {
          return (Completed) handlers.call(call, "reconcile");
        }
      };
    }
  }

  private final Map<StepType, Function<StepCall, StepAnswer>> answers = new ConcurrentHashMap<>();
  private final Map<StepType, CountDownLatch> blocks = new ConcurrentHashMap<>();
  private final Map<StepType, Boolean> failures = new ConcurrentHashMap<>();
  private final List<Call> calls = new CopyOnWriteArrayList<>();
  private final List<StepCall> blocked = new CopyOnWriteArrayList<>();
  final List<StepCancel> cancels = new CopyOnWriteArrayList<>();
  private volatile Function<UUID, Optional<UUID>> proposal;
  private volatile Function<UUID, Optional<UUID>> proposalOfRequest;
  private volatile Function<UUID, Optional<UUID>> lastExecutedStepExecution;
  private volatile Function<UUID, List<UUID>> earlierApprovers;
  private volatile Function<UUID, Optional<Key>> latestRecoveryCheckKey;
  private volatile Function<StepCall, Completed> reconcile;

  TestHandlers() {
    reset();
  }

  /** The default answer of each step type (complete list of P-17 spec Section 7). */
  private static Map<StepType, Function<StepCall, StepAnswer>> defaults() {
    Map<StepType, Function<StepCall, StepAnswer>> defaults = new EnumMap<>(StepType.class);
    defaults.put(StepType.DIAGNOSTIC, call -> completed("done"));
    defaults.put(StepType.AI_TASK, call -> completed("done"));
    defaults.put(
        StepType.APPROVAL,
        call ->
            new Completed(
                new Key("approved"),
                new ResultRef("vigil-actions", "ApprovalRequest", Ids.newId())));
    defaults.put(StepType.ACTION, call -> completed("succeeded"));
    defaults.put(StepType.RECOVERY_CHECK, call -> completed("recovered"));
    defaults.put(StepType.ESCALATION, call -> completed("next"));
    defaults.put(StepType.NOTIFICATION, call -> completed("sent"));
    defaults.put(StepType.TICKET, call -> completed("done"));
    return defaults;
  }

  public static Completed completed(String output) {
    return new Completed(new Key(output), null);
  }

  /** Default answers, no block and no failure; the recorded calls and cancels are kept. */
  public void reset() {
    answers.putAll(defaults());
    blocks.values().forEach(CountDownLatch::countDown);
    blocks.clear();
    failures.clear();
    proposal = run -> Optional.of(PROPOSAL);
    proposalOfRequest = request -> Optional.of(PROPOSAL);
    lastExecutedStepExecution = run -> Optional.empty();
    earlierApprovers = run -> List.of();
    latestRecoveryCheckKey = run -> Optional.empty();
    reconcile = call -> completed("succeeded");
  }

  /** Replaces the answer of {@code start} and {@code evaluate} for the step type. */
  public void answer(StepType type, Function<StepCall, StepAnswer> answer) {
    answers.put(type, answer);
  }

  /** The following calls of the type wait until {@link #release}. */
  public void block(StepType type) {
    blocks.put(type, new CountDownLatch(1));
  }

  /** Lets every waiting call of the type go on, answering as set, and ends the block. */
  public void release(StepType type) {
    CountDownLatch latch = blocks.remove(type);
    if (latch != null) {
      latch.countDown();
    }
  }

  /** Waits, at most 10 s, until a call of the run is waiting in a block of the type. */
  public void awaitBlocked(StepType type, UUID runId) {
    long deadline = System.nanoTime() + WAIT.toNanos();
    while (blocked.stream()
        .noneMatch(call -> call.stepType() == type && call.runId().equals(runId))) {
      assertThat(System.nanoTime())
          .as("a blocked %s call of run %s", type, runId)
          .isLessThan(deadline);
      IntakeFixture.sleep(Duration.ofMillis(20));
    }
  }

  /** Every call of the type throws. */
  public void failAlways(StepType type) {
    failures.put(type, true);
  }

  /** Every call from its start, in order. */
  public List<Call> calls() {
    return List.copyOf(calls);
  }

  /** The calls of the run, in order. */
  public List<Call> calls(UUID runId) {
    return calls.stream().filter(call -> call.call.runId().equals(runId)).toList();
  }

  public List<StepCancel> cancels() {
    return List.copyOf(cancels);
  }

  public void proposal(Function<UUID, Optional<UUID>> answer) {
    proposal = answer;
  }

  public void proposalOfRequest(Function<UUID, Optional<UUID>> answer) {
    proposalOfRequest = answer;
  }

  public void lastExecutedStepExecution(Function<UUID, Optional<UUID>> answer) {
    lastExecutedStepExecution = answer;
  }

  public void earlierApprovers(Function<UUID, List<UUID>> answer) {
    earlierApprovers = answer;
  }

  public void latestRecoveryCheckKey(Function<UUID, Optional<Key>> answer) {
    latestRecoveryCheckKey = answer;
  }

  public void reconcile(Function<StepCall, Completed> answer) {
    reconcile = answer;
  }

  private StepAnswer call(StepCall stepCall, String operation) {
    Call call = new Call(stepCall, operation);
    calls.add(call);
    try {
      CountDownLatch latch = blocks.get(stepCall.stepType());
      if (latch != null && !operation.equals("reconcile")) {
        blocked.add(stepCall);
        try {
          latch.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(e);
        } finally {
          blocked.remove(stepCall);
        }
      }
      if (failures.containsKey(stepCall.stepType())) {
        throw new IllegalStateException("test failure of " + stepCall.stepType().key());
      }
      return operation.equals("reconcile")
          ? reconcile.apply(stepCall)
          : answers.get(stepCall.stepType()).apply(stepCall);
    } finally {
      call.end = Instant.now();
    }
  }
}
