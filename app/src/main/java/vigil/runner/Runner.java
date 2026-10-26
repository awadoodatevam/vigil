package vigil.runner;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import vigil.connectors.api.ConnectorInstanceView;
import vigil.connectors.api.ConnectorService;
import vigil.connectors.api.TargetConnector;
import vigil.connectors.api.TargetResult;
import vigil.runner.RunnerRecords.NewRun;
import vigil.runner.RunnerRecords.RunEnd;
import vigil.runner.api.GrantView;
import vigil.runner.api.OperationRunState;
import vigil.runner.api.OperationRunView;
import vigil.runner.api.OperationView;
import vigil.runner.api.PermissionKind;
import vigil.runner.api.RunRequest;
import vigil.runner.api.RunnerRestart;
import vigil.runner.api.RunnerService;
import vigil.shared.Actor;
import vigil.shared.Ids;
import vigil.shared.TenantId;
import vigil.shared.Timestamps;
import vigil.shared.TransactionGuard;
import vigil.tenancy.api.TenantService;

/**
 * Operation runs (DM-E-14, DM-SM-10; P-18 spec Section 6.6): the run is recorded before the
 * external call and its result after it (AR-R-09); and restart step (1) (AR-R-24).
 */
@Service
class Runner implements RunnerService, RunnerRestart {

  private static final Logger LOG = LoggerFactory.getLogger(Runner.class);
  private static final int REQUEST_ID_MAX = 150;
  private static final int ERROR_MAX = 1_000;
  private static final String DIAGNOSTICS = "vigil-diagnostics";
  private static final String NUL = "\u0000";

  private final RunnerRecords records;
  private final ConnectorService connectors;
  private final TargetConnectors targetConnectors;
  private final TenantService tenants;
  private final TransactionTemplate tx;
  private final Clock clock;

  /** The run stored by step 2; {@code ended} when nothing else happens. */
  private record Started(
      OperationRunView run,
      boolean ended,
      OperationView operation,
      GrantView grant,
      ConnectorInstanceView instance,
      JsonNode parameters) {}

  Runner(
      RunnerRecords records,
      ConnectorService connectors,
      TargetConnectors targetConnectors,
      TenantService tenants,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.records = records;
    this.connectors = connectors;
    this.targetConnectors = targetConnectors;
    this.tenants = tenants;
    this.tx = new TransactionTemplate(transactionManager);
    this.clock = clock;
  }

  @Override
  public OperationRunView run(TenantId tenantId, Actor requester, RunRequest request) {
    TransactionGuard.assertNoTransaction();
    require(tenantId, requester, request);
    require(
        request.requestId(),
        request.permissionKind(),
        request.operationKey(),
        request.targetKey(),
        request.parametersJson());
    if (requester.kind() != Actor.Kind.MODULE) {
      throw new IllegalArgumentException("the requester must be a module");
    }
    String requestId = request.requestId();
    int length = requestId.codePointCount(0, requestId.length());
    if (length < 1 || length > REQUEST_ID_MAX || requestId.contains(NUL)) {
      throw new IllegalArgumentException("requestId: 1 to 150 characters without U+0000");
    }
    if (!Parameters.KEY.matcher(request.operationKey()).matches()) {
      throw new IllegalArgumentException("operationKey: not a key");
    }
    if (!Parameters.KEY.matcher(request.targetKey()).matches()) {
      throw new IllegalArgumentException("targetKey: not a key");
    }
    if (requester.module().equals(DIAGNOSTICS)
        && request.permissionKind() != PermissionKind.DIAGNOSTIC) {
      throw new IllegalArgumentException("vigil-diagnostics runs diagnostic operations only");
    }
    JsonNode parameters = Parameters.readOrNull(request.parametersJson());
    if (parameters != null && parameters.isObject()) {
      for (String name : parameters.propertyNames()) {
        if (name.contains(NUL)) {
          throw new IllegalArgumentException("parameters: a name holds U+0000");
        }
      }
    }
    Started started = tx.execute(status -> start(tenantId, requester, request, parameters));
    if (started.ended()) {
      return started.run();
    }
    TargetResult result = call(tenantId, request, started);
    return tx.execute(status -> end(tenantId, started, result));
  }

