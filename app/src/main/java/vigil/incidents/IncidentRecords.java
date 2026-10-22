package vigil.incidents;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import vigil.incidents.api.AcknowledgementView;
import vigil.incidents.api.AiStatus;
import vigil.incidents.api.AiUnavailableReason;
import vigil.incidents.api.DataCategory;
import vigil.incidents.api.EscalationCause;
import vigil.incidents.api.EscalationView;
import vigil.incidents.api.EvidenceKind;
import vigil.incidents.api.EvidenceView;
import vigil.incidents.api.IncidentSettingsView;
import vigil.incidents.api.IncidentState;
import vigil.incidents.api.MembershipView;
import vigil.incidents.api.NotifiedPurpose;
import vigil.incidents.api.NotifiedTeamView;
import vigil.incidents.api.Severity;
import vigil.incidents.api.TimelineEntryType;
import vigil.incidents.api.TimelineView;
import vigil.incidents.api.TransitionView;
import vigil.shared.Actor;
import vigil.shared.Ids;
import vigil.shared.TenantId;

/** The tables of {@code incidents}; every statement filters by {@code tenant_id}. */
@Component
class IncidentRecords {

  private static final String INCIDENT_COLUMNS =
      "id, sequence, number, title, state, state_changed_at, first_alert_fired_at,"
          + " last_alert_joined_at, stable_at, alert_count, alert_types::text AS alert_types,"
          + " alert_sources::text AS alert_sources, hypothesis_component_key,"
          + " hypothesis_snapshot::text AS hypothesis_snapshot, hypothesis_computation_id,"
          + " affected_services::text AS affected_services, severity, owner_team_key,"
          + " informed_team_keys::text AS informed_team_keys, assignment_evaluation_id,"
          + " assignment_frozen, escalation_level, acknowledged_at, acknowledged_by, ai_status,"
          + " ai_unavailable_reason, ai_status_changed_at, ai_last_request_group_id, resolved_at,"
          + " resolved_by_verification_id, closed_at, merged_into_incident_id, reopen_count,"
          + " created_at, updated_at";
  private static final String MEMBERSHIP_COLUMNS =
      "id, incident_id, alert_id, alert_snapshot::text AS alert_snapshot, grouping_decision_id,"
          + " reason::text AS reason, final_reason::text AS final_reason, joined_at, ended_at";

  private final JdbcClient jdbc;

  IncidentRecords(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Serialises the transactions that take the same lock of the tenant until they end. */
  void advisoryLock(String name, TenantId tenantId) {
    jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(:name), hashtext(:tenant))::text")
        .param("name", name)
        .param("tenant", tenantId.value().toString())
        .query(String.class)
        .single();
  }

  // Incidents.

  Optional<IncidentRow> incident(TenantId tenantId, UUID id) {
    return jdbc.sql(
            "SELECT "
                + INCIDENT_COLUMNS
                + " FROM incidents.incident WHERE tenant_id = :tenantId AND id = :id")
        .param("tenantId", tenantId.value())
        .param("id", id)
        .query(IncidentRecords::incident)
        .optional();
  }

  /** The incident row, locked until the end of the transaction. */
  Optional<IncidentRow> lockIncident(TenantId tenantId, UUID id) {
    return jdbc.sql(
            "SELECT "
                + INCIDENT_COLUMNS
                + " FROM incidents.incident WHERE tenant_id = :tenantId AND id = :id FOR UPDATE")
        .param("tenantId", tenantId.value())
        .param("id", id)
        .query(IncidentRecords::incident)
        .optional();
  }

  /** The incident rows, locked in ascending {@code sequence} order. */
  List<IncidentRow> lockIncidents(TenantId tenantId, Collection<UUID> ids) {
    return jdbc.sql(
            "SELECT "
                + INCIDENT_COLUMNS
                + " FROM incidents.incident WHERE tenant_id = :tenantId AND id IN (:ids)"
                + " ORDER BY sequence FOR UPDATE")
        .param("tenantId", tenantId.value())
        .param("ids", List.copyOf(ids))
        .query(IncidentRecords::incident)
        .list();
  }

