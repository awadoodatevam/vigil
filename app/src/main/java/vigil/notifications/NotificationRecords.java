package vigil.notifications;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import vigil.incidents.api.NotifiedPurpose;
import vigil.notifications.api.Channel;
import vigil.notifications.api.DeliveryAddressView;
import vigil.notifications.api.DeliveryStatus;
import vigil.notifications.api.NotificationRecordView;
import vigil.notifications.api.RecipientKind;
import vigil.shared.TenantId;

/** The tables of vigil-notifications: delivery addresses (DM-E-77) and records (DM-E-78). */
@Component
class NotificationRecords {

  private static final String ADDRESS_COLUMNS =
      "id, subject_kind, subject_key, channel, address, credential_ref_id, active";
  private static final String RECORD_COLUMNS =
      "id, incident_id, purpose, origin_module, origin_entity, origin_id, recipient_kind,"
          + " recipient_key, via_team_key, channel, address, content, link, status, error, at";

  private final JdbcClient jdbc;

  NotificationRecords(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Serialises the imports of one tenant's delivery addresses, in the caller's transaction. */
  void lock(TenantId tenantId) {
    jdbc.sql(
            "SELECT pg_advisory_xact_lock(hashtext('notifications.delivery_address'),"
                + " hashtext(:tenant))::text")
        .param("tenant", tenantId.value().toString())
        .query(String.class)
        .single();
  }

  boolean anyAddress(TenantId tenantId) {
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM notifications.delivery_address WHERE tenant_id = :t)")
        .param("t", tenantId.value())
        .query(Boolean.class)
        .single();
  }

  /** Every address, by subject kind, subject key, channel, then id. */
  List<DeliveryAddressView> addresses(TenantId tenantId) {
    return jdbc.sql(
            "SELECT "
                + ADDRESS_COLUMNS
                + " FROM notifications.delivery_address WHERE tenant_id = :t"
                + " ORDER BY subject_kind COLLATE \"C\", subject_key COLLATE \"C\","
                + " channel COLLATE \"C\", id")
        .param("t", tenantId.value())
        .query(NotificationRecords::address)
        .list();
  }

  /** The active addresses of one subject, {@code teams} before {@code email}. */
  List<DeliveryAddressView> activeAddresses(
      TenantId tenantId, RecipientKind kind, String subjectKey) {
    return jdbc.sql(
            "SELECT "
                + ADDRESS_COLUMNS
                + " FROM notifications.delivery_address WHERE tenant_id = :t"
                + " AND subject_kind = :kind AND subject_key = :key AND active"
                + " ORDER BY CASE channel WHEN 'teams' THEN 0 ELSE 1 END, id")
        .param("t", tenantId.value())
        .param("kind", kind.key())
        .param("key", subjectKey)
        .query(NotificationRecords::address)
        .list();
  }

  void insertAddress(TenantId tenantId, DeliveryAddressView address, Instant now) {
    jdbc.sql(
            "INSERT INTO notifications.delivery_address (id, tenant_id, created_at, updated_at,"
                + " subject_kind, subject_key, channel, address, credential_ref_id, active)"
                + " VALUES (:id, :t, :now, :now, :kind, :key, :channel, :address, :credential,"
                + " :active)")
        .param("id", address.id())
        .param("t", tenantId.value())
        .param("now", Timestamp.from(now))
        .param("kind", address.subjectKind().key())
        .param("key", address.subjectKey())
        .param("channel", address.channel().key())
        .param("address", address.address())
        .param("credential", address.credentialRefId())
        .param("active", address.active())
        .update();
  }

  /** The incident's records, by {@code at}, then id. */
  List<NotificationRecordView> records(TenantId tenantId, UUID incidentId) {
    return jdbc.sql(
            "SELECT "
                + RECORD_COLUMNS
                + " FROM notifications.notification_record WHERE tenant_id = :t"
                + " AND incident_id = :i ORDER BY at, id")
        .param("t", tenantId.value())
        .param("i", incidentId)
        .query(NotificationRecords::record)
        .list();
  }

  /** The records of one origin, by {@code at}, then id. */
  List<NotificationRecordView> recordsOfOrigin(TenantId tenantId, UUID originId) {
    return jdbc.sql(
            "SELECT "
                + RECORD_COLUMNS
                + " FROM notifications.notification_record WHERE tenant_id = :t"
                + " AND origin_id = :o ORDER BY at, id")
        .param("t", tenantId.value())
        .param("o", originId)
        .query(NotificationRecords::record)
        .list();
  }

  /** DM-R-65: whether a {@code sent} record of the origin, recipient and channel exists. */
  boolean sent(
      TenantId tenantId, UUID originId, RecipientKind kind, String recipientKey, Channel channel) {
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM notifications.notification_record WHERE tenant_id = :t"
                + " AND origin_id = :o AND recipient_kind = :kind AND recipient_key = :key"
                + " AND channel = :channel AND status = 'sent')")
        .param("t", tenantId.value())
        .param("o", originId)
        .param("kind", kind.key())
        .param("key", recipientKey)
        .param("channel", channel.key())
        .query(Boolean.class)
        .single();
  }

  void insertRecord(TenantId tenantId, NotificationRecordView record) {
    jdbc.sql(
            "INSERT INTO notifications.notification_record (id, tenant_id, created_at,"
                + " incident_id, purpose, origin_module, origin_entity, origin_id, recipient_kind,"
                + " recipient_key, via_team_key, channel, address, content, link, status, error,"
                + " at) VALUES (:id, :t, :at, :incident, :purpose, :module, :entity, :origin,"
                + " :kind, :key, :via, :channel, :address, :content, :link, :status, :error,"
                + " :at)")
        .param("id", record.id())
        .param("t", tenantId.value())
        .param("at", Timestamp.from(record.at()))
        .param("incident", record.incidentId())
        .param("purpose", record.purpose().key())
        .param("module", record.originModule())
        .param("entity", record.originEntity())
        .param("origin", record.originId())
        .param("kind", record.recipientKind().key())
        .param("key", record.recipientKey())
        .param("via", record.viaTeamKey())
        .param("channel", record.channel().key())
        .param("address", record.address())
        .param("content", record.content())
        .param("link", record.link())
        .param("status", record.status().key())
        .param("error", record.error())
        .update();
  }

  private static DeliveryAddressView address(ResultSet rs, int row) throws SQLException {
    return new DeliveryAddressView(
        rs.getObject("id", UUID.class),
        RecipientKind.fromKey(rs.getString("subject_kind")).orElseThrow(),
        rs.getString("subject_key"),
        Channel.fromKey(rs.getString("channel")).orElseThrow(),
        rs.getString("address"),
        rs.getObject("credential_ref_id", UUID.class),
        rs.getBoolean("active"));
  }

  private static NotificationRecordView record(ResultSet rs, int row) throws SQLException {
    return new NotificationRecordView(
        rs.getObject("id", UUID.class),
        rs.getObject("incident_id", UUID.class),
        NotifiedPurpose.fromKey(rs.getString("purpose")).orElseThrow(),
        rs.getString("origin_module"),
        rs.getString("origin_entity"),
        rs.getObject("origin_id", UUID.class),
        RecipientKind.fromKey(rs.getString("recipient_kind")).orElseThrow(),
        rs.getString("recipient_key"),
        rs.getString("via_team_key"),
        Channel.fromKey(rs.getString("channel")).orElseThrow(),
        rs.getString("address"),
        rs.getString("content"),
        rs.getString("link"),
        DeliveryStatus.fromKey(rs.getString("status")).orElseThrow(),
        rs.getString("error"),
        rs.getTimestamp("at").toInstant());
  }
}
