package vigil.actions;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import vigil.actions.api.ActionPolicyView;
import vigil.actions.api.ApprovalRequestView;
import vigil.actions.api.PolicyOutcome;
import vigil.actions.api.RefusalReason;
import vigil.actions.api.RegisteredActionView;
import vigil.actions.api.RequestState;
import vigil.shared.Actor;
import vigil.shared.Ids;
import vigil.shared.TenantId;

/** The tables of {@code actions} (P-24 spec Section 6.2); every statement filters by tenant. */
@Component
class ActionRecords {

  static final JsonMapper JSON = JsonMapper.builder().build();

  /** An action proposal (DM-E-57). */
  record Proposal(
      UUID id,
      UUID incidentId,
      UUID runId,
      String actionKey,
      String actionSnapshotJson,
      String componentKey,
      String targetKey,
      UUID similarityResultId,
      String pastIncidentKey,
      BigDecimal score,
      long successCount) {}

  /** The states of an action execution (DM-SM-12). */
  enum ExecutionState {
    RECORDED,
    RECHECKING,
    NOT_EXECUTED,
    EXECUTING,
    RECONCILING,
    SUCCEEDED,
    FAILED,
    UNRESOLVED;

    String key() {
      return name().toLowerCase(Locale.ROOT);
    }

    boolean isFinal() {
      return this == NOT_EXECUTED || this == SUCCEEDED || this == FAILED || this == UNRESOLVED;
    }

    static ExecutionState of(String key) {
      return valueOf(key.toUpperCase(Locale.ROOT));
    }
  }

  /**
   * An action execution (DM-E-60): its fixed columns, and the changeable ones that the steps set
   * before they store them.
   */
  static final class Execution {
    final UUID id;
    final UUID incidentId;
    final UUID runId;
    final UUID stepExecutionId;
    final UUID proposalId;
    final String actionKey;
    final List<UUID> approvalRequestIds;
    final long attemptNumber;
    final Instant startedAt;
    ExecutionState state = ExecutionState.RECORDED;
    long recheckPass;
    String recheckJson;
    UUID operationRunId;
    List<UUID> reconciliationRunIds = List.of();
    Instant executingAt;
    Instant finishedAt;

    Execution(
        UUID id,
        UUID incidentId,
        UUID runId,
        UUID stepExecutionId,
        UUID proposalId,
        String actionKey,
        List<UUID> approvalRequestIds,
        long attemptNumber,
        Instant startedAt) {
      this.id = id;
      this.incidentId = incidentId;
      this.runId = runId;
      this.stepExecutionId = stepExecutionId;
      this.proposalId = proposalId;
      this.actionKey = actionKey;
      this.approvalRequestIds = List.copyOf(approvalRequestIds);
      this.attemptNumber = attemptNumber;
      this.startedAt = startedAt;
    }
  }

  private static final String ACTION_COLUMNS =
      "action_key, name, operation_key, parameter_values::text AS parameter_values,"
          + " limits::text AS limits, attempt_limit, precondition::text AS precondition,"
          + " reconciliation::text AS reconciliation, recovery_check_key, active,"
          + " updated_by_kind, updated_by_user_id, updated_by_module";
  private static final String PROPOSAL_COLUMNS =
      "id, incident_id, run_id, action_key, action_snapshot::text AS action_snapshot,"
          + " component_key, target_key, similarity_result_id, past_incident_key, score,"
          + " success_count";
  private static final String REQUEST_COLUMNS =
      "id, incident_id, run_id, step_execution_id, proposal_id, sequence_in_run, policy_outcome,"
          + " eligible_team_keys::text AS eligible_team_keys,"
          + " excluded_user_ids::text AS excluded_user_ids, snapshot::text AS snapshot,"
          + " snapshot_hash, expires_at, state, refusal_reason, decided_at, decided_by,"
          + " created_at";
  private static final String EXECUTION_COLUMNS =
      "id, incident_id, run_id, step_execution_id, proposal_id, action_key,"
          + " approval_request_ids::text AS approval_request_ids, attempt_number, state,"
          + " recheck_pass, recheck::text AS recheck, operation_run_id,"
          + " reconciliation_run_ids::text AS reconciliation_run_ids, started_at, executing_at,"
          + " finished_at";

