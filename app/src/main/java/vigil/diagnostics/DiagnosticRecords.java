package vigil.diagnostics;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import vigil.diagnostics.api.DiagnosticDefinitionView;
import vigil.diagnostics.api.DiagnosticOutcome;
import vigil.diagnostics.api.DiagnosticResultView;
import vigil.shared.Ids;
import vigil.shared.TenantId;

/** The tables of {@code diagnostics}; every statement filters by {@code tenant_id}. */
@Component
class DiagnosticRecords {

  static final JsonMapper JSON = JsonMapper.builder().build();

  private static final String DEFINITION_COLUMNS =
      "diagnostic_key, name, operation_key, parameter_values::text AS parameter_values,"
          + " summary_template, active";
  private static final String RESULT_COLUMNS =
      "id, incident_id, run_id, step_execution_id, diagnostic_key, component_key, target_key,"
          + " operation_run_id, outcome, values::text AS values, collected_at, error";

  private final JdbcClient jdbc;

  DiagnosticRecords(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  boolean hasDefinitions(TenantId tenantId) {
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM diagnostics.diagnostic_definition"
                + " WHERE tenant_id = :t)")
        .param("t", tenantId.value())
        .query(Boolean.class)
        .single();
  }

  void insertDefinition(TenantId tenantId, DiagnosticDefinitionView definition, Instant now) {
    jdbc.sql(
            "INSERT INTO diagnostics.diagnostic_definition (id, tenant_id, created_at,"
                + " updated_at, diagnostic_key, name, operation_key, parameter_values,"
                + " summary_template, active) VALUES (:id, :t, :now, :now, :key, :name,"
                + " :operation, CAST(:values AS jsonb), :template, :active)")
        .param("id", Ids.newId())
        .param("t", tenantId.value())
        .param("now", Timestamp.from(now))
        .param("key", definition.diagnosticKey())
        .param("name", definition.name())
        .param("operation", definition.operationKey())
        .param("values", definition.parameterValuesJson())
        .param("template", definition.summaryTemplate())
        .param("active", definition.active())
        .update();
  }

  List<DiagnosticDefinitionView> definitions(TenantId tenantId) {
    return jdbc.sql(
            "SELECT "
                + DEFINITION_COLUMNS
                + " FROM diagnostics.diagnostic_definition WHERE tenant_id = :t"
                + " ORDER BY diagnostic_key COLLATE \"C\"")
        .param("t", tenantId.value())
        .query(DiagnosticRecords::definition)
        .list();
  }

  Optional<DiagnosticDefinitionView> definition(TenantId tenantId, String diagnosticKey) {
    return jdbc.sql(
            "SELECT "
                + DEFINITION_COLUMNS
                + " FROM diagnostics.diagnostic_definition"
                + " WHERE tenant_id = :t AND diagnostic_key = :key")
        .param("t", tenantId.value())
        .param("key", diagnosticKey)
        .query(DiagnosticRecords::definition)
        .optional();
  }

  List<DiagnosticResultView> results(TenantId tenantId, UUID incidentId) {
    return jdbc.sql(
            "SELECT "
                + RESULT_COLUMNS
                + " FROM diagnostics.diagnostic_result WHERE tenant_id = :t"
                + " AND incident_id = :incident ORDER BY collected_at, id")
        .param("t", tenantId.value())
        .param("incident", incidentId)
        .query(DiagnosticRecords::result)
        .list();
  }

  List<DiagnosticResultView> resultsOfStep(TenantId tenantId, UUID stepExecutionId) {
    return jdbc.sql(
            "SELECT "
                + RESULT_COLUMNS
                + " FROM diagnostics.diagnostic_result WHERE tenant_id = :t"
                + " AND step_execution_id = :step ORDER BY collected_at, id")
        .param("t", tenantId.value())
        .param("step", stepExecutionId)
        .query(DiagnosticRecords::result)
        .list();
  }

  /**
   * Inserts the result unless the step execution has a result for its key (DM-R-64); whether it was
   * inserted.
   */
  boolean insertResult(TenantId tenantId, DiagnosticResultView result, Instant now) {
    return jdbc.sql(
                "INSERT INTO diagnostics.diagnostic_result (id, tenant_id, created_at,"
                    + " incident_id, run_id, step_execution_id, diagnostic_key, component_key,"
                    + " target_key, operation_run_id, outcome, values, collected_at, error)"
                    + " VALUES (:id, :t, :now, :incident, :run, :step, :key, :component,"
                    + " :target, :operationRun, :outcome, CAST(:values AS jsonb), :collectedAt,"
                    + " :error) ON CONFLICT (tenant_id, step_execution_id, diagnostic_key)"
                    + " DO NOTHING")
            .param("id", result.id())
            .param("t", tenantId.value())
            .param("now", Timestamp.from(now))
            .param("incident", result.incidentId())
            .param("run", result.runId())
            .param("step", result.stepExecutionId())
            .param("key", result.diagnosticKey())
            .param("component", result.componentKey())
            .param("target", result.targetKey())
            .param("operationRun", result.operationRunId())
            .param("outcome", result.outcome().key())
            .param("values", result.valuesJson())
            .param("collectedAt", Timestamp.from(result.collectedAt()))
            .param("error", result.error())
            .update()
        == 1;
  }

  private static DiagnosticDefinitionView definition(ResultSet rs, int row) throws SQLException {
    return new DiagnosticDefinitionView(
        rs.getString("diagnostic_key"),
        rs.getString("name"),
        rs.getString("operation_key"),
        rs.getString("parameter_values"),
        rs.getString("summary_template"),
        rs.getBoolean("active"));
  }

  private static DiagnosticResultView result(ResultSet rs, int row) throws SQLException {
    return new DiagnosticResultView(
        rs.getObject("id", UUID.class),
        rs.getObject("incident_id", UUID.class),
        rs.getObject("run_id", UUID.class),
        rs.getObject("step_execution_id", UUID.class),
        rs.getString("diagnostic_key"),
        rs.getString("component_key"),
        rs.getString("target_key"),
        rs.getObject("operation_run_id", UUID.class),
        DiagnosticOutcome.fromKey(rs.getString("outcome")).orElseThrow(),
        rs.getString("values"),
        rs.getTimestamp("collected_at").toInstant(),
        rs.getString("error"));
  }
}
