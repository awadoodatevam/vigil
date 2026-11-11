package vigil.ticketing;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import vigil.shared.TenantId;
import vigil.ticketing.api.SyncOperation;
import vigil.ticketing.api.SyncOutcome;
import vigil.ticketing.api.SyncRecordView;
import vigil.ticketing.api.SyncRequester;
import vigil.ticketing.api.TicketLinkState;
import vigil.ticketing.api.TicketLinkView;

/** The tables of {@code ticketing}; every statement filters by {@code tenant_id}. */
@Component
class TicketingRecords {

  private static final String LINK_COLUMNS =
      "id, incident_id, connector_instance_id, external_key, url, state, created_at, updated_at";
  private static final String RECORD_COLUMNS =
      "id, incident_id, ticket_link_id, operation, requested_by, step_execution_id,"
          + " escalation_id, content_sent::text AS content_sent, outcome, error, at, finished_at";

  private final JdbcClient jdbc;

  TicketingRecords(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  Optional<TicketLinkView> link(TenantId tenantId, UUID incidentId) {
    return jdbc.sql(
            "SELECT "
                + LINK_COLUMNS
                + " FROM ticketing.ticket_link WHERE tenant_id = :t AND incident_id = :i")
        .param("t", tenantId.value())
        .param("i", incidentId)
        .query(TicketingRecords::link)
        .optional();
  }

  void insertLink(TenantId tenantId, TicketLinkView link) {
    jdbc.sql(
            "INSERT INTO ticketing.ticket_link (id, tenant_id, created_at, updated_at,"
                + " incident_id, connector_instance_id, external_key, url, state)"
                + " VALUES (:id, :t, :created, :updated, :incident, :instance, :key, :url,"
                + " :state)")
        .param("id", link.id())
        .param("t", tenantId.value())
        .param("created", Timestamp.from(link.createdAt()))
        .param("updated", Timestamp.from(link.updatedAt()))
        .param("incident", link.incidentId())
        .param("instance", link.connectorInstanceId())
        .param("key", link.externalKey())
        .param("url", link.url())
        .param("state", link.state().key())
        .update();
  }

  void setLinkState(TenantId tenantId, UUID linkId, TicketLinkState state, Instant now) {
    jdbc.sql(
            "UPDATE ticketing.ticket_link SET state = :state, updated_at = :now"
                + " WHERE tenant_id = :t AND id = :id")
        .param("state", state.key())
        .param("now", Timestamp.from(now))
        .param("t", tenantId.value())
        .param("id", linkId)
        .update();
  }

  List<SyncRecordView> records(TenantId tenantId, UUID incidentId) {
    return jdbc.sql(
            "SELECT "
                + RECORD_COLUMNS
                + " FROM ticketing.ticket_sync_record WHERE tenant_id = :t AND incident_id = :i"
                + " ORDER BY at, id")
        .param("t", tenantId.value())
        .param("i", incidentId)
        .query(TicketingRecords::record)
        .list();
  }

  Optional<SyncRecordView> recordOfStep(TenantId tenantId, UUID stepExecutionId) {
    return jdbc.sql(
            "SELECT "
                + RECORD_COLUMNS
                + " FROM ticketing.ticket_sync_record WHERE tenant_id = :t"
                + " AND step_execution_id = :s")
        .param("t", tenantId.value())
        .param("s", stepExecutionId)
        .query(TicketingRecords::record)
        .optional();
  }

  Optional<SyncRecordView> recordOfEscalation(TenantId tenantId, UUID escalationId) {
    return jdbc.sql(
            "SELECT "
                + RECORD_COLUMNS
                + " FROM ticketing.ticket_sync_record WHERE tenant_id = :t"
                + " AND escalation_id = :e")
        .param("t", tenantId.value())
        .param("e", escalationId)
        .query(TicketingRecords::record)
        .optional();
  }

  void insertRecord(TenantId tenantId, SyncRecordView record) {
    jdbc.sql(
            "INSERT INTO ticketing.ticket_sync_record (id, tenant_id, created_at, updated_at,"
                + " incident_id, ticket_link_id, operation, requested_by, step_execution_id,"
                + " escalation_id, content_sent, outcome, error, at, finished_at)"
                + " VALUES (:id, :t, :at, :updated, :incident, :link, :operation, :requester,"
                + " :step, :escalation, CAST(:content AS jsonb), :outcome, :error, :at,"
                + " :finished)")
        .param("id", record.id())
        .param("t", tenantId.value())
        .param("at", Timestamp.from(record.at()))
        .param(
            "updated",
            Timestamp.from(record.finishedAt() == null ? record.at() : record.finishedAt()))
        .param("incident", record.incidentId())
        .param("link", record.ticketLinkId())
        .param("operation", record.operation().key())
        .param("requester", record.requestedBy().key())
        .param("step", record.stepExecutionId())
        .param("escalation", record.escalationId())
        .param("content", record.contentSentJson())
        .param("outcome", record.outcome().key())
        .param("error", record.error())
        .param("finished", record.finishedAt() == null ? null : Timestamp.from(record.finishedAt()))
        .update();
  }

  /**
   * Finishes a {@code pending} record: outcome, error, link and finish time, and the content sent
   * when {@code contentSentJson} is not {@code null}; returns the number of records changed.
   */
  int finish(
      TenantId tenantId,
      UUID recordId,
      SyncOutcome outcome,
      String error,
      UUID ticketLinkId,
      String contentSentJson,
      Instant now) {
    return jdbc.sql(
            "UPDATE ticketing.ticket_sync_record SET outcome = :outcome, error = :error,"
                + " ticket_link_id = :link, content_sent = COALESCE(CAST(:content AS jsonb),"
                + " content_sent), finished_at = :now, updated_at = :now"
                + " WHERE tenant_id = :t AND id = :id AND outcome = 'pending'")
        .param("outcome", outcome.key())
        .param("error", error)
        .param("link", ticketLinkId)
        .param("content", contentSentJson)
        .param("now", Timestamp.from(now))
        .param("t", tenantId.value())
        .param("id", recordId)
        .update();
  }

  private static TicketLinkView link(ResultSet rs, int row) throws SQLException {
    String state = rs.getString("state");
    return new TicketLinkView(
        rs.getObject("id", UUID.class),
        rs.getObject("incident_id", UUID.class),
        rs.getObject("connector_instance_id", UUID.class),
        rs.getString("external_key"),
        rs.getString("url"),
        TicketLinkState.fromKey(state)
            .orElseThrow(() -> new IllegalStateException("unknown stored state " + state)),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }

  private static SyncRecordView record(ResultSet rs, int row) throws SQLException {
    Timestamp finished = rs.getTimestamp("finished_at");
    return new SyncRecordView(
        rs.getObject("id", UUID.class),
        rs.getObject("incident_id", UUID.class),
        rs.getObject("ticket_link_id", UUID.class),
        known(SyncOperation.fromKey(rs.getString("operation"))),
        known(SyncRequester.fromKey(rs.getString("requested_by"))),
        rs.getObject("step_execution_id", UUID.class),
        rs.getObject("escalation_id", UUID.class),
        rs.getString("content_sent"),
        known(SyncOutcome.fromKey(rs.getString("outcome"))),
        rs.getString("error"),
        rs.getTimestamp("at").toInstant(),
        finished == null ? null : finished.toInstant());
  }

  private static <T> T known(Optional<T> value) {
    return value.orElseThrow(() -> new IllegalStateException("unknown stored value"));
  }
}
