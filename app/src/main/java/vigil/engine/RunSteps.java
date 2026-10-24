package vigil.engine;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vigil.engine.EngineRecords.RunRow;
import vigil.engine.EngineRecords.StepRow;
import vigil.engine.api.EndReason;
import vigil.engine.api.RunMode;
import vigil.engine.api.RunState;
import vigil.engine.api.StepExecutionState;
import vigil.engine.api.WaitReason;
import vigil.incidents.api.IncidentService;
import vigil.incidents.api.IncidentState;
import vigil.incidents.api.IncidentView;
import vigil.shared.Completed;
import vigil.shared.Ids;
import vigil.shared.Key;
import vigil.shared.StepAnswer;
import vigil.shared.StepCall;
import vigil.shared.StepCall.StepType;
import vigil.shared.StepCancel;
import vigil.shared.TenantId;
import vigil.shared.Timestamps;
import vigil.shared.Waiting;

/**
 * What a run executor does with one hand-off (AR-R-20 cases (a) to (h)): each step in the three
 * parts of AR-R-22, with retries (AR-TM-05), the engine's own steps (PM-M12-02), the Action step
 * guard (DM-R-31) and the reconciliation of an interrupted Action step (PM-M12-10).
 */
@Component
class RunSteps {

  private static final Logger LOG = LoggerFactory.getLogger(RunSteps.class);
  private static final List<Duration> RETRY_WAITS =
      List.of(Duration.ofSeconds(5), Duration.ofSeconds(15), Duration.ofSeconds(45));
  private static final int ATTEMPTS = 4;
  private static final String START = "start";

  /**
   * A call prepared by part (1): the run, its step execution and the step in the run's content;
   * {@code start} when the operation is {@code start}; {@code reconcile} for case (g) of an Action
   * step.
   */
  private record Call(
      RunRow run,
      StepRow step,
      RunContent.Step content,
      boolean start,
      String wakeReason,
      boolean reconcile) {}

  /** The outcome of part (2): an answer (with a guard refusal reason) or a failed call. */
  private record Result(StepAnswer answer, String refusalReason, boolean failed) {}

  private final EngineRecords records;
  private final Runs runs;
  private final StepHandlers handlers;
  private final HandOffs handOffs;
  private final ActiveTenants activeTenants;
  private final IncidentService incidents;
  private final TransactionTemplate tx;
  private final Clock clock;

  RunSteps(
      EngineRecords records,
      Runs runs,
      StepHandlers handlers,
      HandOffs handOffs,
      ActiveTenants activeTenants,
      IncidentService incidents,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.records = records;
    this.runs = runs;
    this.handlers = handlers;
    this.handOffs = handOffs;
    this.activeTenants = activeTenants;
    this.incidents = incidents;
    this.tx = new TransactionTemplate(transactionManager);
    this.clock = clock;
  }

  /** Processes one hand-off of the run; after a {@code Completed} answer it goes on. */
  void process(TenantId tenantId, UUID runId) {
    Call call = tx.execute(status -> begin(tenantId, runId));
    while (call != null) {
      Call current = call;
      Result result = invoke(current);
      boolean next = Boolean.TRUE.equals(tx.execute(status -> store(current, result)));
      call = next ? tx.execute(status -> begin(tenantId, runId)) : null;
    }
  }

  // Part (1): the cases of AR-R-20, in one transaction that locks the run.

  private Call begin(TenantId tenantId, UUID runId) {
    Instant now = Timestamps.now(clock);
    RunRow run = records.lockRun(tenantId, runId).orElse(null);
    if (run == null
        || !activeTenants.contains(tenantId)
        || (run.state != RunState.RUNNING && run.state != RunState.WAITING)) {
      return null;
    }
    if (run.state == RunState.WAITING) {
      if (!run.wakePending) {
        return null;
      }
      if (run.waitReason == WaitReason.INCIDENT_FORMING) {
        return startStep(run, now);
      }
      StepRow step = current(run);
      if (step == null || step.state != StepExecutionState.WAITING) {
        run.wakePending = false;
        records.updateRun(run, now);
        return null;
      }
      return reevaluate(run, step, run.wakeReason, false, now);
    }
    StepRow step = current(run);
    if (step == null) {
      return null;
    }
    if (step.state == StepExecutionState.COMPLETED) {
      return run.nextStepId == null ? null : startStep(run, now);
    }
    if (step.state != StepExecutionState.RUNNING) {
      return null;
    }
    if (step.retryDueAt != null) {
      return step.retryDueAt.isAfter(now) ? null : reevaluate(run, step, "retry", false, now);
    }
    boolean reconcile = step.stepType == StepType.ACTION && step.namedOutput == null;
    return reevaluate(run, step, "resume", reconcile, now);
  }

