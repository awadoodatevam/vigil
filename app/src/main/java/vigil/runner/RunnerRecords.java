package vigil.runner;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import vigil.runner.api.GrantView;
import vigil.runner.api.OperationRunState;
import vigil.runner.api.OperationRunView;
import vigil.runner.api.OperationView;
import vigil.runner.api.PermissionKind;
import vigil.shared.TenantId;

/** The tables of {@code runner}; every statement filters by {@code tenant_id}. */
@Component
class RunnerRecords {

  private static final String RUN_COLUMNS =
      "id, operation_key, target_key, permission_kind, parameters::text AS parameters,"
          + " requested_by, request_id, state, started_at, finished_at, exit_status,"
          + " output_values::text AS output_values, output_text, output_truncated, error";

  /** A run as inserted. */
  record NewRun(
      UUID id,
      String operationKey,
      String targetKey,
      PermissionKind permissionKind,
      String parametersJson,
      String requestedBy,
      String requestId,
      OperationRunState state,
      Instant startedAt,
      Instant finishedAt,
      String error) {}

  /** The end of a run. */
  record RunEnd(
      OperationRunState state,
      Long exitStatus,
      String outputValuesJson,
      String outputText,
      boolean outputTruncated,
      String error) {}

  private final JdbcClient jdbc;

