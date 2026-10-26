package vigil.diagnostics;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import vigil.diagnostics.api.DiagnosticDefinitionView;
import vigil.diagnostics.api.DiagnosticOutcome;
import vigil.diagnostics.api.DiagnosticResultView;
import vigil.diagnostics.api.DiagnosticsStepHandler;
import vigil.incidents.api.DataCategory;
import vigil.incidents.api.EvidenceKind;
import vigil.incidents.api.IncidentService;
import vigil.incidents.api.IncidentView;
import vigil.runner.api.OperationRunState;
import vigil.runner.api.OperationRunView;
import vigil.runner.api.PermissionKind;
import vigil.runner.api.RunRequest;
import vigil.runner.api.RunnerService;
import vigil.servicemap.api.ServiceMapService;
import vigil.shared.Actor;
import vigil.shared.Completed;
import vigil.shared.Ids;
import vigil.shared.Key;
import vigil.shared.StepAnswer;
import vigil.shared.StepCall;
import vigil.shared.StepCancel;
import vigil.shared.TenantId;
import vigil.shared.Timestamps;
import vigil.shared.TransactionGuard;

/**
 * The Diagnostic step (DM-S-03, PM-M13-02, PM-M13-03; P-18 spec Section 6.9): each definition of
 * the step runs through the runner with diagnostic permission on the target of the hypothesis
 * component; each result is stored with an evidence entry for a succeeded one (DC-TX-03). A
 * definition that already has a result for the step execution is not run again (DM-R-64).
 */
@Component
class DiagnosticStep implements DiagnosticsStepHandler {

  private static final Actor DIAGNOSTICS = Actor.module("vigil-diagnostics");
  private static final Key DONE = new Key("done");
  private static final Key FAILED = new Key("failed");
  private static final int ERROR_MAX = 500;
  private static final String BAD_PARAMETERS =
      "diagnostic step parameters: diagnostics and target hypothesis_component";

  private final DiagnosticRecords records;
  private final RunnerService runner;
  private final ServiceMapService serviceMap;
  private final IncidentService incidents;
  private final TransactionTemplate tx;
  private final Clock clock;

  DiagnosticStep(
      DiagnosticRecords records,
      RunnerService runner,
      ServiceMapService serviceMap,
      IncidentService incidents,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.records = records;
    this.runner = runner;
    this.serviceMap = serviceMap;
    this.incidents = incidents;
    this.tx = new TransactionTemplate(transactionManager);
    this.clock = clock;
  }

  @Override
  public StepAnswer start(StepCall call) {
    return diagnose(call);
  }

  @Override
  public StepAnswer evaluate(StepCall call) {
    return diagnose(call);
  }

  /** The step leaves nothing open (AR-R-21). */
  @Override
  public void cancel(StepCancel cancel) {
    // Nothing to cancel.
  }

  private StepAnswer diagnose(StepCall call) {
    TransactionGuard.assertNoTransaction();
    Diagnostics.require(call);
    if (call.stepType() != StepCall.StepType.DIAGNOSTIC) {
      throw new IllegalArgumentException("not a diagnostic step: " + call.stepType().key());
    }
    List<String> keys = diagnosticKeys(call.parameters());
    TenantId tenantId = call.tenantId();
    IncidentView incident =
        incidents
            .incident(tenantId, call.incidentId())
            .orElseThrow(
                () -> new IllegalStateException("incident " + call.incidentId() + " not found"));
    String component = incident.hypothesisComponentKey();
    if (component == null) {
      return new Completed(FAILED, null);
    }
    Optional<String> target = serviceMap.target(tenantId, component);
    Map<String, DiagnosticResultView> stored = byKey(tenantId, call.stepExecutionId());
    for (String key : keys) {
      if (!stored.containsKey(key)) {
        diagnose(call, key, component, target);
      }
    }
    Map<String, DiagnosticResultView> results = byKey(tenantId, call.stepExecutionId());
    boolean done =
        keys.stream()
            .allMatch(
                key ->
                    results.containsKey(key)
                        && results.get(key).outcome() == DiagnosticOutcome.SUCCEEDED);
    return new Completed(done ? DONE : FAILED, null);
  }

