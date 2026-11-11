package vigil.routing;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import vigil.shared.Ids;
import vigil.shared.TenantId;

/**
 * The technical table {@code routing.step_escalation} (CTX-D-071 (2)): the escalation each
 * Escalation step execution recorded, by round.
 */
@Component
class StepEscalations {

  /** One round of a step execution. */
  record Round(UUID stepExecutionId, long round, UUID escalationId, long level, Instant at) {}

  private final JdbcClient jdbc;

  StepEscalations(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  Optional<Round> round(TenantId tenantId, UUID stepExecutionId, long round) {
    return jdbc.sql(
            "SELECT step_execution_id, round, escalation_id, level, at FROM routing.step_escalation"
                + " WHERE tenant_id = :t AND step_execution_id = :s AND round = :r")
        .param("t", tenantId.value())
        .param("s", stepExecutionId)
        .param("r", round)
        .query(
            (rs, row) ->
                new Round(
                    rs.getObject("step_execution_id", UUID.class),
                    rs.getLong("round"),
                    rs.getObject("escalation_id", UUID.class),
                    rs.getLong("level"),
                    rs.getTimestamp("at").toInstant()))
        .optional();
  }

  void insert(TenantId tenantId, UUID incidentId, Round round) {
    jdbc.sql(
            "INSERT INTO routing.step_escalation (id, tenant_id, created_at, incident_id,"
                + " step_execution_id, round, escalation_id, level, at) VALUES (:id, :t, :at,"
                + " :incident, :s, :r, :e, :level, :at)")
        .param("id", Ids.newId())
        .param("t", tenantId.value())
        .param("at", Timestamp.from(round.at()))
        .param("incident", incidentId)
        .param("s", round.stepExecutionId())
        .param("r", round.round())
        .param("e", round.escalationId())
        .param("level", round.level())
        .update();
  }
}
