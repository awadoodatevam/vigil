package vigil.workflows;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import vigil.shared.TenantId;
import vigil.workflows.api.StepTypeDefinition;
import vigil.workflows.api.WorkflowService;
import vigil.workflows.api.WorkflowVersionView;
import vigil.workflows.api.WorkflowView;

/** The reads of vigil-workflows (PM-M11; P-41 spec Section 6.4). */
@Service
class Workflows implements WorkflowService {

  private final WorkflowRecords records;

  Workflows(WorkflowRecords records) {
    this.records = records;
  }

  @Override
  public List<StepTypeDefinition> stepTypes() {
    return StepCatalogue.all();
  }

  @Override
  public Optional<WorkflowView> workflow(TenantId tenantId) {
    require(tenantId);
    return records.workflow(tenantId);
  }

  @Override
  public Optional<WorkflowVersionView> version(TenantId tenantId, UUID versionId) {
    require(tenantId, versionId);
    return records.version(tenantId, versionId);
  }

  @Override
  public Optional<WorkflowVersionView> activeVersion(TenantId tenantId) {
    require(tenantId);
    return records.activeVersion(tenantId);
  }

  static void require(Object... arguments) {
    for (Object argument : arguments) {
      if (argument == null) {
        throw new IllegalArgumentException("a required argument is missing");
      }
    }
  }
}
