package vigil.engine.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vigil.shared.Actor;
import vigil.shared.TenantId;

/** Workflow runs (PM-M12). Every read filters by the tenant; every list is unmodifiable. */
public interface EngineService {

  /**
   * DM-SM07-T1: starts a live run of the incident's episode on its own copy of {@code contentJson},
   * from vigil-workflows only (DM-E50-R1); empty when the episode already has a live run. With
   * {@code reopen}, every unfinished live run of the incident is cancelled first in the same
   * transaction (DM-R-22); without it, an unfinished live run of the incident is an {@code
   * IllegalStateException}.
   */
  Optional<UUID> startLiveRun(
      TenantId tenantId,
      Actor actor,
      UUID incidentId,
      long episode,
      UUID workflowVersionId,
      String contentJson,
      boolean reopen);

  Optional<RunView> run(TenantId tenantId, UUID runId);

  /** The incident's runs, by ascending {@code started_at}, then id. */
  List<RunView> runs(TenantId tenantId, UUID incidentId);

  /** The run's step executions, by ascending {@code started_at}, then id. */
  List<StepExecutionView> stepExecutions(TenantId tenantId, UUID runId);

  /** Whether the incident's episode has a live run in any state. */
  boolean hasLiveRun(TenantId tenantId, UUID incidentId, long episode);
}
