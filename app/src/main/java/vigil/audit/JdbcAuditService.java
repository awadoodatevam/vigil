package vigil.audit;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vigil.audit.api.AuditActorFilter;
import vigil.audit.api.AuditEntry;
import vigil.audit.api.AuditEventType;
import vigil.audit.api.AuditEventView;
import vigil.audit.api.AuditPage;
import vigil.audit.api.AuditPosition;
import vigil.audit.api.AuditQuery;
import vigil.audit.api.AuditRecorded;
import vigil.audit.api.AuditService;
import vigil.audit.api.ConfigurationArea;
import vigil.shared.Actor;
import vigil.shared.Ids;
import vigil.shared.TenantId;
import vigil.shared.Timestamps;

/** The audit log in {@code audit.audit_event} (PM-M03, DM-E-08). */
@Service
class JdbcAuditService implements AuditService {

  private static final int SUBJECT_TYPE_MAX = 40;
  private static final int SUMMARY_MAX = 300;
  private static final int LIMIT_MAX = 50;
  private static final Set<String> AREA_KEYS =
      Arrays.stream(ConfigurationArea.values())
          .map(ConfigurationArea::key)
          .collect(Collectors.toUnmodifiableSet());
  private static final Set<String> CONFIGURATION_DETAILS = Set.of("area", "before", "after");
  private static final String COLUMNS =
      "id, sequence, occurred_at, actor_kind, actor_user_id, actor_module, event_type,"
          + " subject_type, subject_id, incident_id, summary, details::text AS details";

  private final JdbcClient jdbc;
  private final Clock clock;
  private final JsonMapper json;

  JdbcAuditService(JdbcClient jdbc, Clock clock, JsonMapper json) {
    this.jdbc = jdbc;
    this.clock = clock;
    this.json = json;
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED)
  public AuditRecorded append(AuditEntry entry) {
    check(entry);
    UUID id = Ids.newId();
    long sequence =
        jdbc.sql(
                "INSERT INTO audit.audit_event (id, tenant_id, created_at, occurred_at, actor_kind,"
                    + " actor_user_id, actor_module, event_type, subject_type, subject_id,"
                    + " incident_id, summary, details) VALUES (:id, :tenantId, :createdAt,"
                    + " :occurredAt, :actorKind, :actorUserId, :actorModule, :eventType,"
                    + " :subjectType, :subjectId, :incidentId, :summary, CAST(:details AS jsonb))"
                    + " RETURNING sequence")
            .param("id", id)
            .param("tenantId", entry.tenantId().value())
            .param("createdAt", Timestamp.from(Timestamps.now(clock)))
            .param("occurredAt", Timestamp.from(entry.occurredAt().truncatedTo(ChronoUnit.MILLIS)))
            .param("actorKind", entry.actor().kind().name().toLowerCase(Locale.ROOT))
            .param("actorUserId", entry.actor().userId())
            .param("actorModule", entry.actor().module())
            .param("eventType", entry.eventType().key())
            .param("subjectType", entry.subjectType())
            .param("subjectId", entry.subjectId())
            .param("incidentId", entry.incidentId())
            .param("summary", entry.summary())
            .param("details", entry.detailsJson())
            .query(Long.class)
            .single();
    return new AuditRecorded(id, sequence);
  }

