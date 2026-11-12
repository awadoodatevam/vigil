package vigil.actions;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import vigil.actions.ActionRecords.Execution;
import vigil.actions.ActionRecords.ExecutionState;
import vigil.actions.ActionRecords.Proposal;
import vigil.actions.api.ApprovalRequestView;
import vigil.actions.api.RegisteredActionView;
import vigil.actions.api.RequestState;
import vigil.actions.api.StateCheck;
import vigil.audit.api.AuditEntry;
import vigil.audit.api.AuditEventType;
import vigil.audit.api.AuditService;
import vigil.incidents.api.DataCategory;
import vigil.incidents.api.EvidenceKind;
import vigil.incidents.api.IncidentService;
import vigil.incidents.api.IncidentState;
import vigil.incidents.api.IncidentView;
import vigil.runner.api.OperationRunView;
import vigil.runner.api.OperationView;
import vigil.runner.api.PermissionKind;
import vigil.runner.api.RunRequest;
import vigil.runner.api.RunnerService;
import vigil.servicemap.api.ServiceMapService;
import vigil.shared.Completed;
import vigil.shared.Completed.ResultRef;
import vigil.shared.Ids;
import vigil.shared.Key;
import vigil.shared.StepCall;
import vigil.shared.TenantId;
import vigil.shared.Timestamps;
import vigil.shared.TransactionGuard;

/**
 * The Action step (DM-S-06, DM-R-32, DM-R-33, DM-SM-12; P-24 spec Section 6.8): one execution per
 * step execution (DM-R-64), recorded before any runner request (DM-E60-R1), continued from its
 * stored state by {@code start}, {@code evaluate} and the engine's {@code reconcile} alike. The
 * step never waits.
 */
@Component
class ActionStep {

  static final Key SUCCEEDED = new Key("succeeded");
  static final Key FAILED = new Key("failed");
  static final Duration RECONCILE_SPACING = Duration.ofSeconds(10);
  static final int RECONCILE_ATTEMPTS = 3;
  private static final Logger LOG = LoggerFactory.getLogger(ActionStep.class);
  private static final int DETAIL_MAX = 300;

  private final ActionRecords records;
  private final StateChecks stateChecks;
  private final RunnerService runner;
  private final IncidentService incidents;
  private final ServiceMapService serviceMap;
  private final ApprovalStep approvals;
  private final AuditService audit;
  private final TransactionTemplate tx;
  private final Clock clock;

  ActionStep(
      ActionRecords records,
      StateChecks stateChecks,
      RunnerService runner,
      IncidentService incidents,
      ServiceMapService serviceMap,
      ApprovalStep approvals,
      AuditService audit,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.records = records;
    this.stateChecks = stateChecks;
    this.runner = runner;
    this.incidents = incidents;
    this.serviceMap = serviceMap;
    this.approvals = approvals;
    this.audit = audit;
    this.tx = new TransactionTemplate(transactionManager);
    this.clock = clock;
  }

  /** The procedure of Section 6.8, from the execution's stored state. */
  Completed run(StepCall call) {
    TransactionGuard.assertNoTransaction();
    if (call.stepType() != StepCall.StepType.ACTION) {
      throw new IllegalArgumentException("action step parameters");
    }
    TenantId tenantId = call.tenantId();
    Execution execution = records.executionOfStep(tenantId, call.stepExecutionId()).orElse(null);
    if (execution == null) {
      execution = tx.execute(status -> record(call));
    }
    Proposal proposal = records.proposal(tenantId, execution.proposalId).orElseThrow();
    if (execution.state == ExecutionState.RECORDED
        || execution.state == ExecutionState.RECHECKING) {
      recheck(tenantId, execution, proposal);
    } else if (execution.state == ExecutionState.EXECUTING) {
      execution.state = ExecutionState.RECONCILING;
      store(tenantId, execution);
    }
    if (execution.state == ExecutionState.RECONCILING) {
      reconcile(tenantId, execution, proposal);
    }
    ResultRef ref = new ResultRef("vigil-actions", "ActionExecution", execution.id);
    return new Completed(execution.state == ExecutionState.SUCCEEDED ? SUCCEEDED : FAILED, ref);
  }