  List<IncidentRow> incidents(TenantId tenantId, Set<IncidentState> states) {
    return jdbc.sql(
            "SELECT "
                + INCIDENT_COLUMNS
                + " FROM incidents.incident WHERE tenant_id = :tenantId AND state IN (:states)"
                + " ORDER BY sequence")
        .param("tenantId", tenantId.value())
        .param("states", states.stream().map(IncidentState::key).toList())
        .query(IncidentRecords::incident)
        .list();
  }

  long lastSequence(TenantId tenantId) {
    return jdbc.sql(
            "SELECT coalesce(max(sequence), 0) FROM incidents.incident WHERE tenant_id = :tenantId")
        .param("tenantId", tenantId.value())
        .query(Long.class)
        .single();
  }

  long lastNumber(TenantId tenantId) {
    return jdbc.sql(
            "SELECT coalesce(max(number), 0) FROM incidents.incident WHERE tenant_id = :tenantId")
        .param("tenantId", tenantId.value())
        .query(Long.class)
        .single();
  }

  void insertIncident(TenantId tenantId, IncidentRow row) {
    jdbc.sql(
            "INSERT INTO incidents.incident (id, tenant_id, created_at, updated_at, sequence,"
                + " number, title, state, state_changed_at, first_alert_fired_at,"
                + " last_alert_joined_at, stable_at, alert_count, alert_types, alert_sources,"
                + " hypothesis_component_key, hypothesis_snapshot, hypothesis_computation_id,"
                + " affected_services, severity, owner_team_key, informed_team_keys,"
                + " assignment_evaluation_id, assignment_frozen, escalation_level,"
                + " acknowledged_at, acknowledged_by, ai_status, ai_unavailable_reason,"
                + " ai_status_changed_at, ai_last_request_group_id, resolved_at,"
                + " resolved_by_verification_id, closed_at, merged_into_incident_id,"
                + " reopen_count) VALUES (:id, :tenantId, :createdAt, :updatedAt, :sequence,"
                + " :number, :title, :state, :stateChangedAt, :firstAlertFiredAt,"
                + " :lastAlertJoinedAt, :stableAt, :alertCount, CAST(:alertTypes AS jsonb),"
                + " CAST(:alertSources AS jsonb), :hypothesisComponentKey,"
                + " CAST(:hypothesisSnapshot AS jsonb), :hypothesisComputationId,"
                + " CAST(:affectedServices AS jsonb), :severity, :ownerTeamKey,"
                + " CAST(:informedTeamKeys AS jsonb), :assignmentEvaluationId,"
                + " :assignmentFrozen, :escalationLevel, :acknowledgedAt, :acknowledgedBy,"
                + " :aiStatus, :aiUnavailableReason, :aiStatusChangedAt, :aiLastRequestGroupId,"
                + " :resolvedAt, :resolvedByVerificationId, :closedAt, :mergedIntoIncidentId,"
                + " :reopenCount)")
        .params(params(tenantId, row))
        .param("createdAt", Timestamp.from(row.createdAt))
        .param("sequence", row.sequence)
        .update();
  }

