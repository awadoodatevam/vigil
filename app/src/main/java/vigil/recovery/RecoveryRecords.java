package vigil.recovery;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import vigil.recovery.api.ConditionKind;
import vigil.recovery.api.RecoveryCondition;
import vigil.recovery.api.RecoveryVerificationView;
import vigil.recovery.api.VerificationState;
import vigil.shared.Ids;
import vigil.shared.TenantId;

/**
 * The tables of {@code recovery} and their JSON forms (P-23 spec Section 6.5); every statement
 * filters by {@code tenant_id}.
 */
@Component
class RecoveryRecords {

  static final JsonMapper JSON = JsonMapper.builder().build();

  /** A stored definition, also the content of a definition snapshot. */
  record Definition(
      String recoveryCheckKey,
      String name,
      List<RecoveryCondition> conditions,
      long observationWindowSeconds,
      boolean isDefault,
      boolean active) {

    Definition {
      conditions = List.copyOf(conditions);
    }
  }

  private static final String DEFINITION_COLUMNS =
      "recovery_check_key, name, conditions::text AS conditions, observation_window_seconds,"
          + " is_default, active";
  private static final String VERIFICATION_COLUMNS =
      "id, incident_id, run_id, step_execution_id, recovery_check_key,"
          + " definition_snapshot::text AS definition_snapshot, state, started_at, max_wait_until,"
          + " window_started_at, last_evaluation::text AS last_evaluation, decided_at";

  private final JdbcClient jdbc;

  RecoveryRecords(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  // Definitions (DM-E-61).

  boolean hasDefinitions(TenantId tenantId) {
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM recovery.recovery_check_definition"
                + " WHERE tenant_id = :t)")
        .param("t", tenantId.value())
        .query(Boolean.class)
        .single();
  }

  void insertDefinition(TenantId tenantId, Definition definition, Instant now) {
    jdbc.sql(
            "INSERT INTO recovery.recovery_check_definition (id, tenant_id, created_at,"
                + " updated_at, recovery_check_key, name, conditions, observation_window_seconds,"
                + " is_default, active) VALUES (:id, :t, :now, :now, :key, :name,"
                + " CAST(:conditions AS jsonb), :window, :isDefault, :active)")
        .param("id", Ids.newId())
        .param("t", tenantId.value())
        .param("now", Timestamp.from(now))
        .param("key", definition.recoveryCheckKey())
        .param("name", definition.name())
        .param("conditions", JSON.writeValueAsString(conditionsJson(definition.conditions())))
        .param("window", definition.observationWindowSeconds())
        .param("isDefault", definition.isDefault())
        .param("active", definition.active())
        .update();
  }

  List<Definition> definitions(TenantId tenantId) {
    return jdbc.sql(
            "SELECT "
                + DEFINITION_COLUMNS
                + " FROM recovery.recovery_check_definition WHERE tenant_id = :t"
                + " ORDER BY recovery_check_key COLLATE \"C\"")
        .param("t", tenantId.value())
        .query(RecoveryRecords::definition)
        .list();
  }

  Optional<Definition> definition(TenantId tenantId, String recoveryCheckKey) {
    return jdbc.sql(
            "SELECT "
                + DEFINITION_COLUMNS
                + " FROM recovery.recovery_check_definition"
                + " WHERE tenant_id = :t AND recovery_check_key = :key")
        .param("t", tenantId.value())
        .param("key", recoveryCheckKey)
        .query(RecoveryRecords::definition)
        .optional();
  }

  /** The active default definition (DM-E61-R1). */
  Optional<Definition> activeDefault(TenantId tenantId) {
    return jdbc.sql(
            "SELECT "
                + DEFINITION_COLUMNS
                + " FROM recovery.recovery_check_definition"
                + " WHERE tenant_id = :t AND is_default AND active")
        .param("t", tenantId.value())
        .query(RecoveryRecords::definition)
        .optional();
  }

  // Verifications (DM-E-62).

  void insertVerification(TenantId tenantId, RecoveryVerificationView verification, Instant now) {
    jdbc.sql(
            "INSERT INTO recovery.recovery_verification (id, tenant_id, created_at, updated_at,"
                + " incident_id, run_id, step_execution_id, recovery_check_key,"
                + " definition_snapshot, state, started_at, max_wait_until, window_started_at,"
                + " last_evaluation, decided_at) VALUES (:id, :t, :now, :now, :incident, :run,"
                + " :step, :key, CAST(:snapshot AS jsonb), :state, :startedAt, :maxWaitUntil,"
                + " :windowStartedAt, CAST(:evaluation AS jsonb), :decidedAt)")
        .param("id", verification.id())
        .param("t", tenantId.value())
        .param("now", Timestamp.from(now))
        .param("incident", verification.incidentId())
        .param("run", verification.runId())
        .param("step", verification.stepExecutionId())
        .param("key", verification.recoveryCheckKey())
        .param("snapshot", verification.definitionSnapshotJson())
        .param("state", verification.state().key())
        .param("startedAt", Timestamp.from(verification.startedAt()))
        .param("maxWaitUntil", Timestamp.from(verification.maxWaitUntil()))
        .param("windowStartedAt", timestamp(verification.windowStartedAt()))
        .param("evaluation", verification.lastEvaluationJson())
        .param("decidedAt", timestamp(verification.decidedAt()))
        .update();
  }