  private final JdbcClient jdbc;

  ActionRecords(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  // Registered actions and policies (DM-E-55, DM-E-56).

  boolean hasActions(TenantId tenantId) {
    return jdbc.sql("SELECT EXISTS (SELECT 1 FROM actions.registered_action WHERE tenant_id = :t)")
        .param("t", tenantId.value())
        .query(Boolean.class)
        .single();
  }

  void insertAction(TenantId tenantId, RegisteredActionView action, Instant now) {
    jdbc.sql(
            "INSERT INTO actions.registered_action (id, tenant_id, created_at, updated_at,"
                + " action_key, name, operation_key, parameter_values, limits, attempt_limit,"
                + " precondition, reconciliation, recovery_check_key, active, updated_by_kind,"
                + " updated_by_user_id, updated_by_module) VALUES (:id, :t, :now, :now, :key,"
                + " :name, :operation, CAST(:parameters AS jsonb), CAST(:limits AS jsonb),"
                + " :attemptLimit, CAST(:precondition AS jsonb), CAST(:reconciliation AS jsonb),"
                + " :recovery, :active, :kind, :userId, :module)")
        .param("id", Ids.newId())
        .param("t", tenantId.value())
        .param("now", Timestamp.from(now))
        .param("key", action.actionKey())
        .param("name", action.name())
        .param("operation", action.operationKey())
        .param("parameters", action.parameterValuesJson())
        .param("limits", JSON.writeValueAsString(texts(action.limits())))
        .param("attemptLimit", action.attemptLimit())
        .param("precondition", StateChecks.write(action.precondition()))
        .param("reconciliation", StateChecks.write(action.reconciliation()))
        .param("recovery", action.recoveryCheckKey())
        .param("active", action.active())
        .param("kind", action.updatedBy().kind().name().toLowerCase(Locale.ROOT))
        .param("userId", action.updatedBy().userId())
        .param("module", action.updatedBy().module())
        .update();
  }

  List<RegisteredActionView> actions(TenantId tenantId) {
    return jdbc.sql(
            "SELECT "
                + ACTION_COLUMNS
                + " FROM actions.registered_action WHERE tenant_id = :t"
                + " ORDER BY action_key COLLATE \"C\"")
        .param("t", tenantId.value())
        .query(ActionRecords::action)
        .list();
  }

  Optional<RegisteredActionView> action(TenantId tenantId, String actionKey) {
    return jdbc.sql(
            "SELECT "
                + ACTION_COLUMNS
                + " FROM actions.registered_action WHERE tenant_id = :t AND action_key = :key")
        .param("t", tenantId.value())
        .param("key", actionKey)
        .query(ActionRecords::action)
        .optional();
  }

  void insertPolicy(TenantId tenantId, ActionPolicyView policy, Instant now) {
    jdbc.sql(
            "INSERT INTO actions.action_policy (id, tenant_id, created_at, updated_at,"
                + " action_key, service_key, outcome, active) VALUES (:id, :t, :now, :now, :key,"
                + " :service, :outcome, :active)")
        .param("id", Ids.newId())
        .param("t", tenantId.value())
        .param("now", Timestamp.from(now))
        .param("key", policy.actionKey())
        .param("service", policy.serviceKey())
        .param("outcome", policy.outcome().key())
        .param("active", policy.active())
        .update();
  }

  List<ActionPolicyView> policies(TenantId tenantId) {
    return jdbc.sql(
            "SELECT action_key, service_key, outcome, active FROM actions.action_policy"
                + " WHERE tenant_id = :t ORDER BY action_key COLLATE \"C\","
                + " service_key COLLATE \"C\" NULLS FIRST, id")
        .param("t", tenantId.value())
        .query(
            (rs, row) ->
                new ActionPolicyView(
                    rs.getString("action_key"),
                    rs.getString("service_key"),
                    PolicyOutcome.fromKey(rs.getString("outcome")).orElseThrow(),
                    rs.getBoolean("active")))
        .list();
  }

  // Proposals (DM-E-57) and steps without one.

  void insertProposal(TenantId tenantId, Proposal proposal, Instant now) {
    jdbc.sql(
            "INSERT INTO actions.action_proposal (id, tenant_id, created_at, incident_id, run_id,"
                + " action_key, action_snapshot, component_key, target_key, similarity_result_id,"
                + " past_incident_key, score, success_count) VALUES (:id, :t, :now, :incident,"
                + " :run, :key, CAST(:snapshot AS jsonb), :component, :target, :result, :past,"
                + " :score, :successCount)")
        .param("id", proposal.id())
        .param("t", tenantId.value())
        .param("now", Timestamp.from(now))
        .param("incident", proposal.incidentId())
        .param("run", proposal.runId())
        .param("key", proposal.actionKey())
        .param("snapshot", proposal.actionSnapshotJson())
        .param("component", proposal.componentKey())
        .param("target", proposal.targetKey())
        .param("result", proposal.similarityResultId())
        .param("past", proposal.pastIncidentKey())
        .param("score", proposal.score())
        .param("successCount", proposal.successCount())
        .update();
  }

  Optional<Proposal> proposalOfRun(TenantId tenantId, UUID runId) {
    return proposal(tenantId, "run_id", runId);
  }

  Optional<Proposal> proposal(TenantId tenantId, UUID id) {
    return proposal(tenantId, "id", id);
  }

  private Optional<Proposal> proposal(TenantId tenantId, String column, UUID value) {
    return jdbc.sql(
            "SELECT "
                + PROPOSAL_COLUMNS
                + " FROM actions.action_proposal WHERE tenant_id = :t AND "
                + column
                + " = :v")
        .param("t", tenantId.value())
        .param("v", value)
        .query(ActionRecords::proposal)
        .optional();
  }

  void insertWithoutProposal(
      TenantId tenantId, UUID runId, UUID incidentId, UUID stepExecutionId, Instant now) {
    jdbc.sql(
            "INSERT INTO actions.step_without_proposal (id, tenant_id, created_at, run_id,"
                + " incident_id, step_execution_id, at) VALUES (:id, :t, :now, :run, :incident,"
                + " :step, :now)")
        .param("id", Ids.newId())
        .param("t", tenantId.value())
        .param("now", Timestamp.from(now))
        .param("run", runId)
        .param("incident", incidentId)
        .param("step", stepExecutionId)
        .update();
  }

  /** Whether a row of {@code step_without_proposal} has this step execution or run. */
  boolean withoutProposal(TenantId tenantId, String column, UUID value) {
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM actions.step_without_proposal WHERE tenant_id = :t AND "
                + column
                + " = :v)")
        .param("t", tenantId.value())
        .param("v", value)
        .query(Boolean.class)
        .single();
  }