  /** Writes every changeable field of the row. */
  void updateIncident(TenantId tenantId, IncidentRow row) {
    jdbc.sql(
            "UPDATE incidents.incident SET updated_at = :updatedAt, number = :number,"
                + " title = :title, state = :state, state_changed_at = :stateChangedAt,"
                + " first_alert_fired_at = :firstAlertFiredAt,"
                + " last_alert_joined_at = :lastAlertJoinedAt, stable_at = :stableAt,"
                + " alert_count = :alertCount, alert_types = CAST(:alertTypes AS jsonb),"
                + " alert_sources = CAST(:alertSources AS jsonb),"
                + " hypothesis_component_key = :hypothesisComponentKey,"
                + " hypothesis_snapshot = CAST(:hypothesisSnapshot AS jsonb),"
                + " hypothesis_computation_id = :hypothesisComputationId,"
                + " affected_services = CAST(:affectedServices AS jsonb), severity = :severity,"
                + " owner_team_key = :ownerTeamKey,"
                + " informed_team_keys = CAST(:informedTeamKeys AS jsonb),"
                + " assignment_evaluation_id = :assignmentEvaluationId,"
                + " assignment_frozen = :assignmentFrozen, escalation_level = :escalationLevel,"
                + " acknowledged_at = :acknowledgedAt, acknowledged_by = :acknowledgedBy,"
                + " ai_status = :aiStatus, ai_unavailable_reason = :aiUnavailableReason,"
                + " ai_status_changed_at = :aiStatusChangedAt,"
                + " ai_last_request_group_id = :aiLastRequestGroupId, resolved_at = :resolvedAt,"
                + " resolved_by_verification_id = :resolvedByVerificationId,"
                + " closed_at = :closedAt, merged_into_incident_id = :mergedIntoIncidentId,"
                + " reopen_count = :reopenCount WHERE tenant_id = :tenantId AND id = :id")
        .params(params(tenantId, row))
        .update();
  }

  private static Map<String, Object> params(TenantId tenantId, IncidentRow row) {
    Map<String, Object> params = new HashMap<>();
    params.put("id", row.id);
    params.put("tenantId", tenantId.value());
    params.put("updatedAt", Timestamp.from(row.updatedAt));
    params.put("number", row.number);
    params.put("title", row.title);
    params.put("state", row.state.key());
    params.put("stateChangedAt", Timestamp.from(row.stateChangedAt));
    params.put("firstAlertFiredAt", Timestamp.from(row.firstAlertFiredAt));
    params.put("lastAlertJoinedAt", Timestamp.from(row.lastAlertJoinedAt));
    params.put("stableAt", timestamp(row.stableAt));
    params.put("alertCount", row.alertCount);
    params.put("alertTypes", IncidentJson.keys(row.alertTypes));
    params.put("alertSources", IncidentJson.keys(row.alertSources));
    params.put(
        "hypothesisComponentKey", row.hypothesis == null ? null : row.hypothesis.componentKey());
    params.put("hypothesisSnapshot", IncidentJson.hypothesisSnapshot(row.hypothesis));
    params.put("hypothesisComputationId", row.hypothesisComputationId);
    params.put("affectedServices", IncidentJson.affectedServices(row.affectedServices));
    params.put("severity", row.severity == null ? null : row.severity.key());
    params.put("ownerTeamKey", row.ownerTeamKey);
    params.put("informedTeamKeys", IncidentJson.keys(row.informedTeamKeys));
    params.put("assignmentEvaluationId", row.assignmentEvaluationId);
    params.put("assignmentFrozen", row.assignmentFrozen);
    params.put("escalationLevel", row.escalationLevel);
    params.put("acknowledgedAt", timestamp(row.acknowledgedAt));
    params.put("acknowledgedBy", row.acknowledgedBy);
    params.put("aiStatus", row.aiStatus.key());
    params.put(
        "aiUnavailableReason",
        row.aiUnavailableReason == null ? null : row.aiUnavailableReason.key());
    params.put("aiStatusChangedAt", timestamp(row.aiStatusChangedAt));
    params.put("aiLastRequestGroupId", row.aiLastRequestGroupId);
    params.put("resolvedAt", timestamp(row.resolvedAt));
    params.put("resolvedByVerificationId", row.resolvedByVerificationId);
    params.put("closedAt", timestamp(row.closedAt));
    params.put("mergedIntoIncidentId", row.mergedIntoIncidentId);
    params.put("reopenCount", row.reopenCount);
    return params;
  }