  /** Step 1 (DM-SM12-T1), in one transaction committed before any runner request. */
  private Execution record(StepCall call) {
    TenantId tenantId = call.tenantId();
    Proposal proposal =
        records
            .proposalOfRun(tenantId, call.runId())
            .orElseThrow(
                () -> new IllegalStateException("no action proposal in run " + call.runId()));
    Instant since =
        records.lastExecuted(tenantId, call.runId()).map(e -> e.executingAt).orElse(null);
    List<UUID> relied =
        records.requestsOfRun(tenantId, call.runId()).stream()
            .filter(r -> r.proposalId().equals(proposal.id()))
            .filter(
                r -> r.state() == RequestState.APPROVED || r.state() == RequestState.PREAUTHORIZED)
            .filter(r -> since == null || r.createdAt().isAfter(since))
            .map(ApprovalRequestView::id)
            .toList();
    Execution execution =
        new Execution(
            Ids.newId(),
            call.incidentId(),
            call.runId(),
            call.stepExecutionId(),
            proposal.id(),
            proposal.actionKey(),
            relied,
            records.executedCount(tenantId, call.incidentId(), proposal.actionKey()) + 1,
            Timestamps.now(clock));
    records.insertExecution(tenantId, execution);
    return execution;
  }

  /** Step 2: the four rechecks in order, stopping at the first that fails; then step 3. */
  private void recheck(TenantId tenantId, Execution execution, Proposal proposal) {
    execution.state = ExecutionState.RECHECKING;
    execution.recheckPass++;
    execution.recheckJson = null;
    store(tenantId, execution);
    JsonNode action = ActionRecords.JSON.readTree(proposal.actionSnapshotJson());
    ArrayNode recheck = ActionRecords.JSON.createArrayNode();
    for (String check : List.of("approval_valid", "unchanged", "incident_open", "precondition")) {
      Result result =
          switch (check) {
            case "approval_valid" -> approvalValid(tenantId, execution);
            case "unchanged" -> unchanged(tenantId, execution, proposal, action);
            case "incident_open" -> incidentOpen(tenantId, execution);
            default -> precondition(tenantId, execution, proposal);
          };
      recheck
          .addObject()
          .put("check", check)
          .put("passed", result.passed())
          .put("detail", cut(result.detail()));
      execution.recheckJson = ActionRecords.JSON.writeValueAsString(recheck);
      store(tenantId, execution);
      if (!result.passed()) {
        execution.state = ExecutionState.NOT_EXECUTED;
        tx.executeWithoutResult(status -> end(tenantId, execution, proposal, check));
        return;
      }
    }
    execute(tenantId, execution, proposal, action);
  }

  private record Result(boolean passed, String detail) {}

  private Result approvalValid(TenantId tenantId, Execution execution) {
    Instant now = Timestamps.now(clock);
    List<String> names = new ArrayList<>();
    Instant earliest = null;
    boolean valid = !execution.approvalRequestIds.isEmpty();
    for (UUID id : execution.approvalRequestIds) {
      ApprovalRequestView request = records.request(tenantId, id).orElseThrow();
      boolean usable =
          request.state() == RequestState.APPROVED || request.state() == RequestState.PREAUTHORIZED;
      valid = valid && usable && now.isBefore(request.expiresAt());
      names.add(
          request.decidedBy() == null
              ? ActionTexts.POLICY_APPROVER
              : approvals.userName(tenantId, request.decidedBy()));
      earliest =
          earliest == null || request.expiresAt().isBefore(earliest)
              ? request.expiresAt()
              : earliest;
    }
    return new Result(valid, ActionTexts.approvalValid(names, earliest));
  }

  private Result unchanged(
      TenantId tenantId, Execution execution, Proposal proposal, JsonNode action) {
    List<String> parts = new ArrayList<>();
    Optional<RegisteredActionView> current =
        records.action(tenantId, proposal.actionKey()).filter(RegisteredActionView::active);
    if (current.isEmpty()
        || !ActionRecords.JSON
            .readTree(ActionRecords.JSON.writeValueAsString(Snapshots.action(current.get())))
            .equals(action)) {
      parts.add("action");
    }
    String component = incident(tenantId, execution).hypothesisComponentKey();
    if (!proposal.componentKey().equals(component)) {
      parts.add("hypothesis component");
    }
    Optional<String> target =
        component == null ? Optional.empty() : serviceMap.target(tenantId, component);
    if (!target.equals(Optional.of(proposal.targetKey()))) {
      parts.add("target");
    }
    return new Result(parts.isEmpty(), ActionTexts.unchanged(parts));
  }

