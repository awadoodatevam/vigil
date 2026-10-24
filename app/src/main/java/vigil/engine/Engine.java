package vigil.engine;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vigil.engine.EngineRecords.RunRow;
import vigil.engine.EngineRecords.StepRow;
import vigil.engine.api.EndReason;
import vigil.engine.api.EngineService;
import vigil.engine.api.RunMode;
import vigil.engine.api.RunStarted;
import vigil.engine.api.RunState;
import vigil.engine.api.RunView;
import vigil.engine.api.StepExecutionState;
import vigil.engine.api.StepExecutionView;
import vigil.shared.Actor;
import vigil.shared.Ids;
import vigil.shared.Key;
import vigil.shared.TenantId;
import vigil.shared.Timestamps;

/** The requests to vigil-engine (PM-M12; P-17 spec Section 6.5). */
@Service
class Engine implements EngineService {

  private static final Logger LOG = LoggerFactory.getLogger(Engine.class);
  private static final Actor WORKFLOWS = Actor.module("vigil-workflows");
  static final String NEXT = "next";

  private final EngineRecords records;
  private final Runs runs;
  private final HandOffs handOffs;
  private final ApplicationEventPublisher publisher;
  private final Clock clock;

  Engine(
      EngineRecords records,
      Runs runs,
      HandOffs handOffs,
      ApplicationEventPublisher publisher,
      Clock clock) {
    this.records = records;
    this.runs = runs;
    this.handOffs = handOffs;
    this.publisher = publisher;
    this.clock = clock;
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED)
  public Optional<UUID> startLiveRun(
      TenantId tenantId,
      Actor actor,
      UUID incidentId,
      long episode,
      UUID workflowVersionId,
      String contentJson,
      boolean reopen) {
    require(tenantId, actor, incidentId, workflowVersionId, contentJson);
    if (!actor.equals(WORKFLOWS)) {
      throw new IllegalArgumentException("a live run is started only by vigil-workflows");
    }
    if (episode < 1) {
      throw new IllegalArgumentException("the episode is at least 1");
    }
    RunContent content = RunContent.parse(contentJson);
    List<RunContent.Step> triggers = content.triggers();
    if (triggers.size() != 1) {
      throw new IllegalArgumentException("the content has not exactly one trigger step");
    }
    if (records.hasLiveRun(tenantId, incidentId, episode)) {
      return Optional.empty();
    }
    Instant now = Timestamps.now(clock);
    if (reopen) {
      runs.cancelIncidentRuns(tenantId, incidentId, EndReason.INCIDENT_REOPENED, now);
    } else if (!records.lockUnfinishedLiveRuns(tenantId, incidentId).isEmpty()) {
      throw new IllegalStateException("the incident has an unfinished live run");
    }
    RunContent.Step trigger = triggers.getFirst();
    RunRow run = new RunRow();
    run.tenantId = tenantId;
    run.id = Ids.newId();
    run.incidentId = incidentId;
    run.mode = RunMode.LIVE;
    run.episode = episode;
    run.workflowVersionId = workflowVersionId;
    run.content = contentJson;
    run.state = RunState.RUNNING;
    run.startedAt = now;
    StepRow step = new StepRow();
    step.id = Ids.newId();
    step.runId = run.id;
    step.stepId = new Key(trigger.stepId()).value();
    step.stepType = trigger.type();
    step.visit = 1;
    step.state = StepExecutionState.COMPLETED;
    step.startedAt = now;
    step.finishedAt = now;
    step.namedOutput = NEXT;
    step.evaluationCount = 1;
    run.currentStepExecutionId = step.id;
    run.nextStepId = content.target(step.stepId, NEXT).orElse(null);
    records.insertRun(run, now);
    records.insertStep(tenantId, step, now);
    publisher.publishEvent(new RunStarted(tenantId, run.id, incidentId, RunMode.LIVE, null));
    LOG.info("workflow run {} started for incident {} (episode {})", run.id, incidentId, episode);
    runs.stepChanged(run, step);
    if (run.nextStepId == null) {
      runs.end(run, RunState.FINISHED, EndReason.PATH_ENDED, null, now);
    } else {
      handOffs.afterCommit(tenantId, run.id);
    }
    return Optional.of(run.id);
  }

  @Override
  public Optional<RunView> run(TenantId tenantId, UUID runId) {
    require(tenantId, runId);
    return records.run(tenantId, runId).map(RunRow::view);
  }

  @Override
  public List<RunView> runs(TenantId tenantId, UUID incidentId) {
    require(tenantId, incidentId);
    return records.runsOfIncident(tenantId, incidentId).stream().map(RunRow::view).toList();
  }

  @Override
  public List<StepExecutionView> stepExecutions(TenantId tenantId, UUID runId) {
    require(tenantId, runId);
    return records.steps(tenantId, runId).stream().map(StepRow::view).toList();
  }

  @Override
  public boolean hasLiveRun(TenantId tenantId, UUID incidentId, long episode) {
    require(tenantId, incidentId);
    return records.hasLiveRun(tenantId, incidentId, episode);
  }

  private static void require(Object... arguments) {
    for (Object argument : arguments) {
      if (Objects.isNull(argument)) {
        throw new IllegalArgumentException("a required argument is missing");
      }
    }
  }
}