  // Approval requests (DM-E-58) and decisions (DM-E-59).

  void insertRequest(TenantId tenantId, ApprovalRequestView request) {
    jdbc.sql(
            "INSERT INTO actions.approval_request (id, tenant_id, created_at, updated_at,"
                + " incident_id, run_id, step_execution_id, proposal_id, sequence_in_run,"
                + " policy_outcome, eligible_team_keys, excluded_user_ids, snapshot,"
                + " snapshot_hash, expires_at, state, refusal_reason, decided_at, decided_by)"
                + " VALUES (:id, :t, :now, :now, :incident, :run, :step, :proposal, :sequence,"
                + " :outcome, CAST(:eligible AS jsonb), CAST(:excluded AS jsonb),"
                + " CAST(:snapshot AS jsonb), :hash, :expiresAt, :state, :reason, :decidedAt,"
                + " :decidedBy)")
        .param("id", request.id())
        .param("t", tenantId.value())
        .param("now", Timestamp.from(request.createdAt()))
        .param("incident", request.incidentId())
        .param("run", request.runId())
        .param("step", request.stepExecutionId())
        .param("proposal", request.proposalId())
        .param("sequence", request.sequenceInRun())
        .param("outcome", request.policyOutcome() == null ? null : request.policyOutcome().key())
        .param("eligible", JSON.writeValueAsString(texts(request.eligibleTeamKeys())))
        .param(
            "excluded",
            JSON.writeValueAsString(
                texts(request.excludedUserIds().stream().map(UUID::toString).toList())))
        .param("snapshot", request.snapshotJson())
        .param("hash", request.snapshotHash())
        .param("expiresAt", Timestamp.from(request.expiresAt()))
        .param("state", request.state().key())
        .param("reason", request.refusalReason() == null ? null : request.refusalReason().key())
        .param("decidedAt", timestamp(request.decidedAt()))
        .param("decidedBy", request.decidedBy())
        .update();
  }

