package vigil.setup;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vigil.shared.TenantId;
import vigil.workflows.api.WorkflowImport;
import vigil.workflows.api.WorkflowPart;

/**
 * Seed part 16, workflows, imported through {@link WorkflowImport} (DM-R-56 (16)): the section
 * {@code workflow} of {@code 06-workflow.yaml} passed as JSON text; vigil-workflows checks it.
 */
@Component
class WorkflowsAdapter implements SeedPartImporter {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String SECTION = "workflow";

  private final WorkflowImport workflowImport;

  WorkflowsAdapter(WorkflowImport workflowImport) {
    this.workflowImport = workflowImport;
  }

  @Override
  public int part() {
    return 16;
  }

  @Override
  public PartResult importPart(
      TenantId tenantId,
      SeedBundle bundle,
      Map<String, JsonNode> files,
      Instant importStartedAt,
      Map<String, UUID> handles) {
    SeedShapes shapes = new SeedShapes();
    JsonNode section = shapes.section(files.get(SeedParts.WORKFLOW), SECTION);
    shapes.refuseIfAny(SeedParts.part(part()).name());
    workflowImport.importPart(
        tenantId, new WorkflowPart(JSON.writeValueAsString(section)), importStartedAt, handles);
    Map<String, Integer> counts = new LinkedHashMap<>();
    counts.put("workflows", 1);
    counts.put("workflow_versions", 1);
    return new PartResult(counts, Map.of());
  }
}