  private static IncidentRow incident(ResultSet rs, int rowNum) throws SQLException {
    IncidentRow row = new IncidentRow();
    row.id = rs.getObject("id", UUID.class);
    row.sequence = rs.getLong("sequence");
    row.number = rs.getObject("number", Long.class);
    row.title = rs.getString("title");
    row.state = IncidentState.fromKey(rs.getString("state")).orElseThrow();
    row.stateChangedAt = instant(rs, "state_changed_at");
    row.firstAlertFiredAt = instant(rs, "first_alert_fired_at");
    row.lastAlertJoinedAt = instant(rs, "last_alert_joined_at");
    row.stableAt = instant(rs, "stable_at");
    row.alertCount = rs.getLong("alert_count");
    row.alertTypes = IncidentJson.strings(rs.getString("alert_types"));
    row.alertSources = IncidentJson.strings(rs.getString("alert_sources"));
    row.hypothesis =
        IncidentJson.hypothesis(
            rs.getString("hypothesis_component_key"), rs.getString("hypothesis_snapshot"));
    row.hypothesisComputationId = rs.getObject("hypothesis_computation_id", UUID.class);
    row.affectedServices = IncidentJson.affectedServices(rs.getString("affected_services"));
    String severity = rs.getString("severity");
    row.severity = severity == null ? null : Severity.fromKey(severity).orElseThrow();
    row.ownerTeamKey = rs.getString("owner_team_key");
    row.informedTeamKeys = IncidentJson.strings(rs.getString("informed_team_keys"));
    row.assignmentEvaluationId = rs.getObject("assignment_evaluation_id", UUID.class);
    row.assignmentFrozen = rs.getBoolean("assignment_frozen");
    row.escalationLevel = rs.getLong("escalation_level");
    row.acknowledgedAt = instant(rs, "acknowledged_at");
    row.acknowledgedBy = rs.getObject("acknowledged_by", UUID.class);
    row.aiStatus = AiStatus.fromKey(rs.getString("ai_status")).orElseThrow();
    String reason = rs.getString("ai_unavailable_reason");
    row.aiUnavailableReason =
        reason == null ? null : AiUnavailableReason.fromKey(reason).orElseThrow();
    row.aiStatusChangedAt = instant(rs, "ai_status_changed_at");
    row.aiLastRequestGroupId = rs.getObject("ai_last_request_group_id", UUID.class);
    row.resolvedAt = instant(rs, "resolved_at");
    row.resolvedByVerificationId = rs.getObject("resolved_by_verification_id", UUID.class);
    row.closedAt = instant(rs, "closed_at");
    row.mergedIntoIncidentId = rs.getObject("merged_into_incident_id", UUID.class);
    row.reopenCount = rs.getLong("reopen_count");
    row.createdAt = instant(rs, "created_at");
    row.updatedAt = instant(rs, "updated_at");
    return row;
  }

  // Memberships.

  /** The membership with its incident. */
  record Membership(UUID incidentId, MembershipView view) {}

  Optional<Membership> activeMembership(TenantId tenantId, UUID alertId) {
    return jdbc.sql(
            "SELECT "
                + MEMBERSHIP_COLUMNS
                + " FROM incidents.incident_alert_membership WHERE tenant_id = :tenantId"
                + " AND alert_id = :alertId AND ended_at IS NULL")
        .param("tenantId", tenantId.value())
        .param("alertId", alertId)
        .query(IncidentRecords::membership)
        .optional();
  }

  /** The incident's memberships (only the active ones, or all), by {@code joined_at}, then id. */
  List<MembershipView> memberships(TenantId tenantId, UUID incidentId, boolean activeOnly) {
    return jdbc
        .sql(
            "SELECT "
                + MEMBERSHIP_COLUMNS
                + " FROM incidents.incident_alert_membership WHERE tenant_id = :tenantId"
                + " AND incident_id = :incidentId"
                + (activeOnly ? " AND ended_at IS NULL" : "")
                + " ORDER BY joined_at, id")
        .param("tenantId", tenantId.value())
        .param("incidentId", incidentId)
        .query(IncidentRecords::membership)
        .list()
        .stream()
        .map(Membership::view)
        .toList();
  }