  private Result incidentOpen(TenantId tenantId, Execution execution) {
    IncidentState state = incident(tenantId, execution).state();
    return new Result(state == IncidentState.OPEN, ActionTexts.incidentState(state));
  }

  private Result precondition(TenantId tenantId, Execution execution, Proposal proposal) {
    StateCheck check =
        StateChecks.read(
            ActionRecords.JSON.writeValueAsString(
                ActionRecords.JSON.readTree(proposal.actionSnapshotJson()).get("precondition")));
    StateChecks.Outcome outcome =
        stateChecks.check(
            tenantId,
            execution.id + ":precondition:" + execution.recheckPass,
            check,
            proposal.targetKey());
    String operationName =
        runner
            .operation(tenantId, check.operationKey())
            .map(OperationView::name)
            .orElse(check.operationKey());
    return new Result(
        Boolean.TRUE.equals(outcome.result()),
        ActionTexts.precondition(
            operationName,
            ActionRecords.JSON.readTree(check.parametersJson()),
            outcome.value(),
            check.operator(),
            check.value()));
  }

  /** Step 3 (DM-SM12-T4 to T7): {@code executing} committed, the runner call, its result. */
  private void execute(TenantId tenantId, Execution execution, Proposal proposal, JsonNode action) {
    execution.state = ExecutionState.EXECUTING;
    execution.executingAt = Timestamps.now(clock);
    store(tenantId, execution);
    OperationRunView run =
        runner.run(
            tenantId,
            ApprovalStep.ACTIONS,
            new RunRequest(
                execution.id + ":execute",
                PermissionKind.ACTION,
                action.get("operation_key").stringValue(),
                proposal.targetKey(),
                ActionRecords.JSON.writeValueAsString(action.get("parameter_values"))));
    execution.operationRunId = run.id();
    execution.state =
        switch (run.state()) {
          case SUCCEEDED -> ExecutionState.SUCCEEDED;
          case FAILED, REFUSED -> ExecutionState.FAILED;
          case UNKNOWN, RUNNING -> ExecutionState.RECONCILING;
        };
    if (execution.state == ExecutionState.RECONCILING) {
      store(tenantId, execution);
    } else {
      tx.executeWithoutResult(status -> end(tenantId, execution, proposal, null));
    }
  }

  /** Step 4 (DM-R-33, AR-TM-06): three reconciliation attempts, 10 s apart. */
  private void reconcile(TenantId tenantId, Execution execution, Proposal proposal) {
    StateCheck check =
        records.action(tenantId, proposal.actionKey()).orElseThrow().reconciliation();
    while (execution.state == ExecutionState.RECONCILING) {
      int n = execution.reconciliationRunIds.size() + 1;
      StateChecks.Outcome outcome;
      if (n > RECONCILE_ATTEMPTS) {
        outcome =
            runner
                .operationRun(tenantId, execution.reconciliationRunIds.get(RECONCILE_ATTEMPTS - 1))
                .map(third -> StateChecks.outcome(third, check))
                .orElse(new StateChecks.Outcome(null, null, null));
      } else {
        outcome =
            stateChecks.check(
                tenantId, execution.id + ":reconcile:" + n, check, proposal.targetKey());
        List<UUID> runs = new ArrayList<>(execution.reconciliationRunIds);
        runs.add(outcome.runId());
        execution.reconciliationRunIds = List.copyOf(runs);
      }
      if (outcome.result() != null) {
        execution.state = outcome.result() ? ExecutionState.SUCCEEDED : ExecutionState.FAILED;
      } else if (n >= RECONCILE_ATTEMPTS) {
        execution.state = ExecutionState.UNRESOLVED;
      }
      if (execution.state != ExecutionState.RECONCILING) {
        tx.executeWithoutResult(status -> end(tenantId, execution, proposal, null));
        return;
      }
      store(tenantId, execution);
      try {
        Thread.sleep(RECONCILE_SPACING);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("reconciliation interrupted", e);
      }
    }
  }