  @Override
  @Transactional(readOnly = true)
  public AuditPage query(TenantId tenantId, AuditQuery query) {
    if (tenantId == null) {
      throw new IllegalArgumentException("tenantId: required");
    }
    if (query == null) {
      throw new IllegalArgumentException("query: required");
    }
    if (query.limit() < 1 || query.limit() > LIMIT_MAX) {
      throw new IllegalArgumentException("limit: 1 to 50");
    }
    if ((query.subjectType() == null) != (query.subjectId() == null)) {
      throw new IllegalArgumentException("subjectType and subjectId: both set or both null");
    }
    List<String> conditions = new ArrayList<>(List.of("tenant_id = :tenantId"));
    Map<String, Object> params = new HashMap<>();
    params.put("tenantId", tenantId.value());
    if (query.from() != null) {
      conditions.add("occurred_at >= :from");
      params.put("from", Timestamp.from(query.from()));
    }
    if (query.to() != null) {
      conditions.add("occurred_at <= :to");
      params.put("to", Timestamp.from(query.to()));
    }
    AuditActorFilter actor = query.actor();
    if (actor != null && actor.kind() == AuditActorFilter.Kind.USER) {
      conditions.add("actor_kind = 'user' AND actor_user_id = :actorUserId");
      params.put("actorUserId", actor.userId());
    } else if (actor != null) {
      conditions.add("actor_kind = 'module'");
    }
    if (query.eventTypes() != null && !query.eventTypes().isEmpty()) {
      conditions.add("event_type IN (:eventTypes)");
      params.put("eventTypes", query.eventTypes().stream().map(AuditEventType::key).toList());
    }
    if (query.incidentIds() != null && !query.incidentIds().isEmpty()) {
      conditions.add("incident_id IN (:incidentIds)");
      params.put("incidentIds", List.copyOf(query.incidentIds()));
    }
    if (query.subjectType() != null) {
      conditions.add("subject_type = :subjectType AND subject_id = :subjectId");
      params.put("subjectType", query.subjectType());
      params.put("subjectId", query.subjectId());
    }
    String where = String.join(" AND ", conditions);
    long total =
        jdbc.sql("SELECT count(*) FROM audit.audit_event WHERE " + where)
            .params(params)
            .query(Long.class)
            .single();
    String page = where;
    if (query.after() != null) {
      page += " AND (occurred_at, sequence) < (:afterOccurredAt, :afterSequence)";
      params.put("afterOccurredAt", Timestamp.from(query.after().occurredAt()));
      params.put("afterSequence", query.after().sequence());
    }
    params.put("fetch", query.limit() + 1);
    List<AuditEventView> rows =
        jdbc.sql(
                "SELECT "
                    + COLUMNS
                    + " FROM audit.audit_event WHERE "
                    + page
                    + " ORDER BY occurred_at DESC, sequence DESC LIMIT :fetch")
            .params(params)
            .query(JdbcAuditService::view)
            .list();
    AuditPosition next = null;
    if (rows.size() > query.limit()) {
      rows = rows.subList(0, query.limit());
      AuditEventView last = rows.getLast();
      next = new AuditPosition(last.occurredAt(), last.sequence());
    }
    return new AuditPage(List.copyOf(rows), total, next);
  }

  private void check(AuditEntry entry) {
    if (entry == null) {
      throw new IllegalArgumentException("entry: required");
    }
    required(entry.tenantId(), "tenantId");
    required(entry.occurredAt(), "occurredAt");
    required(entry.actor(), "actor");
    required(entry.eventType(), "eventType");
    required(entry.subjectId(), "subjectId");
    length(entry.subjectType(), SUBJECT_TYPE_MAX, "subjectType");
    length(entry.summary(), SUMMARY_MAX, "summary");
    JsonNode details = null;
    if (entry.detailsJson() != null) {
      try {
        details = json.readTree(entry.detailsJson());
      } catch (JacksonException e) {
        throw new IllegalArgumentException("detailsJson: not valid JSON", e);
      }
      if (details == null || !details.isObject()) {
        throw new IllegalArgumentException("detailsJson: a JSON object");
      }
    }
    if (entry.eventType() == AuditEventType.CONFIGURATION_CHANGED) {
      if (details == null
          || !Set.copyOf(details.propertyNames()).equals(CONFIGURATION_DETAILS)
          || !details.get("area").isString()
          || !AREA_KEYS.contains(details.get("area").asString())) {
        throw new IllegalArgumentException(
            "detailsJson: configuration_changed needs exactly area, before and after");
      }
    }
  }

  private static void required(Object value, String field) {
    if (value == null) {
      throw new IllegalArgumentException(field + ": required");
    }
  }

  private static void length(String value, int max, String field) {
    if (value == null || value.isEmpty() || value.codePointCount(0, value.length()) > max) {
      throw new IllegalArgumentException(field + ": 1 to " + max + " characters");
    }
  }

  private static AuditEventView view(ResultSet rs, int row) throws SQLException {
    Actor actor =
        "user".equals(rs.getString("actor_kind"))
            ? Actor.user(rs.getObject("actor_user_id", UUID.class))
            : Actor.module(rs.getString("actor_module"));
    return new AuditEventView(
        rs.getObject("id", UUID.class),
        rs.getLong("sequence"),
        rs.getTimestamp("occurred_at").toInstant(),
        actor,
        AuditEventType.valueOf(rs.getString("event_type").toUpperCase(Locale.ROOT)),
        rs.getString("subject_type"),
        rs.getObject("subject_id", UUID.class),
        rs.getObject("incident_id", UUID.class),
        rs.getString("summary"),
        rs.getString("details"));
  }
}