  UUID insertMembership(
      TenantId tenantId, UUID incidentId, MembershipView membership, Instant now) {
    UUID id = Ids.newId();
    jdbc.sql(
            "INSERT INTO incidents.incident_alert_membership (id, tenant_id, created_at,"
                + " updated_at, incident_id, alert_id, alert_snapshot, grouping_decision_id,"
                + " reason, joined_at, final_reason, ended_at) VALUES (:id, :tenantId, :now,"
                + " :now, :incidentId, :alertId, CAST(:snapshot AS jsonb), :decisionId,"
                + " CAST(:reason AS jsonb), :now, CAST(:finalReason AS jsonb), NULL)")
        .param("id", id)
        .param("tenantId", tenantId.value())
        .param("now", Timestamp.from(now))
        .param("incidentId", incidentId)
        .param("alertId", membership.alertId())
        .param("snapshot", IncidentJson.alertSnapshot(membership.alert()))
        .param("decisionId", membership.groupingDecisionId())
        .param("reason", membership.reasonJson())
        .param("finalReason", membership.finalReasonJson())
        .update();
    return id;
  }

  void endMembership(TenantId tenantId, UUID id, Instant now) {
    jdbc.sql(
            "UPDATE incidents.incident_alert_membership SET ended_at = :now, updated_at = :now"
                + " WHERE tenant_id = :tenantId AND id = :id")
        .param("now", Timestamp.from(now))
        .param("tenantId", tenantId.value())
        .param("id", id)
        .update();
  }

  void setFinalReason(TenantId tenantId, UUID id, String finalReason, Instant now) {
    jdbc.sql(
            "UPDATE incidents.incident_alert_membership SET final_reason ="
                + " CAST(:finalReason AS jsonb), updated_at = :now"
                + " WHERE tenant_id = :tenantId AND id = :id")
        .param("finalReason", finalReason)
        .param("now", Timestamp.from(now))
        .param("tenantId", tenantId.value())
        .param("id", id)
        .update();
  }

  private static Membership membership(ResultSet rs, int rowNum) throws SQLException {
    UUID alertId = rs.getObject("alert_id", UUID.class);
    return new Membership(
        rs.getObject("incident_id", UUID.class),
        new MembershipView(
            rs.getObject("id", UUID.class),
            alertId,
            IncidentJson.alertCopy(alertId, rs.getString("alert_snapshot")),
            rs.getString("reason"),
            rs.getString("final_reason"),
            rs.getObject("grouping_decision_id", UUID.class),
            instant(rs, "joined_at"),
            instant(rs, "ended_at")));
  }

  // State transitions and timeline entries.

  void insertTransition(
      TenantId tenantId,
      UUID incidentId,
      String transitionId,
      IncidentState from,
      IncidentState to,
      Instant at,
      Actor actor,
      String reason) {
    jdbc.sql(
            "INSERT INTO incidents.incident_state_transition (id, tenant_id, created_at,"
                + " incident_id, transition_id, from_state, to_state, at, actor_kind,"
                + " actor_user_id, actor_module, reason) VALUES (:id, :tenantId, :at,"
                + " :incidentId, :transitionId, :from, :to, :at, :kind, :userId, :module,"
                + " :reason)")
        .param("id", Ids.newId())
        .param("tenantId", tenantId.value())
        .param("at", Timestamp.from(at))
        .param("incidentId", incidentId)
        .param("transitionId", transitionId)
        .param("from", from == null ? null : from.key())
        .param("to", to.key())
        .param("kind", actorKind(actor))
        .param("userId", actor.userId())
        .param("module", actor.module())
        .param("reason", reason)
        .update();
  }