  /** The verification of the step execution, locked for the transaction (DM-R-64). */
  Optional<RecoveryVerificationView> lockOfStep(TenantId tenantId, UUID stepExecutionId) {
    return jdbc.sql(
            "SELECT "
                + VERIFICATION_COLUMNS
                + " FROM recovery.recovery_verification"
                + " WHERE tenant_id = :t AND step_execution_id = :step FOR UPDATE")
        .param("t", tenantId.value())
        .param("step", stepExecutionId)
        .query(RecoveryRecords::verification)
        .optional();
  }

  /** Writes the evaluation, the window and, on a decision, the state and {@code decided_at}. */
  void updateVerification(
      TenantId tenantId,
      UUID id,
      String lastEvaluationJson,
      Instant windowStartedAt,
      VerificationState state,
      Instant decidedAt,
      Instant now) {
    jdbc.sql(
            "UPDATE recovery.recovery_verification SET last_evaluation ="
                + " CAST(:evaluation AS jsonb), window_started_at = :window, state = :state,"
                + " decided_at = :decidedAt, updated_at = :now WHERE tenant_id = :t AND id = :id")
        .param("evaluation", lastEvaluationJson)
        .param("window", timestamp(windowStartedAt))
        .param("state", state.key())
        .param("decidedAt", timestamp(decidedAt))
        .param("now", Timestamp.from(now))
        .param("t", tenantId.value())
        .param("id", id)
        .update();
  }

  /** DM-SM13-T4: the step execution's observing verification becomes {@code cancelled}. */
  void cancel(TenantId tenantId, UUID stepExecutionId, Instant now) {
    jdbc.sql(
            "UPDATE recovery.recovery_verification SET state = 'cancelled', updated_at = :now"
                + " WHERE tenant_id = :t AND step_execution_id = :step AND state = 'observing'")
        .param("now", Timestamp.from(now))
        .param("t", tenantId.value())
        .param("step", stepExecutionId)
        .update();
  }

  List<RecoveryVerificationView> verifications(TenantId tenantId, UUID incidentId) {
    return jdbc.sql(
            "SELECT "
                + VERIFICATION_COLUMNS
                + " FROM recovery.recovery_verification"
                + " WHERE tenant_id = :t AND incident_id = :incident ORDER BY started_at, id")
        .param("t", tenantId.value())
        .param("incident", incidentId)
        .query(RecoveryRecords::verification)
        .list();
  }

  // The JSON forms.

  /** A condition: {@code kind}, then for a component condition its component and alert types. */
  static ObjectNode conditionJson(RecoveryCondition condition) {
    ObjectNode node = JSON.createObjectNode();
    node.put("kind", condition.kind().key());
    if (condition.kind() == ConditionKind.COMPONENT_NO_FIRING_ALERT) {
      node.put("component_key", condition.componentKey());
      ArrayNode types = node.putArray("alert_types");
      condition.alertTypes().forEach(types::add);
    }
    return node;
  }

  static ArrayNode conditionsJson(List<RecoveryCondition> conditions) {
    ArrayNode array = JSON.createArrayNode();
    conditions.forEach(condition -> array.add(conditionJson(condition)));
    return array;
  }

  static List<RecoveryCondition> conditions(JsonNode array) {
    List<RecoveryCondition> conditions = new ArrayList<>();
    for (JsonNode node : array) {
      ConditionKind kind = ConditionKind.fromKey(node.get("kind").stringValue()).orElseThrow();
      List<String> types = new ArrayList<>();
      JsonNode componentKey = node.get("component_key");
      JsonNode alertTypes = node.get("alert_types");
      if (alertTypes != null) {
        alertTypes.forEach(type -> types.add(type.stringValue()));
      }
      conditions.add(
          new RecoveryCondition(
              kind, componentKey == null ? null : componentKey.stringValue(), types));
    }
    return conditions;
  }

  /** The definition snapshot (DM-E-62 {@code definition_snapshot}). */
  static ObjectNode snapshotJson(Definition definition) {
    ObjectNode node = JSON.createObjectNode();
    node.put("recovery_check_key", definition.recoveryCheckKey());
    node.put("name", definition.name());
    node.set("conditions", conditionsJson(definition.conditions()));
    node.put("observation_window_seconds", definition.observationWindowSeconds());
    return node;
  }

  /** The definition a snapshot holds (not default, active: a snapshot keeps neither). */
  static Definition snapshot(String json) {
    JsonNode node = JSON.readTree(json);
    return new Definition(
        node.get("recovery_check_key").stringValue(),
        node.get("name").stringValue(),
        conditions(node.get("conditions")),
        node.get("observation_window_seconds").longValue(),
        false,
        true);
  }

  private static Definition definition(ResultSet rs, int row) throws SQLException {
    return new Definition(
        rs.getString("recovery_check_key"),
        rs.getString("name"),
        conditions(JSON.readTree(rs.getString("conditions"))),
        rs.getLong("observation_window_seconds"),
        rs.getBoolean("is_default"),
        rs.getBoolean("active"));
  }

  private static RecoveryVerificationView verification(ResultSet rs, int row) throws SQLException {
    return new RecoveryVerificationView(
        rs.getObject("id", UUID.class),
        rs.getObject("incident_id", UUID.class),
        rs.getObject("run_id", UUID.class),
        rs.getObject("step_execution_id", UUID.class),
        rs.getString("recovery_check_key"),
        rs.getString("definition_snapshot"),
        VerificationState.fromKey(rs.getString("state")).orElseThrow(),
        instant(rs, "started_at"),
        instant(rs, "max_wait_until"),
        instant(rs, "window_started_at"),
        rs.getString("last_evaluation"),
        instant(rs, "decided_at"));
  }

  private static Timestamp timestamp(Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }
}
