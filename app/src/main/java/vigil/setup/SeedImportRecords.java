package vigil.setup;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import vigil.setup.api.PartOutcome;
import vigil.setup.api.PartView;
import vigil.setup.api.SeedImportState;
import vigil.setup.api.SeedImportView;
import vigil.shared.Actor;
import vigil.shared.TenantId;

/**
 * The seed import records in {@code setup.seed_import} (DM-E-82). Every statement filters by
 * tenant, except the two installation-level reads {@link #find} and {@link #running}. Callers hold
 * the transaction.
 */
@Component
class SeedImportRecords {

  static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String COLUMNS =
      "id, tenant_id, bundle_name, bundle_version, state, created_at, finished_at, parts::text"
          + " AS parts";

  private final JdbcClient jdbc;

  SeedImportRecords(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  void insert(
      UUID id,
      TenantId tenantId,
      String bundleName,
      String bundleVersion,
      Actor startedBy,
      Instant now,
      List<PartView> parts) {
    jdbc.sql(
            "INSERT INTO setup.seed_import (id, tenant_id, created_at, updated_at, bundle_name,"
                + " bundle_version, started_by_kind, started_by_user_id, started_by_module, state,"
                + " parts, finished_at) VALUES (:id, :tenantId, :now, :now, :bundleName,"
                + " :bundleVersion, :kind, :userId, :module, 'running', CAST(:parts AS jsonb),"
                + " NULL)")
        .param("id", id)
        .param("tenantId", tenantId.value())
        .param("now", Timestamp.from(now))
        .param("bundleName", bundleName)
        .param("bundleVersion", bundleVersion)
        .param("kind", startedBy.kind().name().toLowerCase(Locale.ROOT))
        .param("userId", startedBy.userId())
        .param("module", startedBy.module())
        .param("parts", partsJson(parts))
        .update();
  }

  boolean exists(TenantId tenantId) {
    return jdbc.sql("SELECT EXISTS (SELECT 1 FROM setup.seed_import WHERE tenant_id = :tenantId)")
        .param("tenantId", tenantId.value())
        .query(Boolean.class)
        .single();
  }

  /** The state, parts and finish time of a record; {@code finishedAt} may be {@code null}. */
  void update(
      UUID id,
      TenantId tenantId,
      SeedImportState state,
      List<PartView> parts,
      Instant now,
      Instant finishedAt) {
    jdbc.sql(
            "UPDATE setup.seed_import SET state = :state, parts = CAST(:parts AS jsonb),"
                + " updated_at = :now, finished_at = :finishedAt"
                + " WHERE tenant_id = :tenantId AND id = :id")
        .param("state", state.key())
        .param("parts", partsJson(parts))
        .param("now", Timestamp.from(now))
        .param("finishedAt", finishedAt == null ? null : Timestamp.from(finishedAt))
        .param("tenantId", tenantId.value())
        .param("id", id)
        .update();
  }

  /** The record of {@code id}, of any tenant (installation-level read, AR-I-97). */
  Optional<SeedImportView> find(UUID id) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM setup.seed_import WHERE id = :id")
        .param("id", id)
        .query(SeedImportRecords::view)
        .optional();
  }

  /** Every record still {@code running}, of every tenant (installation-level read, AR-R-24). */
  List<SeedImportView> running() {
    return jdbc.sql(
            "SELECT " + COLUMNS + " FROM setup.seed_import WHERE state = 'running' ORDER BY id")
        .query(SeedImportRecords::view)
        .list();
  }

  /** The stored form of the parts (DM-E-82): module, outcome, counts and error of each part. */
  static String partsJson(List<PartView> parts) {
    ArrayNode array = JSON.createArrayNode();
    for (PartView part : parts) {
      ObjectNode entry = array.addObject();
      entry.put("module", part.module());
      entry.put("outcome", part.outcome().key());
      ObjectNode counts = entry.putObject("counts");
      part.counts().forEach(counts::put);
      entry.put("error", part.error());
    }
    return JSON.writeValueAsString(array);
  }

  private static SeedImportView view(ResultSet rs, int row) throws SQLException {
    Timestamp finishedAt = rs.getTimestamp("finished_at");
    List<PartView> parts = new ArrayList<>();
    for (JsonNode entry : JSON.readTree(rs.getString("parts"))) {
      Map<String, Integer> counts = new LinkedHashMap<>();
      entry.get("counts").properties().forEach(c -> counts.put(c.getKey(), c.getValue().asInt()));
      JsonNode error = entry.get("error");
      parts.add(
          new PartView(
              entry.get("module").stringValue(),
              PartOutcome.valueOf(entry.get("outcome").stringValue().toUpperCase(Locale.ROOT)),
              counts,
              error == null || error.isNull() ? null : error.stringValue()));
    }
    return new SeedImportView(
        rs.getObject("id", UUID.class),
        new TenantId(rs.getObject("tenant_id", UUID.class)),
        rs.getString("bundle_name"),
        rs.getString("bundle_version"),
        SeedImportState.valueOf(rs.getString("state").toUpperCase(Locale.ROOT)),
        rs.getTimestamp("created_at").toInstant(),
        finishedAt == null ? null : finishedAt.toInstant(),
        List.copyOf(parts));
  }
}