  List<TransitionView> transitions(TenantId tenantId, UUID incidentId) {
    return jdbc.sql(
            "SELECT * FROM incidents.incident_state_transition WHERE tenant_id = :tenantId"
                + " AND incident_id = :incidentId ORDER BY at, id")
        .param("tenantId", tenantId.value())
        .param("incidentId", incidentId)
        .query(
            (rs, rowNum) -> {
              String from = rs.getString("from_state");
              return new TransitionView(
                  rs.getObject("id", UUID.class),
                  rs.getObject("incident_id", UUID.class),
                  rs.getString("transition_id"),
                  from == null ? null : IncidentState.fromKey(from).orElseThrow(),
                  IncidentState.fromKey(rs.getString("to_state")).orElseThrow(),
                  instant(rs, "at"),
                  actor(rs, "actor"),
                  rs.getString("reason"));
            })
        .list();
  }

  void insertTimeline(
      TenantId tenantId,
      UUID incidentId,
      Instant at,
      Actor actor,
      TimelineEntryType type,
      String text,
      String refType,
      UUID refId) {
    jdbc.sql(
            "INSERT INTO incidents.timeline_entry (id, tenant_id, created_at, incident_id, at,"
                + " actor_kind, actor_user_id, actor_module, entry_type, text, ref_type, ref_id)"
                + " VALUES (:id, :tenantId, :at, :incidentId, :at, :kind, :userId, :module,"
                + " :type, :text, :refType, :refId)")
        .param("id", Ids.newId())
        .param("tenantId", tenantId.value())
        .param("at", Timestamp.from(at))
        .param("incidentId", incidentId)
        .param("kind", actorKind(actor))
        .param("userId", actor.userId())
        .param("module", actor.module())
        .param("type", type.key())
        .param("text", text)
        .param("refType", refType)
        .param("refId", refId)
        .update();
  }

  List<TimelineView> timeline(TenantId tenantId, UUID incidentId) {
    return jdbc.sql(
            "SELECT * FROM incidents.timeline_entry WHERE tenant_id = :tenantId"
                + " AND incident_id = :incidentId ORDER BY at, id")
        .param("tenantId", tenantId.value())
        .param("incidentId", incidentId)
        .query(
            (rs, rowNum) ->
                new TimelineView(
                    rs.getObject("id", UUID.class),
                    rs.getObject("incident_id", UUID.class),
                    instant(rs, "at"),
                    actor(rs, "actor"),
                    TimelineEntryType.fromKey(rs.getString("entry_type")).orElseThrow(),
                    rs.getString("text"),
                    rs.getString("ref_type"),
                    rs.getObject("ref_id", UUID.class)))
        .list();
  }

  // Acknowledgements, escalations and notified teams.

  UUID insertAcknowledgement(
      TenantId tenantId, UUID incidentId, UUID userId, String teamKey, long episode, Instant at) {
    UUID id = Ids.newId();
    jdbc.sql(
            "INSERT INTO incidents.acknowledgement (id, tenant_id, created_at, incident_id,"
                + " user_id, team_key, episode, at) VALUES (:id, :tenantId, :at, :incidentId,"
                + " :userId, :teamKey, :episode, :at)")
        .param("id", id)
        .param("tenantId", tenantId.value())
        .param("at", Timestamp.from(at))
        .param("incidentId", incidentId)
        .param("userId", userId)
        .param("teamKey", teamKey)
        .param("episode", episode)
        .update();
    return id;
  }

  List<AcknowledgementView> acknowledgements(TenantId tenantId, UUID incidentId) {
    return jdbc.sql(
            "SELECT * FROM incidents.acknowledgement WHERE tenant_id = :tenantId"
                + " AND incident_id = :incidentId ORDER BY episode")
        .param("tenantId", tenantId.value())
        .param("incidentId", incidentId)
        .query(
            (rs, rowNum) ->
                new AcknowledgementView(
                    rs.getObject("id", UUID.class),
                    rs.getObject("incident_id", UUID.class),
                    rs.getObject("user_id", UUID.class),
                    rs.getString("team_key"),
                    rs.getLong("episode"),
                    instant(rs, "at")))
        .list();
  }