  private StepRow current(RunRow run) {
    return run.currentStepExecutionId == null
        ? null
        : records.step(run.tenantId, run.currentStepExecutionId).orElse(null);
  }

  /** Part (1) for the checkpoint step: the incident's state first (DM-R-16). */
  private Call startStep(RunRow run, Instant now) {
    run.wakePending = false;
    run.state = RunState.RUNNING;
    run.waitReason = null;
    if (run.mode == RunMode.LIVE) {
      IncidentState state = incident(run).state();
      if (state == IncidentState.MERGED || state == IncidentState.CLOSED) {
        records.updateRun(run, now);
        runs.cancelIncidentRuns(
            run.tenantId,
            run.incidentId,
            state == IncidentState.MERGED ? EndReason.INCIDENT_MERGED : EndReason.INCIDENT_CLOSED,
            now);
        return null;
      }
      if (state == IncidentState.FORMING) {
        run.state = RunState.WAITING;
        run.waitReason = WaitReason.INCIDENT_FORMING;
        records.updateRun(run, now);
        return null;
      }
    }
    RunContent.Step content = RunContent.parse(run.content).step(run.nextStepId).orElseThrow();
    if (content.type() == null) {
      throw new IllegalStateException("step " + content.stepId() + " has no step type");
    }
    StepRow step = new StepRow();
    step.id = Ids.newId();
    step.runId = run.id;
    step.stepId = content.stepId();
    step.stepType = content.type();
    step.visit = 1 + records.visits(run.tenantId, run.id, content.stepId());
    step.state = StepExecutionState.RUNNING;
    step.startedAt = now;
    step.evaluationCount = 1;
    records.insertStep(run.tenantId, step, now);
    run.currentStepExecutionId = step.id;
    run.nextStepId = null;
    records.updateRun(run, now);
    runs.stepChanged(run, step);
    return new Call(run, step, content, true, START, false);
  }

  /** Part (1) for the current step execution (cases (d), (f), (g)). */
  private Call reevaluate(
      RunRow run, StepRow step, String wakeReason, boolean reconcile, Instant now) {
    run.state = RunState.RUNNING;
    run.waitReason = null;
    run.wakePending = false;
    records.updateRun(run, now);
    step.state = StepExecutionState.RUNNING;
    step.retryDueAt = null;
    step.evaluationCount++;
    records.updateStep(run.tenantId, step, now);
    runs.stepChanged(run, step);
    RunContent.Step content = RunContent.parse(run.content).step(step.stepId).orElseThrow();
    return new Call(run, step, content, step.wakeEvents == null, wakeReason, reconcile);
  }

  // Part (2): the call, with no transaction open.

  private Result invoke(Call call) {
    try {
      StepType type = call.step().stepType;
      if (call.reconcile()) {
        return new Result(handlers.reconcile(stepCall(call)), null, false);
      }
      return switch (type) {
        case TRIGGER -> answer(new Completed(new Key(Engine.NEXT), null));
        case CONDITION ->
            answer(
                new Completed(
                    new Key(Conditions.output(incident(call.run()), call.content().parameters())),
                    null));
        case WAIT ->
            answer(
                WaitStep.answer(
                    call.start(),
                    incident(call.run()),
                    call.content().parameters(),
                    call.step().stepState,
                    Timestamps.now(clock)));
        case ACTION -> action(call);
        default -> handlerAnswer(call);
      };
    } catch (RuntimeException | Error e) {
      LOG.error(
          "step {} of run {} for tenant {} failed (attempt {})",
          call.step().stepId,
          call.run().id,
          call.run().tenantId.value(),
          call.step().failedAttempts + 1,
          e);
      return new Result(null, null, true);
    }
  }

  private static Result answer(StepAnswer answer) {
    return new Result(answer, null, false);
  }

  /** The guard before the first call of a new Action step execution, then the handler. */
  private Result action(Call call) {
    if (call.wakeReason().equals(START)) {
      RunRow run = call.run();
      List<StepRow> steps = records.steps(run.tenantId, run.id);
      var refusal =
          ActionGuard.refusal(
              RunContent.parse(run.content),
              call.step().stepId,
              steps.stream().map(StepRow::view).toList(),
              handlers.proposal(run.tenantId, run.id),
              handlers.lastExecutedStepExecution(run.tenantId, run.id),
              request -> handlers.proposalOfRequest(run.tenantId, request));
      if (refusal.isPresent()) {
        return new Result(new Completed(new Key("failed"), null), refusal.get(), false);
      }
    }
    return handlerAnswer(call);
  }

