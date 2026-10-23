package vigil.correlation;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import vigil.correlation.api.CorrelationSettingsView;
import vigil.correlation.api.Decision;
import vigil.correlation.api.GroupingDecisionView;
import vigil.correlation.api.GroupingRelation;
import vigil.correlation.api.HypothesisComputationView;
import vigil.correlation.api.TieBreak;
import vigil.shared.Ids;
import vigil.shared.TenantId;

/** The records of vigil-correlation (P-16 spec Section 6.3); every query filters by tenant. */
@Component
class CorrelationRecords {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String DECISION_COLUMNS =
      "id, alert_id, alert_sequence, component_key, generation, decision, incident_id,"
          + " merged_incident_ids, related_alert_id, relation, relation_path, time_delta,"
          + " decided_at";
  private static final String COMPUTATION_COLUMNS =
      "id, incident_id, generation, affected_component_keys, dependent_counts,"
          + " result_component_key, tie_break, previous_component_key, computed_at";

  private final JdbcClient jdbc;

  CorrelationRecords(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  // Settings.

  Optional<CorrelationSettingsView> settings(TenantId tenantId) {
    return jdbc.sql(
            "SELECT grouping_window, settle_period, max_forming, stability_check_interval"
                + " FROM correlation.correlation_settings WHERE tenant_id = :tenantId")
        .param("tenantId", tenantId.value())
        .query(
            (rs, rowNum) ->
                new CorrelationSettingsView(
                    Duration.ofSeconds(rs.getInt("grouping_window")),
                    Duration.ofSeconds(rs.getInt("settle_period")),
                    Duration.ofSeconds(rs.getInt("max_forming")),
                    Duration.ofSeconds(rs.getInt("stability_check_interval"))))
        .optional();
  }

  void insertSettings(
      TenantId tenantId,
      int groupingWindow,
      int settlePeriod,
      int maxForming,
      int stabilityCheckInterval,
      Instant now) {
    jdbc.sql(
            "INSERT INTO correlation.correlation_settings (id, tenant_id, created_at, updated_at,"
                + " grouping_window, settle_period, max_forming, stability_check_interval)"
                + " VALUES (:id, :tenantId, :now, :now, :window, :settle, :maxForming,"
                + " :interval)")
        .param("id", Ids.newId())
        .param("tenantId", tenantId.value())
        .param("now", Timestamp.from(now))
        .param("window", groupingWindow)
        .param("settle", settlePeriod)
        .param("maxForming", maxForming)
        .param("interval", stabilityCheckInterval)
        .update();
  }

  // Grouping decisions.

  boolean hasDecision(TenantId tenantId, UUID alertId) {
    return jdbc.sql(
                "SELECT count(*) FROM correlation.grouping_decision"
                    + " WHERE tenant_id = :tenantId AND alert_id = :alertId")
            .param("tenantId", tenantId.value())
            .param("alertId", alertId)
            .query(Long.class)
            .single()
        > 0;
  }

  Optional<GroupingDecisionView> decision(TenantId tenantId, UUID alertId) {
    return jdbc.sql(
            "SELECT "
                + DECISION_COLUMNS
                + " FROM correlation.grouping_decision"
                + " WHERE tenant_id = :tenantId AND alert_id = :alertId")
        .param("tenantId", tenantId.value())
        .param("alertId", alertId)
        .query(CorrelationRecords::decision)
        .optional();
  }

  List<GroupingDecisionView> decisions(TenantId tenantId, UUID incidentId) {
    return jdbc
        .sql(
            "SELECT "
                + DECISION_COLUMNS
                + " FROM correlation.grouping_decision"
                + " WHERE tenant_id = :tenantId AND incident_id = :incidentId"
                + " ORDER BY alert_sequence")
        .param("tenantId", tenantId.value())
        .param("incidentId", incidentId)
        .query(CorrelationRecords::decision)
        .list()
        .stream()
        .toList();
  }

  List<UUID> alertsOfComponent(TenantId tenantId, String componentKey) {
    return jdbc
        .sql(
            "SELECT alert_id FROM correlation.grouping_decision"
                + " WHERE tenant_id = :tenantId AND component_key = :componentKey"
                + " ORDER BY alert_sequence")
        .param("tenantId", tenantId.value())
        .param("componentKey", componentKey)
        .query(UUID.class)
        .list()
        .stream()
        .toList();
  }

  void insertDecision(TenantId tenantId, GroupingDecisionView view) {
    jdbc.sql(
            "INSERT INTO correlation.grouping_decision (id, tenant_id, created_at, alert_id,"
                + " alert_sequence, component_key, generation, decision, incident_id,"
                + " merged_incident_ids, related_alert_id, relation, relation_path, time_delta,"
                + " decided_at) VALUES (:id, :tenantId, :decidedAt, :alertId, :alertSequence,"
                + " :componentKey, :generation, :decision, :incidentId, CAST(:merged AS jsonb),"
                + " :relatedAlertId, :relation, CAST(:relationPath AS jsonb), :timeDelta,"
                + " :decidedAt)")
        .param("id", view.id())
        .param("tenantId", tenantId.value())
        .param("decidedAt", Timestamp.from(view.decidedAt()))
        .param("alertId", view.alertId())
        .param("alertSequence", view.alertSequence())
        .param("componentKey", view.componentKey())
        .param("generation", view.generation())
        .param("decision", view.decision().key())
        .param("incidentId", view.incidentId())
        .param("merged", array(view.mergedIncidentIds().stream().map(UUID::toString).toList()))
        .param("relatedAlertId", view.relatedAlertId())
        .param("relation", view.relation() == null ? null : view.relation().key())
        .param("relationPath", view.relationPath() == null ? null : array(view.relationPath()))
        .param(
            "timeDelta",
            view.timeDeltaSeconds() == null ? null : Math.toIntExact(view.timeDeltaSeconds()))
        .update();
  }

  private static GroupingDecisionView decision(ResultSet rs, int rowNum) throws SQLException {
    String relation = rs.getString("relation");
    String path = rs.getString("relation_path");
    int timeDelta = rs.getInt("time_delta");
    Long timeDeltaSeconds = rs.wasNull() ? null : (long) timeDelta;
    return new GroupingDecisionView(
        rs.getObject("id", UUID.class),
        rs.getObject("alert_id", UUID.class),
        rs.getLong("alert_sequence"),
        rs.getString("component_key"),
        rs.getLong("generation"),
        Decision.fromKey(rs.getString("decision")).orElseThrow(),
        rs.getObject("incident_id", UUID.class),
        strings(rs.getString("merged_incident_ids")).stream().map(UUID::fromString).toList(),
        rs.getObject("related_alert_id", UUID.class),
        relation == null ? null : GroupingRelation.fromKey(relation).orElseThrow(),
        path == null ? null : strings(path),
        timeDeltaSeconds,
        rs.getTimestamp("decided_at").toInstant());
  }

  // Hypothesis computations.

  List<HypothesisComputationView> computations(TenantId tenantId, UUID incidentId) {
    return jdbc
        .sql(
            "SELECT "
                + COMPUTATION_COLUMNS
                + " FROM correlation.hypothesis_computation"
                + " WHERE tenant_id = :tenantId AND incident_id = :incidentId"
                + " ORDER BY computed_at, id")
        .param("tenantId", tenantId.value())
        .param("incidentId", incidentId)
        .query(CorrelationRecords::computation)
        .list()
        .stream()
        .toList();
  }

  Optional<HypothesisComputationView> latestComputation(TenantId tenantId, UUID incidentId) {
    return jdbc.sql(
            "SELECT "
                + COMPUTATION_COLUMNS
                + " FROM correlation.hypothesis_computation"
                + " WHERE tenant_id = :tenantId AND incident_id = :incidentId"
                + " ORDER BY computed_at DESC, id DESC LIMIT 1")
        .param("tenantId", tenantId.value())
        .param("incidentId", incidentId)
        .query(CorrelationRecords::computation)
        .optional();
  }

  void insertComputation(TenantId tenantId, HypothesisComputationView view) {
    ObjectNode counts = JSON.createObjectNode();
    view.dependentCounts().forEach(counts::put);
    jdbc.sql(
            "INSERT INTO correlation.hypothesis_computation (id, tenant_id, created_at,"
                + " incident_id, generation, affected_component_keys, dependent_counts,"
                + " result_component_key, tie_break, previous_component_key, computed_at)"
                + " VALUES (:id, :tenantId, :computedAt, :incidentId, :generation,"
                + " CAST(:affected AS jsonb), CAST(:counts AS jsonb), :result, :tieBreak,"
                + " :previous, :computedAt)")
        .param("id", view.id())
        .param("tenantId", tenantId.value())
        .param("computedAt", Timestamp.from(view.computedAt()))
        .param("incidentId", view.incidentId())
        .param("generation", view.generation())
        .param("affected", array(view.affectedComponentKeys()))
        .param("counts", JSON.writeValueAsString(counts))
        .param("result", view.resultComponentKey())
        .param("tieBreak", view.tieBreak().key())
        .param("previous", view.previousComponentKey())
        .update();
  }

  private static HypothesisComputationView computation(ResultSet rs, int rowNum)
      throws SQLException {
    JsonNode counts = JSON.readTree(rs.getString("dependent_counts"));
    List<String> keys = new ArrayList<>(counts.propertyNames());
    keys.sort(GroupingRules.CODE_POINTS);
    Map<String, Long> dependentCounts = new LinkedHashMap<>();
    keys.forEach(key -> dependentCounts.put(key, counts.get(key).longValue()));
    return new HypothesisComputationView(
        rs.getObject("id", UUID.class),
        rs.getObject("incident_id", UUID.class),
        rs.getLong("generation"),
        strings(rs.getString("affected_component_keys")),
        Collections.unmodifiableMap(dependentCounts),
        rs.getString("result_component_key"),
        TieBreak.fromKey(rs.getString("tie_break")).orElseThrow(),
        rs.getString("previous_component_key"),
        rs.getTimestamp("computed_at").toInstant());
  }

  // The cursor (AR-R-17).

  long cursor(TenantId tenantId) {
    return jdbc.sql("SELECT last_sequence FROM correlation.cursor WHERE tenant_id = :tenantId")
        .param("tenantId", tenantId.value())
        .query(Long.class)
        .optional()
        .orElse(0L);
  }

  void setCursor(TenantId tenantId, long sequence) {
    jdbc.sql(
            "INSERT INTO correlation.cursor (tenant_id, last_sequence) VALUES (:tenantId,"
                + " :sequence) ON CONFLICT (tenant_id) DO UPDATE SET last_sequence ="
                + " EXCLUDED.last_sequence")
        .param("tenantId", tenantId.value())
        .param("sequence", sequence)
        .update();
  }

  // JSON.

  private static String array(Collection<String> values) {
    ArrayNode array = JSON.createArrayNode();
    values.forEach(array::add);
    return JSON.writeValueAsString(array);
  }

  private static List<String> strings(String json) {
    List<String> values = new ArrayList<>();
    JSON.readTree(json).forEach(value -> values.add(value.stringValue()));
    return List.copyOf(values);
  }
}
