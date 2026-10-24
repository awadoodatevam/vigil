package vigil.engine;

import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import vigil.engine.EngineRecords.RunRow;
import vigil.engine.EngineRecords.StepRow;
import vigil.engine.api.EndReason;
import vigil.engine.api.RunFinished;
import vigil.engine.api.RunState;
import vigil.engine.api.StepChanged;
import vigil.engine.api.StepExecutionState;
import vigil.shared.Key;
import vigil.shared.StepCancel;
import vigil.shared.TenantId;

/**
 * What every change of a run shares, inside the caller's transaction: the events of AR-E-10, the
 * end of a run with its {@code INFO} line, and the cancellation of DM-SM07-T5.
 */
@Component
class Runs {

  private static final Logger LOG = LoggerFactory.getLogger(Runs.class);

  private final EngineRecords records;
  private final StepHandlers handlers;
  private final ApplicationEventPublisher publisher;

  Runs(EngineRecords records, StepHandlers handlers, ApplicationEventPublisher publisher) {
    this.records = records;
    this.handlers = handlers;
    this.publisher = publisher;
  }

  /** Publishes {@code StepChanged} with the step execution's state. */
  void stepChanged(RunRow run, StepRow step) {
    publisher.publishEvent(
        new StepChanged(
            run.tenantId,
            run.id,
            run.incidentId,
            run.mode,
            run.testReplayId(),
            step.id,
            step.state));
  }

  /** Ends the run: its state and end, {@code RunFinished} and the {@code INFO} line. */
  void end(RunRow run, RunState state, EndReason reason, String detail, Instant now) {
    run.state = state;
    run.waitReason = null;
    run.endReason = reason;
    run.endDetail = detail;
    run.finishedAt = now;
    records.updateRun(run, now);
    publisher.publishEvent(
        new RunFinished(run.tenantId, run.id, run.incidentId, run.mode, run.testReplayId()));
    LOG.info("workflow run {} ended: {} ({})", run.id, state.key(), reason.key());
  }

  /**
   * DM-SM07-T5: cancels every unfinished live run of the incident with the reason, its current step
   * execution, that step's {@code pending} timer and whatever its handler created (AR-R-20).
   */
  void cancelIncidentRuns(TenantId tenantId, UUID incidentId, EndReason reason, Instant now) {
    for (RunRow run : records.lockUnfinishedLiveRuns(tenantId, incidentId)) {
      StepRow step =
          run.currentStepExecutionId == null
              ? null
              : records.step(tenantId, run.currentStepExecutionId).orElse(null);
      if (step != null
          && (step.state == StepExecutionState.RUNNING
              || step.state == StepExecutionState.WAITING)) {
        cancelStep(run, step, now);
        handlers.cancel(
            new StepCancel(
                tenantId, run.id, run.incidentId, step.id, new Key(step.stepId), step.stepType));
        stepChanged(run, step);
      }
      end(run, RunState.CANCELLED, reason, null, now);
    }
  }

  /** The step execution {@code cancelled} with its {@code pending} timer. */
  void cancelStep(RunRow run, StepRow step, Instant now) {
    records.cancelTimer(run.tenantId, step.id, now);
    step.state = StepExecutionState.CANCELLED;
    step.finishedAt = now;
    step.retryDueAt = null;
    records.updateStep(run.tenantId, step, now);
  }
}
