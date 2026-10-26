package vigil.workflows;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vigil.shared.Actor;
import vigil.shared.Ids;
import vigil.shared.TenantId;
import vigil.shared.Timestamps;
import vigil.shared.VigilError;
import vigil.workflows.api.VersionOrigin;
import vigil.workflows.api.WorkflowImport;
import vigil.workflows.api.WorkflowPart;

/**
 * The seed import of part 16: the workflow and its version 1 (DM-E-45, DM-E-47, DD-F-06), checked
 * as a whole (DM-R-57; P-41 spec Section 6.5). It writes no audit event and publishes no event.
 */
@Service
class WorkflowImporter implements WorkflowImport {

  private static final Actor SETUP = Actor.module("vigil-setup");

  private final WorkflowRecords records;
  private final TransactionTemplate tx;
  private final Clock clock;

  WorkflowImporter(
      WorkflowRecords records, PlatformTransactionManager transactionManager, Clock clock) {
    this.records = records;
    this.tx = new TransactionTemplate(transactionManager);
    this.clock = clock;
  }

  @Override
  public Map<String, UUID> importPart(
      TenantId tenantId, WorkflowPart part, Instant importStartedAt, Map<String, UUID> handles) {
    Workflows.require(tenantId, part, importStartedAt, handles);
    return tx.execute(
        status -> {
          if (records.workflow(tenantId).isPresent()) {
            throw refused(List.of("workflow already exists"));
          }
          WorkflowPartCheck.Result result = WorkflowPartCheck.check(part.workflowJson());
          if (!result.problems().isEmpty()) {
            throw refused(result.problems());
          }
          Instant now = Timestamps.now(clock);
          UUID workflowId = Ids.newId();
          UUID versionId = Ids.newId();
          records.insertWorkflow(tenantId, workflowId, result.name(), versionId, now);
          records.insertVersion(
              tenantId,
              versionId,
              workflowId,
              1,
              result.contentJson(),
              VersionOrigin.SEED,
              null,
              SETUP,
              "[]",
              now);
          return Map.of();
        });
  }

  private static VigilError refused(List<String> problems) {
    return new VigilError(
        VigilError.Code.INVALID, "Part workflows refused.", Map.of("problems", problems));
  }
}
