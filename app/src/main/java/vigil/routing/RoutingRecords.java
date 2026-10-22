package vigil.routing;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import vigil.incidents.api.Severity;
import vigil.routing.api.EscalationLevel;
import vigil.routing.api.EscalationPolicyView;
import vigil.routing.api.EvaluationView;
import vigil.routing.api.NotAppliedReason;
import vigil.routing.api.RequestedBy;
import vigil.routing.api.RoutingResult;
import vigil.routing.api.RuleSetView;
import vigil.shared.Actor;
import vigil.shared.Ids;
import vigil.shared.TenantId;

/** The tables of {@code routing}; every statement filters by {@code tenant_id}. */
@Component
class RoutingRecords {

  private static final String RULE_SET_COLUMNS =
      "id, revision, rules::text AS rules, saved_by_kind, saved_by_user_id, saved_by_module,"
          + " saved_at, change_summary";
  private static final String EVALUATION_COLUMNS =
      "e.id, e.incident_id, e.requested_by, e.rule_set_revision, e.inputs::text AS inputs,"
          + " e.matched_rule_key, e.result_severity, e.result_owner_team_key,"
          + " e.result_informed_team_keys::text AS result_informed_team_keys, e.applied,"
          + " e.not_applied_reason, e.at, (SELECT rule ->> 'name' FROM"
          + " routing.routing_rule_set_revision r, jsonb_array_elements(r.rules) rule"
          + " WHERE r.tenant_id = e.tenant_id AND r.revision = e.rule_set_revision"
          + " AND rule ->> 'rule_key' = e.matched_rule_key) AS matched_rule_name";

  private final JdbcClient jdbc;

