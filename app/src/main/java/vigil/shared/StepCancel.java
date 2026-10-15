package vigil.shared;

import java.util.Objects;
import java.util.UUID;

/** Cancels whatever a step has created that is still open (AR-R-21). */
public record StepCancel(
    TenantId tenantId,
    UUID runId,
    UUID incidentId,
    UUID stepExecutionId,
    Key stepId,
    StepCall.StepType stepType) {

  public StepCancel {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(runId, "runId");
    Objects.requireNonNull(incidentId, "incidentId");
    Objects.requireNonNull(stepExecutionId, "stepExecutionId");
    Objects.requireNonNull(stepId, "stepId");
    Objects.requireNonNull(stepType, "stepType");
  }
}