  UUID insertEscalation(
      TenantId tenantId,
      UUID incidentId,
      long level,
      Collection<String> teamKeys,
      EscalationCause cause,
      Duration timer,
      long policyRevision,
      Instant at) {
    UUID id = Ids.newId();
    jdbc.sql(
            "INSERT INTO incidents.incident_escalation (id, tenant_id, created_at, incident_id,"
                + " level, team_keys, cause, timer, policy_revision, at) VALUES (:id, :tenantId,"
                + " :at, :incidentId, :level, CAST(:teamKeys AS jsonb), :cause, :timer,"
                + " :policyRevision, :at)")
        .param("id", id)
        .param("tenantId", tenantId.value())
        .param("at", Timestamp.from(at))
        .param("incidentId", incidentId)
        .param("level", level)
        .param("teamKeys", IncidentJson.keys(teamKeys))
        .param("cause", cause.key())
        .param("timer", timer == null ? null : (int) timer.toSeconds())
        .param("policyRevision", policyRevision)
        .update();
    return id;
  }

  List<EscalationView> escalations(TenantId tenantId, UUID incidentId) {
    return jdbc.sql(
            "SELECT id, incident_id, level, team_keys::text AS team_keys, cause, timer,"
                + " policy_revision, at FROM incidents.incident_escalation"
                + " WHERE tenant_id = :tenantId AND incident_id = :incidentId ORDER BY at, id")
        .param("tenantId", tenantId.value())
        .param("incidentId", incidentId)
        .query(
            (rs, rowNum) -> {
              Integer timer = rs.getObject("timer", Integer.class);
              return new EscalationView(
                  rs.getObject("id", UUID.class),
                  rs.getObject("incident_id", UUID.class),
                  rs.getLong("level"),
                  IncidentJson.strings(rs.getString("team_keys")),
                  EscalationCause.fromKey(rs.getString("cause")).orElseThrow(),
                  timer == null ? null : Duration.ofSeconds(timer),
                  rs.getLong("policy_revision"),
                  instant(rs, "at"));
            })
        .list();
  }

  UUID insertNotifiedTeam(
      TenantId tenantId, UUID incidentId, String teamKey, NotifiedPurpose purpose, Instant at) {
    UUID id = Ids.newId();
    jdbc.sql(
            "INSERT INTO incidents.notified_team (id, tenant_id, created_at, incident_id,"
                + " team_key, first_notified_at, purpose) VALUES (:id, :tenantId, :at,"
                + " :incidentId, :teamKey, :at, :purpose)")
        .param("id", id)
        .param("tenantId", tenantId.value())
        .param("at", Timestamp.from(at))
        .param("incidentId", incidentId)
        .param("teamKey", teamKey)
        .param("purpose", purpose.key())
        .update();
    return id;
  }

  List<NotifiedTeamView> notifiedTeams(TenantId tenantId, UUID incidentId) {
    return jdbc.sql(
            "SELECT * FROM incidents.notified_team WHERE tenant_id = :tenantId"
                + " AND incident_id = :incidentId ORDER BY first_notified_at, team_key COLLATE \"C\"")
        .param("tenantId", tenantId.value())
        .param("incidentId", incidentId)
        .query(
            (rs, rowNum) ->
                new NotifiedTeamView(
                    rs.getObject("id", UUID.class),
                    rs.getObject("incident_id", UUID.class),
                    rs.getString("team_key"),
                    instant(rs, "first_notified_at"),
                    NotifiedPurpose.fromKey(rs.getString("purpose")).orElseThrow()))
        .list();
  }

  // Evidence entries.

  Optional<UUID> evidenceOf(TenantId tenantId, String producerModule, UUID producerRecordId) {
    return jdbc.sql(
            "SELECT id FROM incidents.evidence_entry WHERE tenant_id = :tenantId"
                + " AND producer_module = :module AND producer_record_id = :recordId")
        .param("tenantId", tenantId.value())
        .param("module", producerModule)
        .param("recordId", producerRecordId)
        .query(UUID.class)
        .optional();
  }

