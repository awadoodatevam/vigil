package vigil.servicemap;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import vigil.servicemap.api.ComponentKind;
import vigil.servicemap.api.ComponentView;
import vigil.servicemap.api.Counts;
import vigil.servicemap.api.Criticality;
import vigil.servicemap.api.GenerationView;
import vigil.servicemap.api.IdentityKey;
import vigil.servicemap.api.ServiceView;
import vigil.servicemap.api.SourceKind;
import vigil.shared.Actor;
import vigil.shared.Ids;
import vigil.shared.TenantId;

/** The tables of {@code servicemap}; every statement filters by {@code tenant_id}. */
@Component
class ServiceMapRecords {

  static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Map<String, String> COLUMNS =
      Map.of(
          "business_service",
          "service_key, name, criticality, owner_team_key, component_keys",
          "component",
          "component_key, name, kind, criticality, owner_team_key, runs_on_key, target_key",
          "component_identity_key",
          "component_key, source_kind, pair_name, pair_value",
          "dependency",
          "from_component_key, to_component_key");

  private final JdbcClient jdbc;

  ServiceMapRecords(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** The tenant's highest generation number, or empty. */
  Optional<Long> currentNumber(TenantId tenantId) {
    return jdbc.sql(
            "SELECT generation FROM servicemap.generation WHERE tenant_id = :tenantId"
                + " ORDER BY generation DESC LIMIT 1")
        .param("tenantId", tenantId.value())
        .query(Long.class)
        .optional();
  }

  /** The tenant's highest generation with its id, or empty. */
  Optional<Stored> current(TenantId tenantId) {
    return jdbc.sql(
            "SELECT * FROM servicemap.generation WHERE tenant_id = :tenantId"
                + " ORDER BY generation DESC LIMIT 1")
        .param("tenantId", tenantId.value())
        .query(ServiceMapRecords::stored)
        .optional();
  }

  /** A generation record and its id. */
  record Stored(UUID id, GenerationView view) {}

  /** The generation's graph, read from its rows. */
  ServiceMapGraph graph(TenantId tenantId, long generation) {
    List<ServiceView> services =
        rows("business_service", tenantId, generation)
            .query(
                (rs, row) ->
                    new ServiceView(
                        rs.getString("service_key"),
                        rs.getString("name"),
                        criticality(rs),
                        rs.getString("owner_team_key"),
                        List.of(JSON.readValue(rs.getString("component_keys"), String[].class))))
            .list();
    List<ComponentView> components =
        rows("component", tenantId, generation)
            .query(
                (rs, row) ->
                    new ComponentView(
                        rs.getString("component_key"),
                        rs.getString("name"),
                        ComponentKind.fromKey(rs.getString("kind")).orElseThrow(),
                        criticality(rs),
                        rs.getString("owner_team_key"),
                        rs.getString("runs_on_key"),
                        rs.getString("target_key")))
            .list();
    List<ServiceMapGraph.Identity> identities =
        rows("component_identity_key", tenantId, generation)
            .query(
                (rs, row) ->
                    new ServiceMapGraph.Identity(
                        rs.getString("component_key"),
                        SourceKind.fromKey(rs.getString("source_kind")).orElseThrow(),
                        new IdentityKey(rs.getString("pair_name"), rs.getString("pair_value"))))
            .list();
    List<List<String>> dependencies =
        rows("dependency", tenantId, generation)
            .query(
                (rs, row) ->
                    List.of(rs.getString("from_component_key"), rs.getString("to_component_key")))
            .list();
    return new ServiceMapGraph(generation, services, components, identities, dependencies);
  }

  private JdbcClient.StatementSpec rows(String table, TenantId tenantId, long generation) {
    return jdbc.sql(
            "SELECT * FROM servicemap."
                + table
                + " WHERE tenant_id = :tenantId AND generation = :generation")
        .param("tenantId", tenantId.value())
        .param("generation", generation);
  }

  /** Stores a generation and the rows of its file; returns the generation record's id. */
  UUID insert(
      TenantId tenantId,
      long generation,
      String fileName,
      String fileHash,
      Actor importedBy,
      Timestamp now,
      ServiceMapFile.Content file) {
    UUID id = Ids.newId();
    jdbc.sql(
            "INSERT INTO servicemap.generation (id, tenant_id, created_at, generation,"
                + " imported_by_kind, imported_by_user_id, imported_by_module, file_name,"
                + " file_hash, counts) VALUES (:id, :tenantId, :now, :generation, :kind, :userId,"
                + " :module, :fileName, :fileHash, CAST(:counts AS jsonb))")
        .param("id", id)
        .param("tenantId", tenantId.value())
        .param("now", now)
        .param("generation", generation)
        .param("kind", importedBy.kind().name().toLowerCase(Locale.ROOT))
        .param("userId", importedBy.userId())
        .param("module", importedBy.module())
        .param("fileName", fileName)
        .param("fileHash", fileHash)
        .param("counts", JSON.writeValueAsString(countsJson(file.counts())))
        .update();
    for (ServiceMapFile.Service s : file.services()) {
      insert("business_service", tenantId, generation, now)
          .param("service_key", s.serviceKey())
          .param("name", s.name())
          .param("criticality", s.criticality())
          .param("owner_team_key", s.ownerTeamKey())
          .param("component_keys", JSON.writeValueAsString(new TreeSet<>(s.componentKeys())))
          .update();
    }
    for (ServiceMapFile.Component c : file.components()) {
      insert("component", tenantId, generation, now)
          .param("component_key", c.componentKey())
          .param("name", c.name())
          .param("kind", c.kind())
          .param("criticality", c.criticality())
          .param("owner_team_key", c.ownerTeamKey())
          .param("runs_on_key", c.runsOnKey())
          .param("target_key", c.targetKey())
          .update();
    }
    for (ServiceMapFile.Identity i : file.identities()) {
      insert("component_identity_key", tenantId, generation, now)
          .param("component_key", i.componentKey())
          .param("source_kind", i.sourceKind())
          .param("pair_name", i.name())
          .param("pair_value", i.value())
          .update();
    }
    for (ServiceMapFile.Dependency d : file.dependencies()) {
      insert("dependency", tenantId, generation, now)
          .param("from_component_key", d.from())
          .param("to_component_key", d.to())
          .update();
    }
    return id;
  }

  private JdbcClient.StatementSpec insert(
      String table, TenantId tenantId, long generation, Timestamp now) {
    String columns = COLUMNS.get(table);
    String values = ":" + columns.replace(", ", ", :");
    if (table.equals("business_service")) {
      values = values.replace(":component_keys", "CAST(:component_keys AS jsonb)");
    }
    return jdbc.sql(
            "INSERT INTO servicemap."
                + table
                + " (id, tenant_id, created_at, generation, "
                + columns
                + ") VALUES (:id, :tenantId, :now, :generation, "
                + values
                + ")")
        .param("id", Ids.newId())
        .param("tenantId", tenantId.value())
        .param("now", now)
        .param("generation", generation);
  }

  /** {@code {"services", "components", "identity_keys", "dependencies"}} (DM-E-24). */
  static ObjectNode countsJson(Counts counts) {
    ObjectNode json = JSON.createObjectNode();
    json.put("services", counts.services());
    json.put("components", counts.components());
    json.put("identity_keys", counts.identityKeys());
    json.put("dependencies", counts.dependencies());
    return json;
  }

  private static Criticality criticality(ResultSet rs) throws SQLException {
    String value = rs.getString("criticality");
    return value == null ? null : Criticality.fromKey(value).orElseThrow();
  }

  private static Stored stored(ResultSet rs, int row) throws SQLException {
    Actor importedBy =
        "user".equals(rs.getString("imported_by_kind"))
            ? Actor.user(rs.getObject("imported_by_user_id", UUID.class))
            : Actor.module(rs.getString("imported_by_module"));
    JsonNode counts = JSON.readTree(rs.getString("counts"));
    return new Stored(
        rs.getObject("id", UUID.class),
        new GenerationView(
            rs.getLong("generation"),
            rs.getString("file_name"),
            rs.getString("file_hash"),
            importedBy,
            rs.getTimestamp("created_at").toInstant(),
            new Counts(
                counts.get("services").asInt(),
                counts.get("components").asInt(),
                counts.get("identity_keys").asInt(),
                counts.get("dependencies").asInt())));
  }
}