  /** Step 2: the earlier run of the request id, a refused run, or a {@code running} run. */
  private Started start(
      TenantId tenantId, Actor requester, RunRequest request, JsonNode parameters) {
    Instant now = Timestamps.now(clock);
    Optional<OperationRunView> earlier = records.runByRequest(tenantId, request.requestId());
    if (earlier.isPresent()) {
      return new Started(earlier.get(), true, null, null, null, null);
    }
    String key = request.operationKey();
    Optional<OperationView> operation = records.operation(tenantId, key);
    if (operation.isPresent() && operation.get().permissionKind() != request.permissionKind()) {
      throw new IllegalArgumentException(
          "operation "
              + key
              + (request.permissionKind() == PermissionKind.ACTION
                  ? " is not an action operation"
                  : " is not a diagnostic operation"));
    }
    String refusal = null;
    Optional<GrantView> grant = Optional.empty();
    if (operation.isEmpty() || !operation.get().active()) {
      refusal = "operation " + key + " is not an active operation";
    } else {
      List<String> problems =
          Parameters.valueProblems(
              key,
              Parameters.JSON.readTree(operation.get().parametersJson()),
              request.parametersJson());
      if (!problems.isEmpty()) {
        refusal = String.join("; ", problems);
      } else {
        PermissionKind kind = operation.get().permissionKind();
        grant = records.activeGrant(tenantId, request.targetKey(), kind);
        if (grant.isEmpty()) {
          refusal = "target " + request.targetKey() + " has no active " + kind.key() + " grant";
        }
      }
    }
    UUID id = Ids.newId();
    records.insertRun(
        tenantId,
        new NewRun(
            id,
            key,
            request.targetKey(),
            operation.map(OperationView::permissionKind).orElse(request.permissionKind()),
            storedParameters(parameters),
            requester.module(),
            request.requestId(),
            refusal == null ? OperationRunState.RUNNING : OperationRunState.REFUSED,
            now,
            refusal == null ? null : now,
            refusal == null ? null : cut(refusal, ERROR_MAX)),
        now);
    OperationRunView stored = records.runByRequest(tenantId, request.requestId()).orElseThrow();
    if (!stored.id().equals(id)) {
      return new Started(stored, true, null, null, null, null);
    }
    if (refusal != null) {
      logEnded(tenantId, stored);
      return new Started(stored, true, null, null, null, null);
    }
    ConnectorInstanceView instance =
        connectors.instance(tenantId, grant.get().connectorInstanceId()).orElse(null);
    return new Started(stored, false, operation.get(), grant.get(), instance, parameters);
  }

  /** Step 3, with no transaction open: the command on the target. */
  private TargetResult call(TenantId tenantId, RunRequest request, Started started) {
    ConnectorInstanceView instance = started.instance();
    Optional<TargetConnector> connector =
        instance == null || !instance.active()
            ? Optional.empty()
            : targetConnectors.find(instance.kind());
    if (connector.isEmpty()) {
      return new TargetResult.NotStarted(
          "connector instance of " + request.targetKey() + " is not an active target instance");
    }
    OperationView operation = started.operation();
    JsonNode definitions = Parameters.JSON.readTree(operation.parametersJson());
    String command =
        Commands.fill(
            operation.commandTemplate(),
            definitions,
            Parameters.texts(definitions, started.parameters()));
    return connector
        .get()
        .run(
            tenantId,
            instance,
            started.grant().credentialRefId(),
            command,
            Duration.ofSeconds(operation.timeoutSeconds()));
  }

  /** Step 4: the run ends, when it is still {@code running}. */
  private OperationRunView end(TenantId tenantId, Started started, TargetResult result) {
    OperationView operation = started.operation();
    RunEnd end =
        switch (result) {
          case TargetResult.NotStarted notStarted ->
              new RunEnd(
                  OperationRunState.FAILED,
                  null,
                  null,
                  null,
                  false,
                  cut(notStarted.reason(), ERROR_MAX));
          case TargetResult.Finished finished ->
              finished(operation, finished, started.parameters());
          case TargetResult.Interrupted interrupted ->
              new RunEnd(
                  operation.permissionKind() == PermissionKind.ACTION
                      ? OperationRunState.UNKNOWN
                      : OperationRunState.FAILED,
                  null,
                  null,
                  interrupted.output(),
                  interrupted.outputTruncated(),
                  cut(interrupted.reason(), ERROR_MAX));
        };
    UUID id = started.run().id();
    Instant now = Timestamps.now(clock);
    boolean ended = records.endRun(tenantId, id, end, now);
    OperationRunView run = records.run(tenantId, id).orElseThrow();
    if (ended) {
      logEnded(tenantId, run);
    }
    return run;
  }

