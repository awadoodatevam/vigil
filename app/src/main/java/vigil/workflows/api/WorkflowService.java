package vigil.workflows.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vigil.shared.TenantId;

/** Workflow definitions (PM-M11). Every read filters by the tenant; every list is unmodifiable. */
public interface WorkflowService {

  /** The step type catalogue (DM-E-44, PM-M11-02), in the order of DM Section 9. */
  List<StepTypeDefinition> stepTypes();

  Optional<WorkflowView> workflow(TenantId tenantId);

  Optional<WorkflowVersionView> version(TenantId tenantId, UUID versionId);

  /** The version the workflow's active version marker names. */
  Optional<WorkflowVersionView> activeVersion(TenantId tenantId);
}