  Optional<ApprovalRequestView> requestOfStep(TenantId tenantId, UUID stepExecutionId) {
    return request(tenantId, "step_execution_id", stepExecutionId, "");
  }

  /** The request, locked for the transaction (DM-R-63). */
  Optional<ApprovalRequestView> lockRequest(TenantId tenantId, UUID id) {
    return request(tenantId, "id", id, " FOR UPDATE");
  }

  Optional<ApprovalRequestView> request(TenantId tenantId, UUID id) {
    return request(tenantId, "id", id, "");
  }

  private Optional<ApprovalRequestView> request(
      TenantId tenantId, String column, UUID value, String lock) {
    return jdbc.sql(
            "SELECT "
                + REQUEST_COLUMNS
                + " FROM actions.approval_request WHERE tenant_id = :t AND "
                + column
                + " = :v"
                + lock)
        .param("t", tenantId.value())
        .param("v", value)
        .query(ActionRecords::request)
        .optional();
  }

  /** The run's requests, by creation. */
  List<ApprovalRequestView> requestsOfRun(TenantId tenantId, UUID runId) {
    return jdbc.sql(
            "SELECT "
                + REQUEST_COLUMNS
                + " FROM actions.approval_request WHERE tenant_id = :t AND run_id = :run"
                + " ORDER BY created_at, id")
        .param("t", tenantId.value())
        .param("run", runId)
        .query(ActionRecords::request)
        .list();
  }

  long requestCount(TenantId tenantId, UUID runId) {
    return jdbc.sql(
            "SELECT count(*) FROM actions.approval_request WHERE tenant_id = :t AND run_id = :run")
        .param("t", tenantId.value())
        .param("run", runId)
        .query(Long.class)
        .single();
  }

  /** Sets the state of a request with {@code decided_at} and {@code decided_by}. */
  void decideRequest(
      TenantId tenantId, UUID id, RequestState state, Instant decidedAt, UUID decidedBy) {
    jdbc.sql(
            "UPDATE actions.approval_request SET state = :state, decided_at = :decidedAt,"
                + " decided_by = :decidedBy, updated_at = :decidedAt WHERE tenant_id = :t AND id"
                + " = :id")
        .param("state", state.key())
        .param("decidedAt", Timestamp.from(decidedAt))
        .param("decidedBy", decidedBy)
        .param("t", tenantId.value())
        .param("id", id)
        .update();
  }

  /** DM-SM11-T8: the step execution's pending request becomes {@code cancelled}. */
  void cancelPending(TenantId tenantId, UUID stepExecutionId, Instant now) {
    jdbc.sql(
            "UPDATE actions.approval_request SET state = 'cancelled', decided_at = :now,"
                + " updated_at = :now WHERE tenant_id = :t AND step_execution_id = :step AND state"
                + " = 'pending'")
        .param("now", Timestamp.from(now))
        .param("t", tenantId.value())
        .param("step", stepExecutionId)
        .update();
  }

  void insertDecision(
      TenantId tenantId,
      UUID requestId,
      UUID userId,
      String teamKey,
      String decision,
      String snapshotHashShown,
      Instant at) {
    jdbc.sql(
            "INSERT INTO actions.approval_decision (id, tenant_id, created_at, request_id,"
                + " user_id, team_key, decision, snapshot_hash_shown, at) VALUES (:id, :t, :at,"
                + " :request, :user, :team, :decision, :hash, :at)")
        .param("id", Ids.newId())
        .param("t", tenantId.value())
        .param("at", Timestamp.from(at))
        .param("request", requestId)
        .param("user", userId)
        .param("team", teamKey)
        .param("decision", decision)
        .param("hash", snapshotHashShown)
        .update();
  }

  // Action executions (DM-E-60).