  /**
   * Step 5, in the caller's transaction: the final state with {@code finished_at}, its evidence
   * entry and its audit event; {@code failedRecheck} for {@code not_executed}.
   */
  private void end(
      TenantId tenantId, Execution execution, Proposal proposal, String failedRecheck) {
    Instant now = Timestamps.now(clock);
    boolean reconciled =
        records.executionOfStep(tenantId, execution.stepExecutionId).orElseThrow().state
            == ExecutionState.RECONCILING;
    execution.finishedAt = now;
    records.updateExecution(tenantId, execution, now);
    JsonNode action = ActionRecords.JSON.readTree(proposal.actionSnapshotJson());
    String actionName = action.get("name").stringValue();
    String operationKey = action.get("operation_key").stringValue();
    String targetName = approvals.targetName(tenantId, proposal.targetKey());
    String outputs =
        execution.operationRunId == null
            ? null
            : runner
                .operationRun(tenantId, execution.operationRunId)
                .map(OperationRunView::outputValuesJson)
                .orElse(null);
    boolean removedFiles =
        outputs != null
            && runner
                .operation(tenantId, operationKey)
                .map(o -> "removed_files".equals(o.outputParser()))
                .orElse(false);
    ObjectNode content = ActionRecords.JSON.createObjectNode();
    content.put("action_execution_id", execution.id.toString());
    content.put("state", execution.state.key());
    content.put("attempt_number", execution.attemptNumber);
    content.put("action_key", proposal.actionKey());
    content.put("target_key", proposal.targetKey());
    content.put("operation_key", operationKey);
    content.put(
        "operation_run_id",
        execution.operationRunId == null ? null : execution.operationRunId.toString());
    content.set("output_values", json(outputs));
    content.set("recheck", json(execution.recheckJson));
    incidents.addEvidence(
        tenantId,
        ApprovalStep.ACTIONS,
        execution.incidentId,
        EvidenceKind.ACTION_EXECUTION,
        execution.id,
        ActionTexts.executionSource(proposal.targetKey(), operationKey),
        now,
        ActionTexts.executionSummary(
            execution.state,
            actionName,
            targetName,
            removedFiles ? ActionRecords.JSON.readTree(outputs) : null,
            failedRecheck),
        ActionRecords.JSON.writeValueAsString(content),
        DataCategory.INCIDENT_RECORD);
    ObjectNode details = ActionRecords.JSON.createObjectNode();
    details.put("attempt_number", execution.attemptNumber);
    details.put("outcome", execution.state.key());
    audit.append(
        new AuditEntry(
            tenantId,
            now,
            ApprovalStep.ACTIONS,
            reconciled ? AuditEventType.ACTION_RECONCILED : AuditEventType.ACTION_EXECUTED,
            "ActionExecution",
            execution.id,
            execution.incidentId,
            reconciled
                ? ActionTexts.actionReconciledAudit(
                    actionName, targetName, execution.attemptNumber, execution.state)
                : ActionTexts.actionExecutedAudit(
                    actionName, targetName, execution.attemptNumber, execution.state),
            ActionRecords.JSON.writeValueAsString(details)));
    if (execution.state != ExecutionState.SUCCEEDED) {
      LOG.warn(
          "action execution {} of incident {} of tenant {}: {}",
          execution.id,
          execution.incidentId,
          tenantId.value(),
          execution.state.key());
    }
  }

  /** Stores the execution's changeable columns in their own transaction. */
  private void store(TenantId tenantId, Execution execution) {
    tx.executeWithoutResult(
        status -> records.updateExecution(tenantId, execution, Timestamps.now(clock)));
  }

  private IncidentView incident(TenantId tenantId, Execution execution) {
    return incidents
        .incident(tenantId, execution.incidentId)
        .orElseThrow(
            () -> new IllegalStateException("incident " + execution.incidentId + " not found"));
  }

  /** The JSON text as a tree; JSON {@code null} for none. */
  private static JsonNode json(String text) {
    return text == null ? ActionRecords.JSON.nullNode() : ActionRecords.JSON.readTree(text);
  }

  private static String cut(String text) {
    return text.codePointCount(0, text.length()) <= DETAIL_MAX
        ? text
        : text.substring(0, text.offsetByCodePoints(0, DETAIL_MAX));
  }
}