  UUID insertEvidence(TenantId tenantId, EvidenceView entry, Instant now) {
    UUID id = Ids.newId();
    jdbc.sql(
            "INSERT INTO incidents.evidence_entry (id, tenant_id, created_at, incident_id, kind,"
                + " producer_module, producer_record_id, source_system, collected_at, summary,"
                + " content, data_category) VALUES (:id, :tenantId, :now, :incidentId, :kind,"
                + " :module, :recordId, :sourceSystem, :collectedAt, :summary,"
                + " CAST(:content AS jsonb), :category)")
        .param("id", id)
        .param("tenantId", tenantId.value())
        .param("now", Timestamp.from(now))
        .param("incidentId", entry.incidentId())
        .param("kind", entry.kind().key())
        .param("module", entry.producerModule())
        .param("recordId", entry.producerRecordId())
        .param("sourceSystem", entry.sourceSystem())
        .param("collectedAt", Timestamp.from(entry.collectedAt()))
        .param("summary", entry.summary())
        .param("content", entry.contentJson())
        .param("category", entry.dataCategory().key())
        .update();
    return id;
  }

  List<EvidenceView> evidence(TenantId tenantId, UUID incidentId) {
    return jdbc.sql(
            "SELECT id, incident_id, kind, producer_module, producer_record_id, source_system,"
                + " collected_at, summary, content::text AS content, data_category"
                + " FROM incidents.evidence_entry WHERE tenant_id = :tenantId"
                + " AND incident_id = :incidentId ORDER BY collected_at, id")
        .param("tenantId", tenantId.value())
        .param("incidentId", incidentId)
        .query(
            (rs, rowNum) ->
                new EvidenceView(
                    rs.getObject("id", UUID.class),
                    rs.getObject("incident_id", UUID.class),
                    EvidenceKind.fromKey(rs.getString("kind")).orElseThrow(),
                    rs.getString("producer_module"),
                    rs.getObject("producer_record_id", UUID.class),
                    rs.getString("source_system"),
                    instant(rs, "collected_at"),
                    rs.getString("summary"),
                    rs.getString("content"),
                    DataCategory.fromKey(rs.getString("data_category")).orElseThrow()))
        .list();
  }

  // Settings.

  Optional<IncidentSettingsView> settings(TenantId tenantId) {
    return jdbc.sql(
            "SELECT reopen_window, closure_check_interval FROM incidents.incident_settings"
                + " WHERE tenant_id = :tenantId")
        .param("tenantId", tenantId.value())
        .query(
            (rs, rowNum) ->
                new IncidentSettingsView(
                    Duration.ofSeconds(rs.getInt("reopen_window")),
                    Duration.ofSeconds(rs.getInt("closure_check_interval"))))
        .optional();
  }

  void insertSettings(TenantId tenantId, int reopenWindow, int closureCheckInterval, Instant now) {
    jdbc.sql(
            "INSERT INTO incidents.incident_settings (id, tenant_id, created_at, updated_at,"
                + " reopen_window, closure_check_interval) VALUES (:id, :tenantId, :now, :now,"
                + " :reopenWindow, :interval)")
        .param("id", Ids.newId())
        .param("tenantId", tenantId.value())
        .param("now", Timestamp.from(now))
        .param("reopenWindow", reopenWindow)
        .param("interval", closureCheckInterval)
        .update();
  }

  // Columns.

  private static String actorKind(Actor actor) {
    return actor.kind() == Actor.Kind.USER ? "user" : "module";
  }

  private static Actor actor(ResultSet rs, String field) throws SQLException {
    return rs.getString(field + "_kind").equals("user")
        ? Actor.user(rs.getObject(field + "_user_id", UUID.class))
        : Actor.module(rs.getString(field + "_module"));
  }

  private static Timestamp timestamp(Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }
}