  void insertExecution(TenantId tenantId, Execution execution) {
    jdbc.sql(
            "INSERT INTO actions.action_execution (id, tenant_id, created_at, updated_at,"
                + " incident_id, run_id, step_execution_id, proposal_id, action_key,"
                + " approval_request_ids, attempt_number, state, recheck_pass, recheck,"
                + " operation_run_id, reconciliation_run_ids, started_at, executing_at,"
                + " finished_at) VALUES (:id, :t, :now, :now, :incident, :run, :step, :proposal,"
                + " :key, CAST(:requests AS jsonb), :attempt, :state, :pass, NULL, NULL,"
                + " CAST('[]' AS jsonb), :now, NULL, NULL)")
        .param("id", execution.id)
        .param("t", tenantId.value())
        .param("now", Timestamp.from(execution.startedAt))
        .param("incident", execution.incidentId)
        .param("run", execution.runId)
        .param("step", execution.stepExecutionId)
        .param("proposal", execution.proposalId)
        .param("key", execution.actionKey)
        .param("requests", ids(execution.approvalRequestIds))
        .param("attempt", execution.attemptNumber)
        .param("state", execution.state.key())
        .param("pass", execution.recheckPass)
        .update();
  }

  Optional<Execution> executionOfStep(TenantId tenantId, UUID stepExecutionId) {
    return jdbc.sql(
            "SELECT "
                + EXECUTION_COLUMNS
                + " FROM actions.action_execution WHERE tenant_id = :t AND step_execution_id ="
                + " :step")
        .param("t", tenantId.value())
        .param("step", stepExecutionId)
        .query(ActionRecords::execution)
        .optional();
  }

  /** Writes every changeable column of the execution. */
  void updateExecution(TenantId tenantId, Execution execution, Instant now) {
    jdbc.sql(
            "UPDATE actions.action_execution SET state = :state, recheck_pass = :pass, recheck ="
                + " CAST(:recheck AS jsonb), operation_run_id = :operationRun,"
                + " reconciliation_run_ids = CAST(:reconciliations AS jsonb), executing_at ="
                + " :executingAt, finished_at = :finishedAt, updated_at = :now WHERE tenant_id ="
                + " :t AND id = :id")
        .param("state", execution.state.key())
        .param("pass", execution.recheckPass)
        .param("recheck", execution.recheckJson)
        .param("operationRun", execution.operationRunId)
        .param("reconciliations", ids(execution.reconciliationRunIds))
        .param("executingAt", timestamp(execution.executingAt))
        .param("finishedAt", timestamp(execution.finishedAt))
        .param("now", Timestamp.from(now))
        .param("t", tenantId.value())
        .param("id", execution.id)
        .update();
  }

  /** The executions of the action for the incident that reached {@code executing}. */
  long executedCount(TenantId tenantId, UUID incidentId, String actionKey) {
    return jdbc.sql(
            "SELECT count(*) FROM actions.action_execution WHERE tenant_id = :t AND incident_id"
                + " = :incident AND action_key = :key AND executing_at IS NOT NULL")
        .param("t", tenantId.value())
        .param("incident", incidentId)
        .param("key", actionKey)
        .query(Long.class)
        .single();
  }

