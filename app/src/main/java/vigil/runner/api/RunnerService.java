package vigil.runner.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vigil.shared.Actor;
import vigil.shared.TenantId;

/** The requests of other modules to vigil-runner (PM-M05). */
public interface RunnerService {

  /**
   * Runs a registered operation on a target with the request's permission (PM-M05-01 to PM-M05-03);
   * a request id that exists returns the earlier run (DM-E14-R1).
   *
   * <p>external call: not inside a transaction
   */
  OperationRunView run(TenantId tenantId, Actor requester, RunRequest request);

  Optional<OperationView> operation(TenantId tenantId, String operationKey);

  /** Every grant, by {@code target_key}, then permission kind key, then id. */
  List<GrantView> grants(TenantId tenantId);

  Optional<OperationRunView> operationRun(TenantId tenantId, UUID id);

  /** The problems the run would refuse the parameters with (DM-V-06); runs nothing. */
  List<String> parameterProblems(TenantId tenantId, String operationKey, String parametersJson);
}