  RunnerRecords(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  boolean hasRecords(TenantId tenantId) {
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM runner.operation WHERE tenant_id = :t)"
                + " OR EXISTS (SELECT 1 FROM runner.target_access_grant WHERE tenant_id = :t)")
        .param("t", tenantId.value())
        .query(Boolean.class)
        .single();
  }

  void insertOperation(TenantId tenantId, UUID id, OperationView operation, Instant now) {
    jdbc.sql(
            "INSERT INTO runner.operation (id, tenant_id, created_at, updated_at, operation_key,"
                + " name, permission_kind, command_template, parameters, output_parser, timeout,"
                + " active) VALUES (:id, :t, :now, :now, :key, :name, :kind, :template,"
                + " CAST(:parameters AS jsonb), :parser, :timeout, :active)")
        .param("id", id)
        .param("t", tenantId.value())
        .param("now", Timestamp.from(now))
        .param("key", operation.operationKey())
        .param("name", operation.name())
        .param("kind", operation.permissionKind().key())
        .param("template", operation.commandTemplate())
        .param("parameters", operation.parametersJson())
        .param("parser", operation.outputParser())
        .param("timeout", (int) operation.timeoutSeconds())
        .param("active", operation.active())
        .update();
  }

  void insertGrant(TenantId tenantId, GrantView grant, Instant now) {
    jdbc.sql(
            "INSERT INTO runner.target_access_grant (id, tenant_id, created_at, updated_at,"
                + " target_key, connector_instance_id, permission_kind, credential_ref_id, active)"
                + " VALUES (:id, :t, :now, :now, :target, :instance, :kind, :credential, :active)")
        .param("id", grant.id())
        .param("t", tenantId.value())
        .param("now", Timestamp.from(now))
        .param("target", grant.targetKey())
        .param("instance", grant.connectorInstanceId())
        .param("kind", grant.permissionKind().key())
        .param("credential", grant.credentialRefId())
        .param("active", grant.active())
        .update();
  }

  Optional<OperationView> operation(TenantId tenantId, String operationKey) {
    return jdbc.sql(
            "SELECT operation_key, name, permission_kind, command_template,"
                + " parameters::text AS parameters, output_parser, timeout, active"
                + " FROM runner.operation WHERE tenant_id = :t AND operation_key = :key")
        .param("t", tenantId.value())
        .param("key", operationKey)
        .query(
            (rs, row) ->
                new OperationView(
                    rs.getString("operation_key"),
                    rs.getString("name"),
                    kind(rs),
                    rs.getString("command_template"),
                    rs.getString("parameters"),
                    rs.getString("output_parser"),
                    rs.getLong("timeout"),
                    rs.getBoolean("active")))
        .optional();
  }

  List<GrantView> grants(TenantId tenantId) {
    return jdbc.sql(
            "SELECT id, target_key, connector_instance_id, permission_kind, credential_ref_id,"
                + " active FROM runner.target_access_grant WHERE tenant_id = :t"
                + " ORDER BY target_key COLLATE \"C\", permission_kind COLLATE \"C\", id")
        .param("t", tenantId.value())
        .query(RunnerRecords::grant)
        .list();
  }

  Optional<GrantView> activeGrant(TenantId tenantId, String targetKey, PermissionKind kind) {
    return jdbc.sql(
            "SELECT id, target_key, connector_instance_id, permission_kind, credential_ref_id,"
                + " active FROM runner.target_access_grant WHERE tenant_id = :t"
                + " AND target_key = :target AND permission_kind = :kind AND active")
        .param("t", tenantId.value())
        .param("target", targetKey)
        .param("kind", kind.key())
        .query(RunnerRecords::grant)
        .optional();
  }

  Optional<OperationRunView> run(TenantId tenantId, UUID id) {
    return jdbc.sql(
            "SELECT "
                + RUN_COLUMNS
                + " FROM runner.operation_run WHERE tenant_id = :t AND id = :id")
        .param("t", tenantId.value())
        .param("id", id)
        .query(RunnerRecords::run)
        .optional();
  }

  Optional<OperationRunView> runByRequest(TenantId tenantId, String requestId) {
    return jdbc.sql(
            "SELECT "
                + RUN_COLUMNS
                + " FROM runner.operation_run WHERE tenant_id = :t AND request_id = :request")
        .param("t", tenantId.value())
        .param("request", requestId)
        .query(RunnerRecords::run)
        .optional();
  }

  /** Inserts the run unless the tenant has a run with its request id (DM-E14-R1). */
  void insertRun(TenantId tenantId, NewRun run, Instant now) {
    jdbc.sql(
            "INSERT INTO runner.operation_run (id, tenant_id, created_at, updated_at,"
                + " operation_key, target_key, permission_kind, parameters, requested_by,"
                + " request_id, state, started_at, finished_at, output_truncated, error)"
                + " VALUES (:id, :t, :now, :now, :operation, :target, :kind,"
                + " CAST(:parameters AS jsonb), :requestedBy, :request, :state, :startedAt,"
                + " :finishedAt, false, :error) ON CONFLICT (tenant_id, request_id) DO NOTHING")
        .param("id", run.id())
        .param("t", tenantId.value())
        .param("now", Timestamp.from(now))
        .param("operation", run.operationKey())
        .param("target", run.targetKey())
        .param("kind", run.permissionKind().key())
        .param("parameters", run.parametersJson())
        .param("requestedBy", run.requestedBy())
        .param("request", run.requestId())
        .param("state", run.state().key())
        .param("startedAt", Timestamp.from(run.startedAt()))
        .param("finishedAt", run.finishedAt() == null ? null : Timestamp.from(run.finishedAt()))
        .param("error", run.error())
        .update();
  }

  /** Ends the run when it is still {@code running}; whether it did. */
  boolean endRun(TenantId tenantId, UUID id, RunEnd end, Instant now) {
    return jdbc.sql(
                "UPDATE runner.operation_run SET updated_at = :now, state = :state,"
                    + " finished_at = :now, exit_status = :exitStatus,"
                    + " output_values = CAST(:outputValues AS jsonb), output_text = :outputText,"
                    + " output_truncated = :truncated, error = :error"
                    + " WHERE tenant_id = :t AND id = :id AND state = 'running'")
            .param("now", Timestamp.from(now))
            .param("state", end.state().key())
            .param("exitStatus", end.exitStatus())
            .param("outputValues", end.outputValuesJson())
            .param("outputText", end.outputText())
            .param("truncated", end.outputTruncated())
            .param("error", end.error())
            .param("t", tenantId.value())
            .param("id", id)
            .update()
        == 1;
  }

  /**
   * Ends every run still {@code running} as interrupted by a restart (DM-SM10-T6); returns their
   * ids.
   */
  List<UUID> endInterrupted(TenantId tenantId, String error, Instant now) {
    return jdbc.sql(
            "UPDATE runner.operation_run SET updated_at = :now, finished_at = :now,"
                + " state = CASE WHEN permission_kind = 'action' THEN 'unknown' ELSE 'failed' END,"
                + " error = :error WHERE tenant_id = :t AND state = 'running' RETURNING id")
        .param("now", Timestamp.from(now))
        .param("error", error)
        .param("t", tenantId.value())
        .query(UUID.class)
        .list();
  }

  private static GrantView grant(ResultSet rs, int row) throws SQLException {
    return new GrantView(
        rs.getObject("id", UUID.class),
        rs.getString("target_key"),
        rs.getObject("connector_instance_id", UUID.class),
        kind(rs),
        rs.getObject("credential_ref_id", UUID.class),
        rs.getBoolean("active"));
  }

  private static OperationRunView run(ResultSet rs, int row) throws SQLException {
    Timestamp finished = rs.getTimestamp("finished_at");
    return new OperationRunView(
        rs.getObject("id", UUID.class),
        rs.getString("operation_key"),
        rs.getString("target_key"),
        kind(rs),
        rs.getString("parameters"),
        rs.getString("requested_by"),
        rs.getString("request_id"),
        OperationRunState.fromKey(rs.getString("state")).orElseThrow(),
        rs.getTimestamp("started_at").toInstant(),
        finished == null ? null : finished.toInstant(),
        rs.getObject("exit_status", Long.class),
        rs.getString("output_values"),
        rs.getString("output_text"),
        rs.getBoolean("output_truncated"),
        rs.getString("error"));
  }

  private static PermissionKind kind(ResultSet rs) throws SQLException {
    return PermissionKind.fromKey(rs.getString("permission_kind")).orElseThrow();
  }
}