  boolean anyUnresolved(TenantId tenantId, UUID incidentId, String actionKey) {
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM actions.action_execution WHERE tenant_id = :t AND"
                + " incident_id = :incident AND action_key = :key AND state = 'unresolved')")
        .param("t", tenantId.value())
        .param("incident", incidentId)
        .param("key", actionKey)
        .query(Boolean.class)
        .single();
  }

  /** The run's execution with the latest {@code executing_at}, then the greatest id. */
  Optional<Execution> lastExecuted(TenantId tenantId, UUID runId) {
    return jdbc.sql(
            "SELECT "
                + EXECUTION_COLUMNS
                + " FROM actions.action_execution WHERE tenant_id = :t AND run_id = :run AND"
                + " executing_at IS NOT NULL ORDER BY executing_at DESC, id DESC LIMIT 1")
        .param("t", tenantId.value())
        .param("run", runId)
        .query(ActionRecords::execution)
        .optional();
  }

  /** The users of the run's {@code approved} requests, by decision, each once. */
  List<UUID> approvers(TenantId tenantId, UUID runId) {
    List<UUID> users =
        jdbc.sql(
                "SELECT decided_by FROM actions.approval_request WHERE tenant_id = :t AND run_id"
                    + " = :run AND state = 'approved' ORDER BY decided_at, id")
            .param("t", tenantId.value())
            .param("run", runId)
            .query(UUID.class)
            .list();
    return users.stream().distinct().toList();
  }

  // Mapping.

  static ArrayNode texts(List<String> values) {
    ArrayNode array = JSON.createArrayNode();
    values.forEach(array::add);
    return array;
  }

  private static String ids(List<UUID> ids) {
    return JSON.writeValueAsString(texts(ids.stream().map(UUID::toString).toList()));
  }

  static List<String> textList(String json) {
    List<String> values = new ArrayList<>();
    for (JsonNode node : JSON.readTree(json)) {
      values.add(node.stringValue());
    }
    return List.copyOf(values);
  }

  private static List<UUID> idList(String json) {
    return textList(json).stream().map(UUID::fromString).toList();
  }

  private static RegisteredActionView action(ResultSet rs, int row) throws SQLException {
    String kind = rs.getString("updated_by_kind");
    Actor actor =
        kind.equals("user")
            ? Actor.user(rs.getObject("updated_by_user_id", UUID.class))
            : Actor.module(rs.getString("updated_by_module"));
    return new RegisteredActionView(
        rs.getString("action_key"),
        rs.getString("name"),
        rs.getString("operation_key"),
        rs.getString("parameter_values"),
        textList(rs.getString("limits")),
        rs.getLong("attempt_limit"),
        StateChecks.read(rs.getString("precondition")),
        StateChecks.read(rs.getString("reconciliation")),
        rs.getString("recovery_check_key"),
        rs.getBoolean("active"),
        actor);
  }

  private static Proposal proposal(ResultSet rs, int row) throws SQLException {
    return new Proposal(
        rs.getObject("id", UUID.class),
        rs.getObject("incident_id", UUID.class),
        rs.getObject("run_id", UUID.class),
        rs.getString("action_key"),
        rs.getString("action_snapshot"),
        rs.getString("component_key"),
        rs.getString("target_key"),
        rs.getObject("similarity_result_id", UUID.class),
        rs.getString("past_incident_key"),
        rs.getBigDecimal("score"),
        rs.getLong("success_count"));
  }

  private static ApprovalRequestView request(ResultSet rs, int row) throws SQLException {
    String outcome = rs.getString("policy_outcome");
    String reason = rs.getString("refusal_reason");
    return new ApprovalRequestView(
        rs.getObject("id", UUID.class),
        rs.getObject("incident_id", UUID.class),
        rs.getObject("run_id", UUID.class),
        rs.getObject("step_execution_id", UUID.class),
        rs.getObject("proposal_id", UUID.class),
        rs.getLong("sequence_in_run"),
        outcome == null ? null : PolicyOutcome.fromKey(outcome).orElseThrow(),
        textList(rs.getString("eligible_team_keys")),
        idList(rs.getString("excluded_user_ids")),
        rs.getString("snapshot"),
        rs.getString("snapshot_hash"),
        instant(rs, "expires_at"),
        RequestState.fromKey(rs.getString("state")).orElseThrow(),
        reason == null ? null : RefusalReason.fromKey(reason).orElseThrow(),
        instant(rs, "decided_at"),
        rs.getObject("decided_by", UUID.class),
        instant(rs, "created_at"));
  }

  private static Execution execution(ResultSet rs, int row) throws SQLException {
    Execution execution =
        new Execution(
            rs.getObject("id", UUID.class),
            rs.getObject("incident_id", UUID.class),
            rs.getObject("run_id", UUID.class),
            rs.getObject("step_execution_id", UUID.class),
            rs.getObject("proposal_id", UUID.class),
            rs.getString("action_key"),
            idList(rs.getString("approval_request_ids")),
            rs.getLong("attempt_number"),
            instant(rs, "started_at"));
    execution.state = ExecutionState.of(rs.getString("state"));
    execution.recheckPass = rs.getLong("recheck_pass");
    execution.recheckJson = rs.getString("recheck");
    execution.operationRunId = rs.getObject("operation_run_id", UUID.class);
    execution.reconciliationRunIds = idList(rs.getString("reconciliation_run_ids"));
    execution.executingAt = instant(rs, "executing_at");
    execution.finishedAt = instant(rs, "finished_at");
    return execution;
  }

  private static Timestamp timestamp(Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }
}