  RoutingRecords(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Serialises the tenant's imports and saves of routing rules until the transaction ends. */
  void lock(TenantId tenantId) {
    jdbc.sql("SELECT pg_advisory_xact_lock(hashtext('routing.rules'), hashtext(:tenant))::text")
        .param("tenant", tenantId.value().toString())
        .query(String.class)
        .single();
  }

  /** True when the tenant has a routing rule set revision or an escalation policy revision. */
  boolean anyRevision(TenantId tenantId) {
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM routing.routing_rule_set_revision WHERE tenant_id = :t)"
                + " OR EXISTS (SELECT 1 FROM routing.escalation_policy_revision"
                + " WHERE tenant_id = :t)")
        .param("t", tenantId.value())
        .query(Boolean.class)
        .single();
  }

  Optional<RuleSetView> currentRuleSet(TenantId tenantId) {
    return jdbc.sql(
            "SELECT "
                + RULE_SET_COLUMNS
                + " FROM routing.routing_rule_set_revision WHERE tenant_id = :t"
                + " ORDER BY revision DESC LIMIT 1")
        .param("t", tenantId.value())
        .query(RoutingRecords::ruleSet)
        .optional();
  }

  Optional<RuleSetView> ruleSet(TenantId tenantId, long revision) {
    return jdbc.sql(
            "SELECT "
                + RULE_SET_COLUMNS
                + " FROM routing.routing_rule_set_revision WHERE tenant_id = :t"
                + " AND revision = :revision")
        .param("t", tenantId.value())
        .param("revision", revision)
        .query(RoutingRecords::ruleSet)
        .optional();
  }

  void insertRuleSet(
      TenantId tenantId,
      UUID id,
      long revision,
      String rulesJson,
      Actor savedBy,
      Instant now,
      String changeSummary) {
    jdbc.sql(
            "INSERT INTO routing.routing_rule_set_revision (id, tenant_id, created_at, revision,"
                + " rules, saved_by_kind, saved_by_user_id, saved_by_module, saved_at,"
                + " change_summary) VALUES (:id, :t, :now, :revision, CAST(:rules AS jsonb),"
                + " :kind, :userId, :module, :now, :summary)")
        .param("id", id)
        .param("t", tenantId.value())
        .param("now", Timestamp.from(now))
        .param("revision", revision)
        .param("rules", rulesJson)
        .param("kind", kind(savedBy))
        .param("userId", savedBy.userId())
        .param("module", savedBy.module())
        .param("summary", changeSummary)
        .update();
  }

  Optional<EscalationPolicyView> currentPolicy(TenantId tenantId) {
    return jdbc.sql(
            "SELECT id, revision, levels::text AS levels, saved_by_kind, saved_by_user_id,"
                + " saved_by_module, saved_at FROM routing.escalation_policy_revision"
                + " WHERE tenant_id = :t ORDER BY revision DESC LIMIT 1")
        .param("t", tenantId.value())
        .query(
            (rs, row) ->
                new EscalationPolicyView(
                    rs.getObject("id", UUID.class),
                    rs.getLong("revision"),
                    RoutingJson.levels(rs.getString("levels")),
                    actor(rs),
                    rs.getTimestamp("saved_at").toInstant()))
        .optional();
  }

  void insertPolicy(
      TenantId tenantId, long revision, List<EscalationLevel> levels, Actor savedBy, Instant now) {
    jdbc.sql(
            "INSERT INTO routing.escalation_policy_revision (id, tenant_id, created_at, revision,"
                + " levels, saved_by_kind, saved_by_user_id, saved_by_module, saved_at)"
                + " VALUES (:id, :t, :now, :revision, CAST(:levels AS jsonb),"
                + " :kind, :userId, :module, :now)")
        .param("id", Ids.newId())
        .param("t", tenantId.value())
        .param("now", Timestamp.from(now))
        .param("revision", revision)
        .param("levels", RoutingJson.levels(levels))
        .param("kind", kind(savedBy))
        .param("userId", savedBy.userId())
        .param("module", savedBy.module())
        .update();
  }

  void insertEvaluation(TenantId tenantId, EvaluationView evaluation) {
    RoutingResult result = evaluation.result();
    NotAppliedReason reason = evaluation.notAppliedReason();
    jdbc.sql(
            "INSERT INTO routing.routing_evaluation (id, tenant_id, created_at, incident_id,"
                + " requested_by, rule_set_revision, inputs, matched_rule_key, result_severity,"
                + " result_owner_team_key, result_informed_team_keys, applied,"
                + " not_applied_reason, at) VALUES (:id, :t, :at, :incidentId, :requestedBy,"
                + " :revision, CAST(:inputs AS jsonb), :ruleKey, :severity, :owner,"
                + " CAST(:informed AS jsonb), :applied, :reason, :at)")
        .param("id", evaluation.id())
        .param("t", tenantId.value())
        .param("at", Timestamp.from(evaluation.at()))
        .param("incidentId", evaluation.incidentId())
        .param("requestedBy", evaluation.requestedBy().key())
        .param("revision", evaluation.ruleSetRevision())
        .param("inputs", evaluation.inputsJson())
        .param("ruleKey", evaluation.matchedRuleKey())
        .param("severity", result.severity().key())
        .param("owner", result.ownerTeamKey())
        .param("informed", RoutingJson.keys(result.informedTeamKeys()))
        .param("applied", evaluation.applied())
        .param("reason", reason == null ? null : reason.key())
        .update();
  }

  List<EvaluationView> evaluations(TenantId tenantId, UUID incidentId) {
    return jdbc.sql(
            "SELECT "
                + EVALUATION_COLUMNS
                + " FROM routing.routing_evaluation e WHERE e.tenant_id = :t"
                + " AND e.incident_id = :incidentId ORDER BY e.at, e.id")
        .param("t", tenantId.value())
        .param("incidentId", incidentId)
        .query(RoutingRecords::evaluation)
        .list();
  }

  Optional<EvaluationView> evaluation(TenantId tenantId, UUID id) {
    return jdbc.sql(
            "SELECT "
                + EVALUATION_COLUMNS
                + " FROM routing.routing_evaluation e WHERE e.tenant_id = :t AND e.id = :id")
        .param("t", tenantId.value())
        .param("id", id)
        .query(RoutingRecords::evaluation)
        .optional();
  }

  private static RuleSetView ruleSet(ResultSet rs, int row) throws SQLException {
    return new RuleSetView(
        rs.getObject("id", UUID.class),
        rs.getLong("revision"),
        RoutingJson.rules(rs.getString("rules")),
        actor(rs),
        rs.getTimestamp("saved_at").toInstant(),
        rs.getString("change_summary"));
  }

  private static EvaluationView evaluation(ResultSet rs, int row) throws SQLException {
    String reason = rs.getString("not_applied_reason");
    return new EvaluationView(
        rs.getObject("id", UUID.class),
        rs.getObject("incident_id", UUID.class),
        RequestedBy.fromKey(rs.getString("requested_by")).orElseThrow(),
        rs.getLong("rule_set_revision"),
        rs.getString("inputs"),
        rs.getString("matched_rule_key"),
        rs.getString("matched_rule_name"),
        new RoutingResult(
            Severity.fromKey(rs.getString("result_severity")).orElseThrow(),
            rs.getString("result_owner_team_key"),
            RoutingJson.strings(rs.getString("result_informed_team_keys"))),
        rs.getBoolean("applied"),
        reason == null ? null : NotAppliedReason.fromKey(reason).orElseThrow(),
        rs.getTimestamp("at").toInstant());
  }

  private static String kind(Actor actor) {
    return actor.kind() == Actor.Kind.USER ? "user" : "module";
  }

  private static Actor actor(ResultSet rs) throws SQLException {
    return rs.getString("saved_by_kind").equals("user")
        ? Actor.user(rs.getObject("saved_by_user_id", UUID.class))
        : Actor.module(rs.getString("saved_by_module"));
  }
}