  private static RunEnd finished(
      OperationView operation, TargetResult.Finished finished, JsonNode parameters) {
    long exitStatus = finished.exitStatus();
    if (exitStatus != 0) {
      String error = finished.errorOutput() == null ? "" : finished.errorOutput();
      error = error.replaceFirst("[\\r\\n]+$", "");
      return new RunEnd(
          OperationRunState.FAILED,
          exitStatus,
          null,
          finished.output(),
          finished.outputTruncated(),
          cut(error.isEmpty() ? "exit status " + exitStatus : error, ERROR_MAX));
    }
    Optional<String> values =
        finished.outputTruncated()
            ? Optional.empty()
            : OutputParsers.parse(operation.outputParser(), finished.output(), parameters)
                .map(Parameters.JSON::writeValueAsString);
    return values
        .map(
            json ->
                new RunEnd(
                    OperationRunState.SUCCEEDED, exitStatus, json, finished.output(), false, null))
        .orElseGet(
            () ->
                new RunEnd(
                    OperationRunState.FAILED,
                    exitStatus,
                    null,
                    finished.output(),
                    finished.outputTruncated(),
                    "the output could not be read by " + operation.outputParser()));
  }

  @Override
  public void markInterruptedRuns() {
    for (TenantId tenantId : tenants.activeTenants()) {
      List<UUID> ids =
          tx.execute(
              status ->
                  records.endInterrupted(
                      tenantId, "interrupted by restart", Timestamps.now(clock)));
      for (UUID id : ids) {
        LOG.warn("operation run {} for tenant {} interrupted by restart", id, tenantId.value());
      }
    }
  }

  @Override
  public Optional<OperationView> operation(TenantId tenantId, String operationKey) {
    require(tenantId, operationKey);
    return records.operation(tenantId, operationKey);
  }

  @Override
  public List<GrantView> grants(TenantId tenantId) {
    require(tenantId);
    return List.copyOf(records.grants(tenantId));
  }

  @Override
  public Optional<OperationRunView> operationRun(TenantId tenantId, UUID id) {
    require(tenantId, id);
    return records.run(tenantId, id);
  }

  @Override
  public List<String> parameterProblems(
      TenantId tenantId, String operationKey, String parametersJson) {
    require(tenantId, operationKey, parametersJson);
    Optional<OperationView> operation = records.operation(tenantId, operationKey);
    if (operation.isEmpty() || !operation.get().active()) {
      return List.of("operation " + operationKey + " is not an active operation");
    }
    return Parameters.valueProblems(
        operationKey, Parameters.JSON.readTree(operation.get().parametersJson()), parametersJson);
  }

  private static void logEnded(TenantId tenantId, OperationRunView run) {
    LOG.info(
        "operation run {} for tenant {}: {} on {} ended {}",
        run.id(),
        tenantId.value(),
        run.operationKey(),
        run.targetKey(),
        run.state().key());
  }

  /**
   * The parameters as stored: the request's JSON object, or {@code {}} when it is not one or a text
   * in it holds U+0000 (PostgreSQL stores no U+0000 in {@code jsonb}).
   */
  private static String storedParameters(JsonNode parameters) {
    if (parameters == null || !parameters.isObject() || holdsNul(parameters)) {
      return "{}";
    }
    return Parameters.JSON.writeValueAsString(parameters);
  }

  private static boolean holdsNul(JsonNode node) {
    if (node.isString()) {
      return node.stringValue().contains(NUL);
    }
    for (String name : node.propertyNames()) {
      if (name.contains(NUL)) {
        return true;
      }
    }
    for (JsonNode child : node) {
      if (holdsNul(child)) {
        return true;
      }
    }
    return false;
  }

  /** The first {@code max} code points of {@code text}. */
  static String cut(String text, int max) {
    return text.codePointCount(0, text.length()) <= max
        ? text
        : text.substring(0, text.offsetByCodePoints(0, max));
  }

  static void require(Object... arguments) {
    for (Object argument : arguments) {
      if (argument == null) {
        throw new IllegalArgumentException("a required argument is missing");
      }
    }
  }
}