  /** Runs one definition and stores its result, with its evidence entry when it succeeded. */
  private void diagnose(StepCall call, String key, String component, Optional<String> target) {
    TenantId tenantId = call.tenantId();
    Optional<DiagnosticDefinitionView> definition =
        records.definition(tenantId, key).filter(DiagnosticDefinitionView::active);
    if (definition.isEmpty()) {
      store(
          call,
          null,
          failed(
              call,
              key,
              component,
              target.orElse(null),
              null,
              Timestamps.now(clock),
              "diagnostic " + key + " is not an active diagnostic definition"));
      return;
    }
    if (target.isEmpty()) {
      store(
          call,
          null,
          failed(
              call,
              key,
              component,
              null,
              null,
              Timestamps.now(clock),
              "component " + component + " has no target"));
      return;
    }
    OperationRunView run =
        runner.run(
            tenantId,
            DIAGNOSTICS,
            new RunRequest(
                call.stepExecutionId() + ":" + key + ":" + call.evaluationCount(),
                PermissionKind.DIAGNOSTIC,
                definition.get().operationKey(),
                target.get(),
                definition.get().parameterValuesJson()));
    if (run.state() == OperationRunState.RUNNING) {
      throw new IllegalStateException("operation run " + run.id() + " is still running");
    }
    if (run.state() == OperationRunState.SUCCEEDED) {
      store(
          call,
          definition.get(),
          new DiagnosticResultView(
              Ids.newId(),
              call.incidentId(),
              call.runId(),
              call.stepExecutionId(),
              key,
              component,
              target.get(),
              run.id(),
              DiagnosticOutcome.SUCCEEDED,
              run.outputValuesJson(),
              run.finishedAt(),
              null));
    } else {
      store(
          call,
          null,
          failed(
              call,
              key,
              component,
              target.get(),
              run.id(),
              run.finishedAt(),
              cut(String.valueOf(run.error()))));
    }
  }

  private static DiagnosticResultView failed(
      StepCall call,
      String key,
      String component,
      String target,
      UUID operationRunId,
      Instant collectedAt,
      String error) {
    return new DiagnosticResultView(
        Ids.newId(),
        call.incidentId(),
        call.runId(),
        call.stepExecutionId(),
        key,
        component,
        target,
        operationRunId,
        DiagnosticOutcome.FAILED,
        null,
        collectedAt,
        error);
  }

  /**
   * Stores the result in its own transaction; a succeeded result with its evidence entry. Nothing
   * is written when the step execution already has a result for the key.
   */
  private void store(
      StepCall call, DiagnosticDefinitionView definition, DiagnosticResultView result) {
    TenantId tenantId = call.tenantId();
    tx.executeWithoutResult(
        status -> {
          if (!records.insertResult(tenantId, result, Timestamps.now(clock))
              || result.outcome() != DiagnosticOutcome.SUCCEEDED) {
            return;
          }
          JsonNode values = DiagnosticRecords.JSON.readTree(result.valuesJson());
          ObjectNode content = DiagnosticRecords.JSON.createObjectNode();
          content.put("diagnostic_key", result.diagnosticKey());
          content.put("diagnostic_name", definition.name());
          content.put("component_key", result.componentKey());
          content.put("target_key", result.targetKey());
          content.put("operation_key", definition.operationKey());
          content.put("operation_run_id", result.operationRunId().toString());
          content.set("values", values);
          incidents.addEvidence(
              tenantId,
              DIAGNOSTICS,
              call.incidentId(),
              EvidenceKind.DIAGNOSTIC_RESULT,
              result.id(),
              result.targetKey() + " via runner, operation " + definition.operationKey(),
              result.collectedAt(),
              Summaries.fill(definition.summaryTemplate(), values),
              DiagnosticRecords.JSON.writeValueAsString(content),
              DataCategory.DIAGNOSTIC_OUTPUT);
        });
  }

  private Map<String, DiagnosticResultView> byKey(TenantId tenantId, UUID stepExecutionId) {
    Map<String, DiagnosticResultView> results = new LinkedHashMap<>();
    for (DiagnosticResultView result : records.resultsOfStep(tenantId, stepExecutionId)) {
      results.put(result.diagnosticKey(), result);
    }
    return results;
  }

  /** The keys of the parameter {@code diagnostics}; the parameters as seed part 16 stores them. */
  private static List<String> diagnosticKeys(String parameters) {
    JsonNode tree;
    try {
      tree = DiagnosticRecords.JSON.readTree(parameters);
    } catch (JacksonException e) {
      throw new IllegalArgumentException(BAD_PARAMETERS, e);
    }
    JsonNode diagnostics = tree == null ? null : tree.get("diagnostics");
    JsonNode target = tree == null ? null : tree.get("target");
    if (diagnostics == null
        || !diagnostics.isArray()
        || diagnostics.isEmpty()
        || target == null
        || !target.isString()
        || !target.stringValue().equals("hypothesis_component")) {
      throw new IllegalArgumentException(BAD_PARAMETERS);
    }
    List<String> keys = new ArrayList<>();
    for (JsonNode key : diagnostics) {
      if (!key.isString() || !DiagnosticsImporter.KEY.matcher(key.stringValue()).matches()) {
        throw new IllegalArgumentException(BAD_PARAMETERS);
      }
      keys.add(key.stringValue());
    }
    return keys;
  }

  private static String cut(String text) {
    return text.codePointCount(0, text.length()) <= ERROR_MAX
        ? text
        : text.substring(0, text.offsetByCodePoints(0, ERROR_MAX));
  }
}