  private Result handlerAnswer(Call call) {
    StepType type = call.step().stepType;
    StepHandlers.Handler handler =
        handlers
            .handler(type)
            .orElseThrow(() -> new IllegalStateException("no step handler for " + type.key()));
    StepCall stepCall = stepCall(call);
    return answer(
        call.start() ? handler.start().apply(stepCall) : handler.evaluate().apply(stepCall));
  }

  private StepCall stepCall(Call call) {
    RunRow run = call.run();
    StepRow step = call.step();
    String stepState =
        step.stepState == null
            ? null
            : RunContent.JSON.writeValueAsString(RunContent.JSON.readTree(step.stepState));
    return new StepCall(
        run.tenantId,
        run.id,
        run.incidentId,
        step.id,
        new Key(step.stepId),
        step.stepType,
        RunContent.JSON.writeValueAsString(call.content().parameters()),
        step.evaluationCount,
        stepState,
        new StepCall.WakeReason(call.wakeReason()),
        step.stepType == StepType.RECOVERY_CHECK
            ? handlers.latestRecoveryCheckKey(run.tenantId, run.id)
            : null,
        step.stepType == StepType.APPROVAL
            ? handlers.earlierApprovers(run.tenantId, run.id)
            : List.of());
  }

  private IncidentView incident(RunRow run) {
    return incidents.incident(run.tenantId, run.incidentId).orElseThrow();
  }

  // Part (3): the answer, in one transaction that locks the run; true when the run goes on.

  private boolean store(Call call, Result result) {
    Instant now = Timestamps.now(clock);
    TenantId tenantId = call.run().tenantId;
    RunRow run = records.lockRun(tenantId, call.run().id).orElseThrow();
    StepRow step = records.step(tenantId, call.step().id).orElseThrow();
    if (run.state != RunState.RUNNING
        || !step.id.equals(run.currentStepExecutionId)
        || step.state != StepExecutionState.RUNNING) {
      handlers.cancel(
          new StepCancel(
              tenantId, run.id, run.incidentId, step.id, new Key(step.stepId), step.stepType));
      return false;
    }
    if (result.failed()) {
      return failed(run, step, call.content(), now);
    }
    records.cancelTimer(tenantId, step.id, now);
    step.timerId = null;
    step.failedAttempts = 0;
    step.retryDueAt = null;
    if (result.answer() instanceof Completed completed) {
      step.namedOutput = completed.namedOutput().value();
      step.resultRef = completed.resultRef();
      step.refusalReason = result.refusalReason();
      step.finishedAt = now;
      step.state = StepExecutionState.COMPLETED;
      records.updateStep(tenantId, step, now);
      runs.stepChanged(run, step);
      var target = RunContent.parse(run.content).target(step.stepId, step.namedOutput);
      if (target.isEmpty()) {
        runs.end(run, RunState.FINISHED, EndReason.PATH_ENDED, null, now);
        return false;
      }
      run.nextStepId = target.get();
      records.updateRun(run, now);
      return true;
    }
    Waiting waiting = (Waiting) result.answer();
    step.wakeEvents = waiting.wakeEvents();
    step.stepState = waiting.stepState();
    step.state = StepExecutionState.WAITING;
    if (waiting.timerDueAt() != null) {
      step.timerId =
          records.insertTimer(
              tenantId,
              Ids.newId(),
              run.id,
              step.id,
              waiting.timerDueAt().truncatedTo(ChronoUnit.MILLIS),
              step.stepType.key() + " timer",
              now);
    }
    records.updateStep(tenantId, step, now);
    run.state = RunState.WAITING;
    run.waitReason = WaitReason.STEP_WAITING;
    records.updateRun(run, now);
    if (run.wakePending) {
      handOffs.afterCommit(tenantId, run.id);
    }
    runs.stepChanged(run, step);
    return false;
  }

  /** A failed call: a retry after 5 s, 15 s or 45 s; after the 4th attempt the run fails. */
  private boolean failed(RunRow run, StepRow step, RunContent.Step content, Instant now) {
    step.failedAttempts++;
    if (step.failedAttempts < ATTEMPTS) {
      Duration wait = RETRY_WAITS.get((int) step.failedAttempts - 1);
      step.retryDueAt = now.plus(wait);
      records.updateStep(run.tenantId, step, now);
      handOffs.afterCommit(run.tenantId, run.id, wait);
      return false;
    }
    runs.cancelStep(run, step, now);
    runs.stepChanged(run, step);
    runs.end(
        run,
        RunState.FAILED,
        EndReason.STEP_FAILED_REPEATEDLY,
        "step '" + content.label() + "' did not answer after " + ATTEMPTS + " attempts",
        now);
    return false;
  }
}
