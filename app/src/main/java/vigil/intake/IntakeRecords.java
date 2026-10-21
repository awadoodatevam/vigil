package vigil.intake;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
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
import vigil.intake.api.AlertStatus;
import vigil.intake.api.AlertTypeView;
import vigil.intake.api.AlertView;
import vigil.intake.api.Health;
import vigil.intake.api.IdentityPair;
import vigil.intake.api.MonitoringKind;
import vigil.intake.api.SourceView;
import vigil.intake.api.TestOutcome;
import vigil.shared.Ids;
import vigil.shared.TenantId;

/** The tables of {@code intake}; every statement filters by {@code tenant_id}. */
@Component
class IntakeRecords {

  static final JsonMapper JSON = JsonMapper.builder().build();

  private final JdbcClient jdbc;

  IntakeRecords(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** A source row: its view and its webhook token reference. */
  record Stored(SourceView view, UUID webhookTokenRefId) {

    HealthRule.Inputs inputs() {
      return new HealthRule.Inputs(
          view.health(),
          view.lastContactAt(),
          view.lastTestAt(),
          view.lastTestOutcome(),
          view.staleAfter(),
          view.disconnectedAfter());
    }
  }

  /** A new source of seed part 6. */
  record NewSource(
      String name,
      String sourceKey,
      String sourceKind,
      UUID connectorInstanceId,
      UUID webhookTokenRefId,
      long healthCheckInterval,
      long staleAfter,
      long disconnectedAfter) {}

  // Sources.

  Optional<Stored> activeSource(TenantId tenantId, String sourceKey) {
    return jdbc.sql(
            "SELECT * FROM intake.monitoring_source WHERE tenant_id = :tenantId"
                + " AND source_key = :sourceKey AND active")
        .param("tenantId", tenantId.value())
        .param("sourceKey", sourceKey)
        .query(IntakeRecords::stored)
        .optional();
  }

  Optional<Stored> source(TenantId tenantId, UUID sourceId) {
    return jdbc.sql(
            "SELECT * FROM intake.monitoring_source WHERE tenant_id = :tenantId AND id = :id")
        .param("tenantId", tenantId.value())
        .param("id", sourceId)
        .query(IntakeRecords::stored)
        .optional();
  }

  /** The source row, locked until the end of the transaction. */
  Stored lockSource(TenantId tenantId, UUID sourceId) {
    return jdbc.sql(
            "SELECT * FROM intake.monitoring_source WHERE tenant_id = :tenantId AND id = :id"
                + " FOR UPDATE")
        .param("tenantId", tenantId.value())
        .param("id", sourceId)
        .query(IntakeRecords::stored)
        .single();
  }

  /** Every source, or only the active ones, in code point order of {@code source_key}. */
  List<Stored> sources(TenantId tenantId, boolean activeOnly) {
    return jdbc.sql(
            "SELECT * FROM intake.monitoring_source WHERE tenant_id = :tenantId"
                + (activeOnly ? " AND active" : "")
                + " ORDER BY source_key COLLATE \"C\"")
        .param("tenantId", tenantId.value())
        .query(IntakeRecords::stored)
        .list();
  }

  boolean hasMonitoringRecords(TenantId tenantId) {
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM intake.monitoring_source WHERE tenant_id = :tenantId)"
                + " OR EXISTS (SELECT 1 FROM intake.alert_type_definition"
                + " WHERE tenant_id = :tenantId)"
                + " OR EXISTS (SELECT 1 FROM intake.alert_type_mapping WHERE tenant_id = :tenantId)")
        .param("tenantId", tenantId.value())
        .query(Boolean.class)
        .single();
  }

  /** Stores a new source in state {@code disconnected}; returns its id. */
  UUID insertSource(TenantId tenantId, NewSource source, String module, Instant now) {
    UUID id = Ids.newId();
    jdbc.sql(
            "INSERT INTO intake.monitoring_source (id, tenant_id, created_at, updated_at, name,"
                + " source_kind, connector_instance_id, source_key, webhook_token_ref_id,"
                + " health_check_interval, stale_after, disconnected_after, health,"
                + " health_changed_at, last_contact_at, last_test_at, last_test_outcome,"
                + " last_test_reason, active, updated_by_kind, updated_by_user_id,"
                + " updated_by_module) VALUES (:id, :tenantId, :now, :now, :name, :kind,"
                + " :instanceId, :sourceKey, :tokenRefId, :interval, :stale, :disconnected,"
                + " 'disconnected', :now, NULL, NULL, NULL, NULL, true, 'module', NULL, :module)")
        .param("id", id)
        .param("tenantId", tenantId.value())
        .param("now", Timestamp.from(now))
        .param("name", source.name())
        .param("kind", source.sourceKind())
        .param("instanceId", source.connectorInstanceId())
        .param("sourceKey", source.sourceKey())
        .param("tokenRefId", source.webhookTokenRefId())
        .param("interval", (int) source.healthCheckInterval())
        .param("stale", (int) source.staleAfter())
        .param("disconnected", (int) source.disconnectedAfter())
        .param("module", module)
        .update();
    return id;
  }

  /** Sets the source's contact time (DM-R-39). */
  void updateContact(TenantId tenantId, UUID sourceId, Instant now) {
    jdbc.sql(
            "UPDATE intake.monitoring_source SET last_contact_at = :now, updated_at = :now"
                + " WHERE tenant_id = :tenantId AND id = :id")
        .param("now", Timestamp.from(now))
        .param("tenantId", tenantId.value())
        .param("id", sourceId)
        .update();
  }

  /** Records a connection test result; a passed test is also a contact (DM-R-39). */
  void updateTest(
      TenantId tenantId, UUID sourceId, TestOutcome outcome, String reason, Instant now) {
    jdbc.sql(
            "UPDATE intake.monitoring_source SET last_test_at = :now, last_test_outcome ="
                + " :outcome, last_test_reason = :reason, updated_at = :now"
                + (outcome == TestOutcome.PASSED ? ", last_contact_at = :now" : "")
                + " WHERE tenant_id = :tenantId AND id = :id")
        .param("now", Timestamp.from(now))
        .param("outcome", outcome.key())
        .param("reason", reason)
        .param("tenantId", tenantId.value())
        .param("id", sourceId)
        .update();
  }

  void updateHealth(TenantId tenantId, UUID sourceId, Health health, Instant now) {
    jdbc.sql(
            "UPDATE intake.monitoring_source SET health = :health, health_changed_at = :now,"
                + " updated_at = :now WHERE tenant_id = :tenantId AND id = :id")
        .param("health", health.key())
        .param("now", Timestamp.from(now))
        .param("tenantId", tenantId.value())
        .param("id", sourceId)
        .update();
  }

  void insertHealthChange(
      TenantId tenantId, UUID sourceId, Health from, Health to, Instant at, HealthCause cause) {
    jdbc.sql(
            "INSERT INTO intake.source_health_change (id, tenant_id, created_at, source_id,"
                + " from_health, to_health, at, cause) VALUES (:id, :tenantId, :at, :sourceId,"
                + " :from, :to, :at, :cause)")
        .param("id", Ids.newId())
        .param("tenantId", tenantId.value())
        .param("at", Timestamp.from(at))
        .param("sourceId", sourceId)
        .param("from", from == null ? null : from.key())
        .param("to", to.key())
        .param("cause", cause.key())
        .update();
  }

  /** The {@code to_health} of the source's latest change at or before {@code at}, or empty. */
  Optional<String> healthAt(TenantId tenantId, UUID sourceId, Instant at) {
    return jdbc.sql(
            "SELECT to_health FROM intake.source_health_change WHERE tenant_id = :tenantId"
                + " AND source_id = :sourceId AND at <= :at ORDER BY at DESC, id DESC LIMIT 1")
        .param("tenantId", tenantId.value())
        .param("sourceId", sourceId)
        .param("at", Timestamp.from(at))
        .query(String.class)
        .optional();
  }

  /**
   * Whether the source has a change with {@code at} later than {@code from}, not after {@code to}.
   */
  boolean changedWithin(TenantId tenantId, UUID sourceId, Instant from, Instant to) {
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM intake.source_health_change WHERE tenant_id = :tenantId"
                + " AND source_id = :sourceId AND at > :from AND at <= :to)")
        .param("tenantId", tenantId.value())
        .param("sourceId", sourceId)
        .param("from", Timestamp.from(from))
        .param("to", Timestamp.from(to))
        .query(Boolean.class)
        .single();
  }

  // Alert types and mappings.

  void insertAlertType(TenantId tenantId, String alertType, String displayName, Instant now) {
    jdbc.sql(
            "INSERT INTO intake.alert_type_definition (id, tenant_id, created_at, updated_at,"
                + " alert_type, display_name) VALUES (:id, :tenantId, :now, :now, :alertType,"
                + " :displayName)")
        .param("id", Ids.newId())
        .param("tenantId", tenantId.value())
        .param("now", Timestamp.from(now))
        .param("alertType", alertType)
        .param("displayName", displayName)
        .update();
  }

  List<AlertTypeView> alertTypes(TenantId tenantId) {
    return jdbc.sql(
            "SELECT alert_type, display_name FROM intake.alert_type_definition"
                + " WHERE tenant_id = :tenantId ORDER BY alert_type COLLATE \"C\"")
        .param("tenantId", tenantId.value())
        .query(
            (rs, row) ->
                new AlertTypeView(rs.getString("alert_type"), rs.getString("display_name")))
        .list();
  }

  void insertMapping(TenantId tenantId, AlertTypeRule.Mapping mapping, Instant now) {
    jdbc.sql(
            "INSERT INTO intake.alert_type_mapping (id, tenant_id, created_at, updated_at,"
                + " source_kind, \"order\", field, operator, value, alert_type) VALUES (:id,"
                + " :tenantId, :now, :now, :kind, :order, :field, :operator, :value, :alertType)")
        .param("id", Ids.newId())
        .param("tenantId", tenantId.value())
        .param("now", Timestamp.from(now))
        .param("kind", mapping.sourceKind().key())
        .param("order", mapping.order())
        .param("field", mapping.field())
        .param("operator", mapping.operator())
        .param("value", mapping.value())
        .param("alertType", mapping.alertType())
        .update();
  }

  /** The mappings of one kind in ascending {@code order}. */
  List<AlertTypeRule.Mapping> mappings(TenantId tenantId, MonitoringKind kind) {
    return jdbc.sql(
            "SELECT * FROM intake.alert_type_mapping WHERE tenant_id = :tenantId"
                + " AND source_kind = :kind ORDER BY \"order\"")
        .param("tenantId", tenantId.value())
        .param("kind", kind.key())
        .query(
            (rs, row) ->
                new AlertTypeRule.Mapping(
                    kind,
                    rs.getLong("order"),
                    rs.getString("field"),
                    rs.getString("operator"),
                    rs.getString("value"),
                    rs.getString("alert_type")))
        .list();
  }

  // The counter, calls and entries.

  void insertCounter(TenantId tenantId) {
    jdbc.sql("INSERT INTO intake.alert_counter (tenant_id, last_sequence) VALUES (:tenantId, 0)")
        .param("tenantId", tenantId.value())
        .update();
  }

  /** The counter's {@code last_sequence}, its row locked until commit (AR-R-16). */
  long lockCounter(TenantId tenantId) {
    return jdbc.sql(
            "SELECT last_sequence FROM intake.alert_counter WHERE tenant_id = :tenantId"
                + " FOR UPDATE")
        .param("tenantId", tenantId.value())
        .query(Long.class)
        .optional()
        .orElseThrow(() -> new IllegalStateException("no alert counter for the tenant"));
  }

  void updateCounter(TenantId tenantId, long lastSequence) {
    jdbc.sql("UPDATE intake.alert_counter SET last_sequence = :last WHERE tenant_id = :tenantId")
        .param("last", lastSequence)
        .param("tenantId", tenantId.value())
        .update();
  }

  UUID insertCall(
      TenantId tenantId,
      UUID sourceId,
      Instant receivedAt,
      String contentType,
      byte[] body,
      int entryCount,
      boolean parsed) {
    UUID id = Ids.newId();
    jdbc.sql(
            "INSERT INTO intake.webhook_call (id, tenant_id, created_at, source_id, received_at,"
                + " content_type, body, entry_count, parse_outcome) VALUES (:id, :tenantId,"
                + " :at, :sourceId, :at, :contentType, :body, :entryCount, :outcome)")
        .param("id", id)
        .param("tenantId", tenantId.value())
        .param("at", Timestamp.from(receivedAt))
        .param("sourceId", sourceId)
        .param("contentType", contentType)
        .param("body", body)
        .param("entryCount", entryCount)
        .param("outcome", parsed ? "parsed" : "failed")
        .update();
    return id;
  }

  UUID insertRaw(
      TenantId tenantId,
      UUID callId,
      UUID sourceId,
      int entryIndex,
      Instant receivedAt,
      String payload) {
    UUID id = Ids.newId();
    jdbc.sql(
            "INSERT INTO intake.raw_alert (id, tenant_id, created_at, webhook_call_id, source_id,"
                + " entry_index, received_at, payload) VALUES (:id, :tenantId, :at, :callId,"
                + " :sourceId, :entryIndex, :at, :payload)")
        .param("id", id)
        .param("tenantId", tenantId.value())
        .param("at", Timestamp.from(receivedAt))
        .param("callId", callId)
        .param("sourceId", sourceId)
        .param("entryIndex", entryIndex)
        .param("payload", payload)
        .update();
    return id;
  }

  /** A normalised alert (DM-E-20); fields of a failed entry are {@code null}. */
  record Normalised(
      UUID rawAlertId,
      boolean normalised,
      String failureReason,
      String alertKey,
      AlertStatus reportedStatus,
      String alertType,
      List<IdentityPair> identityPairs,
      String title,
      Instant sourceTime,
      String sourceSeverity) {}

  void insertNormalised(
      TenantId tenantId,
      Normalised entry,
      UUID appliedToAlertId,
      String application,
      Instant receivedAt) {
    jdbc.sql(
            "INSERT INTO intake.normalised_alert (id, tenant_id, created_at, raw_alert_id,"
                + " outcome, failure_reason, alert_key, reported_status, alert_type,"
                + " identity_pairs, title, source_time, source_severity, applied_to_alert_id,"
                + " application) VALUES (:id, :tenantId, :at, :rawId, :outcome, :reason,"
                + " :alertKey, :status, :alertType, CAST(:pairs AS jsonb), :title, :sourceTime,"
                + " :severity, :alertId, :application)")
        .param("id", Ids.newId())
        .param("tenantId", tenantId.value())
        .param("at", Timestamp.from(receivedAt))
        .param("rawId", entry.rawAlertId())
        .param("outcome", entry.normalised() ? "normalised" : "failed")
        .param("reason", entry.failureReason())
        .param("alertKey", entry.alertKey())
        .param("status", entry.reportedStatus() == null ? null : entry.reportedStatus().key())
        .param("alertType", entry.alertType())
        .param("pairs", entry.identityPairs() == null ? null : pairsJson(entry.identityPairs()))
        .param("title", entry.title())
        .param("sourceTime", timestamp(entry.sourceTime()))
        .param("severity", entry.sourceSeverity())
        .param("alertId", appliedToAlertId)
        .param("application", application)
        .update();
  }

  // Alerts.

  /** The firing alert of the source and key, or empty (DM-E21-R2). */
  Optional<AlertView> firingAlert(TenantId tenantId, UUID sourceId, String alertKey) {
    return jdbc.sql(
            "SELECT * FROM intake.alert WHERE tenant_id = :tenantId AND source_id = :sourceId"
                + " AND alert_key = :alertKey AND status = 'firing'")
        .param("tenantId", tenantId.value())
        .param("sourceId", sourceId)
        .param("alertKey", alertKey)
        .query(IntakeRecords::alert)
        .optional();
  }

  UUID insertAlert(
      TenantId tenantId,
      UUID sourceId,
      MonitoringKind kind,
      Normalised entry,
      Instant firedAt,
      Instant receivedAt,
      long sequence) {
    UUID id = Ids.newId();
    jdbc.sql(
            "INSERT INTO intake.alert (id, tenant_id, created_at, updated_at, source_id,"
                + " source_kind, alert_key, alert_type, identity_pairs, title, status, fired_at,"
                + " first_received_at, resolved_at, resolve_received_at, entry_count, sequence)"
                + " VALUES (:id, :tenantId, :at, :at, :sourceId, :kind, :alertKey, :alertType,"
                + " CAST(:pairs AS jsonb), :title, 'firing', :firedAt, :at, NULL, NULL, 1,"
                + " :sequence)")
        .param("id", id)
        .param("tenantId", tenantId.value())
        .param("at", Timestamp.from(receivedAt))
        .param("sourceId", sourceId)
        .param("kind", kind.key())
        .param("alertKey", entry.alertKey())
        .param("alertType", entry.alertType())
        .param("pairs", pairsJson(entry.identityPairs()))
        .param("title", entry.title() == null ? "" : entry.title())
        .param("firedAt", Timestamp.from(firedAt))
        .param("sequence", sequence)
        .update();
    return id;
  }

  void repeatFiring(TenantId tenantId, UUID alertId, Instant now) {
    jdbc.sql(
            "UPDATE intake.alert SET entry_count = entry_count + 1, updated_at = :now"
                + " WHERE tenant_id = :tenantId AND id = :id")
        .param("now", Timestamp.from(now))
        .param("tenantId", tenantId.value())
        .param("id", alertId)
        .update();
  }

  void resolve(TenantId tenantId, UUID alertId, Instant resolvedAt, Instant receivedAt) {
    jdbc.sql(
            "UPDATE intake.alert SET status = 'resolved', resolved_at = :resolvedAt,"
                + " resolve_received_at = :now, entry_count = entry_count + 1, updated_at = :now"
                + " WHERE tenant_id = :tenantId AND id = :id")
        .param("resolvedAt", Timestamp.from(resolvedAt))
        .param("now", Timestamp.from(receivedAt))
        .param("tenantId", tenantId.value())
        .param("id", alertId)
        .update();
  }

  void insertStateChange(
      TenantId tenantId,
      UUID alertId,
      AlertStatus from,
      AlertStatus to,
      Instant at,
      Instant sourceTime,
      UUID rawAlertId) {
    jdbc.sql(
            "INSERT INTO intake.alert_state_change (id, tenant_id, created_at, alert_id,"
                + " from_status, to_status, at, source_time, raw_alert_id) VALUES (:id,"
                + " :tenantId, :at, :alertId, :from, :to, :at, :sourceTime, :rawId)")
        .param("id", Ids.newId())
        .param("tenantId", tenantId.value())
        .param("at", Timestamp.from(at))
        .param("alertId", alertId)
        .param("from", from == null ? null : from.key())
        .param("to", to.key())
        .param("sourceTime", timestamp(sourceTime))
        .param("rawId", rawAlertId)
        .update();
  }

  List<AlertView> alertsAfter(TenantId tenantId, long sequence, int limit) {
    return jdbc.sql(
            "SELECT * FROM intake.alert WHERE tenant_id = :tenantId AND sequence > :sequence"
                + " ORDER BY sequence LIMIT :limit")
        .param("tenantId", tenantId.value())
        .param("sequence", sequence)
        .param("limit", limit)
        .query(IntakeRecords::alert)
        .list();
  }

  Optional<AlertView> alert(TenantId tenantId, UUID alertId) {
    return jdbc.sql("SELECT * FROM intake.alert WHERE tenant_id = :tenantId AND id = :id")
        .param("tenantId", tenantId.value())
        .param("id", alertId)
        .query(IntakeRecords::alert)
        .optional();
  }

  // Mapping rows.

  static String pairsJson(List<IdentityPair> pairs) {
    ArrayNode array = JSON.createArrayNode();
    for (IdentityPair pair : pairs) {
      ObjectNode object = array.addObject();
      object.put("name", pair.name());
      object.put("value", pair.value());
    }
    return JSON.writeValueAsString(array);
  }

  private static List<IdentityPair> pairs(String json) {
    List<IdentityPair> pairs = new ArrayList<>();
    for (JsonNode pair : JSON.readTree(json)) {
      pairs.add(new IdentityPair(pair.get("name").stringValue(), pair.get("value").stringValue()));
    }
    return pairs;
  }

  private static Timestamp timestamp(Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private static Stored stored(ResultSet rs, int row) throws SQLException {
    String outcome = rs.getString("last_test_outcome");
    return new Stored(
        new SourceView(
            rs.getObject("id", UUID.class),
            rs.getString("source_key"),
            rs.getString("name"),
            MonitoringKind.fromKey(rs.getString("source_kind")).orElseThrow(),
            rs.getObject("connector_instance_id", UUID.class),
            Duration.ofSeconds(rs.getInt("health_check_interval")),
            Duration.ofSeconds(rs.getInt("stale_after")),
            Duration.ofSeconds(rs.getInt("disconnected_after")),
            Health.fromKey(rs.getString("health")).orElseThrow(),
            instant(rs, "health_changed_at"),
            instant(rs, "last_contact_at"),
            instant(rs, "last_test_at"),
            outcome == null ? null : TestOutcome.fromKey(outcome).orElseThrow(),
            rs.getString("last_test_reason"),
            rs.getBoolean("active")),
        rs.getObject("webhook_token_ref_id", UUID.class));
  }

  private static AlertView alert(ResultSet rs, int row) throws SQLException {
    return new AlertView(
        rs.getObject("id", UUID.class),
        rs.getLong("sequence"),
        rs.getObject("source_id", UUID.class),
        MonitoringKind.fromKey(rs.getString("source_kind")).orElseThrow(),
        rs.getString("alert_key"),
        rs.getString("alert_type"),
        pairs(rs.getString("identity_pairs")),
        rs.getString("title"),
        AlertStatus.fromKey(rs.getString("status")).orElseThrow(),
        instant(rs, "fired_at"),
        instant(rs, "first_received_at"),
        instant(rs, "resolved_at"),
        instant(rs, "resolve_received_at"),
        rs.getLong("entry_count"));
  }
}
