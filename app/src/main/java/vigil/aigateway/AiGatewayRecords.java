package vigil.aigateway;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vigil.aigateway.api.AiTask;
import vigil.aigateway.api.ProviderLocation;
import vigil.aigateway.api.UsageOutcome;
import vigil.shared.Actor;
import vigil.shared.TenantId;

/** The tables of {@code ai_gateway}; every statement filters by {@code tenant_id}. */
@Component
class AiGatewayRecords {

  static final JsonMapper JSON = JsonMapper.builder().build();

  /** A provider configuration (DM-E-70). */
  record ProviderConfiguration(
      String providerKey,
      String providerKind,
      UUID connectorInstanceId,
      String modelName,
      ProviderLocation location,
      BigDecimal priceInputPerMillion,
      BigDecimal priceOutputPerMillion,
      boolean active) {}

  /** An AI usage record as inserted (DM-E-74). */
  record Usage(
      AiTask task,
      String providerKey,
      ProviderLocation providerLocation,
      String modelName,
      UUID requestGroupId,
      UUID incidentId,
      int fallbackPosition,
      List<String> dataCategories,
      UsageOutcome outcome,
      long inputUnits,
      long outputUnits,
      BigDecimal cost,
      Instant startedAt,
      Instant finishedAt) {}

  private final JdbcClient jdbc;

  AiGatewayRecords(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  boolean hasRecords(TenantId tenantId) {
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM ai_gateway.ai_provider_configuration WHERE tenant_id = :t)"
                + " OR EXISTS (SELECT 1 FROM ai_gateway.allowed_provider WHERE tenant_id = :t)"
                + " OR EXISTS (SELECT 1 FROM ai_gateway.task_assignment WHERE tenant_id = :t)"
                + " OR EXISTS (SELECT 1 FROM ai_gateway.data_routing_rule WHERE tenant_id = :t)")
        .param("t", tenantId.value())
        .query(Boolean.class)
        .single();
  }

  void insertConfiguration(
      TenantId tenantId, UUID id, ProviderConfiguration configuration, Instant now) {
    jdbc.sql(
            "INSERT INTO ai_gateway.ai_provider_configuration (id, tenant_id, created_at,"
                + " updated_at, provider_key, provider_kind, connector_instance_id, model_name,"
                + " location, price_input_per_million, price_output_per_million, active)"
                + " VALUES (:id, :t, :now, :now, :key, :kind, :instance, :model, :location,"
                + " :input, :output, :active)")
        .param("id", id)
        .param("t", tenantId.value())
        .param("now", Timestamp.from(now))
        .param("key", configuration.providerKey())
        .param("kind", configuration.providerKind())
        .param("instance", configuration.connectorInstanceId())
        .param("model", configuration.modelName())
        .param("location", configuration.location().key())
        .param("input", configuration.priceInputPerMillion())
        .param("output", configuration.priceOutputPerMillion())
        .param("active", configuration.active())
        .update();
  }

  void insertAllowedProvider(
      TenantId tenantId, UUID id, AiTask task, String providerKind, Instant now) {
    jdbc.sql(
            "INSERT INTO ai_gateway.allowed_provider (id, tenant_id, created_at, updated_at, task,"
                + " provider_kind) VALUES (:id, :t, :now, :now, :task, :kind)")
        .param("id", id)
        .param("t", tenantId.value())
        .param("now", Timestamp.from(now))
        .param("task", task.key())
        .param("kind", providerKind)
        .update();
  }

  void insertTaskAssignment(
      TenantId tenantId,
      UUID id,
      AiTask task,
      List<String> providerKeys,
      Actor updatedBy,
      Instant now) {
    jdbc.sql(
            "INSERT INTO ai_gateway.task_assignment (id, tenant_id, created_at, updated_at, task,"
                + " provider_keys, updated_by_kind, updated_by_user_id, updated_by_module)"
                + " VALUES (:id, :t, :now, :now, :task, CAST(:keys AS jsonb), :kind, :user,"
                + " :module)")
        .param("id", id)
        .param("t", tenantId.value())
        .param("now", Timestamp.from(now))
        .param("task", task.key())
        .param("keys", JSON.writeValueAsString(providerKeys))
        .param("kind", actorKind(updatedBy))
        .param("user", updatedBy.userId())
        .param("module", updatedBy.module())
        .update();
  }

  void insertDataRoutingRule(
      TenantId tenantId,
      UUID id,
      String dataCategory,
      List<String> allowedProviderKeys,
      Actor updatedBy,
      Instant now) {
    jdbc.sql(
            "INSERT INTO ai_gateway.data_routing_rule (id, tenant_id, created_at, updated_at,"
                + " data_category, allowed_provider_keys, updated_by_kind, updated_by_user_id,"
                + " updated_by_module) VALUES (:id, :t, :now, :now, :category,"
                + " CAST(:keys AS jsonb), :kind, :user, :module)")
        .param("id", id)
        .param("t", tenantId.value())
        .param("now", Timestamp.from(now))
        .param("category", dataCategory)
        .param("keys", JSON.writeValueAsString(allowedProviderKeys))
        .param("kind", actorKind(updatedBy))
        .param("user", updatedBy.userId())
        .param("module", updatedBy.module())
        .update();
  }

  /** The tenant's provider configurations by key. */
  Map<String, ProviderConfiguration> configurations(TenantId tenantId) {
    Map<String, ProviderConfiguration> byKey = new LinkedHashMap<>();
    jdbc.sql(
            "SELECT provider_key, provider_kind, connector_instance_id, model_name, location,"
                + " price_input_per_million, price_output_per_million, active"
                + " FROM ai_gateway.ai_provider_configuration WHERE tenant_id = :t"
                + " ORDER BY provider_key COLLATE \"C\"")
        .param("t", tenantId.value())
        .query(
            (rs, row) ->
                new ProviderConfiguration(
                    rs.getString("provider_key"),
                    rs.getString("provider_kind"),
                    rs.getObject("connector_instance_id", UUID.class),
                    rs.getString("model_name"),
                    "in_house".equals(rs.getString("location"))
                        ? ProviderLocation.IN_HOUSE
                        : ProviderLocation.EXTERNAL,
                    rs.getBigDecimal("price_input_per_million"),
                    rs.getBigDecimal("price_output_per_million"),
                    rs.getBoolean("active")))
        .list()
        .forEach(configuration -> byKey.put(configuration.providerKey(), configuration));
    return byKey;
  }

  /** The provider kinds allowed for the task (DM-E-71). */
  Set<String> allowedKinds(TenantId tenantId, AiTask task) {
    return new LinkedHashSet<>(
        jdbc.sql(
                "SELECT provider_kind FROM ai_gateway.allowed_provider"
                    + " WHERE tenant_id = :t AND task = :task ORDER BY provider_kind COLLATE \"C\"")
            .param("t", tenantId.value())
            .param("task", task.key())
            .query(String.class)
            .list());
  }

  /** The provider keys of the task's assignment in order, or empty when it has none. */
  Optional<List<String>> taskAssignment(TenantId tenantId, AiTask task) {
    return jdbc.sql(
            "SELECT provider_keys::text FROM ai_gateway.task_assignment"
                + " WHERE tenant_id = :t AND task = :task")
        .param("t", tenantId.value())
        .param("task", task.key())
        .query(String.class)
        .optional()
        .map(AiGatewayRecords::keys);
  }

  /** The allowed provider keys of each data category that has a rule (DM-E-73). */
  Map<String, Set<String>> dataRoutingRules(TenantId tenantId) {
    Map<String, Set<String>> rules = new LinkedHashMap<>();
    jdbc.sql(
            "SELECT data_category, allowed_provider_keys::text AS keys"
                + " FROM ai_gateway.data_routing_rule WHERE tenant_id = :t")
        .param("t", tenantId.value())
        .query(
            (rs, row) ->
                Map.entry(rs.getString("data_category"), Set.copyOf(keys(rs.getString("keys")))))
        .list()
        .forEach(entry -> rules.put(entry.getKey(), entry.getValue()));
    return rules;
  }

  void insertUsage(TenantId tenantId, UUID id, Usage usage, Instant now) {
    jdbc.sql(
            "INSERT INTO ai_gateway.ai_usage_record (id, tenant_id, created_at, task,"
                + " provider_key, provider_location, model_name, request_group_id, incident_id,"
                + " fallback_position, data_categories, outcome, input_units, output_units, cost,"
                + " started_at, finished_at) VALUES (:id, :t, :now, :task, :key, :location,"
                + " :model, :group, :incident, :position, CAST(:categories AS jsonb), :outcome,"
                + " :input, :output, :cost, :started, :finished)")
        .param("id", id)
        .param("t", tenantId.value())
        .param("now", Timestamp.from(now))
        .param("task", usage.task().key())
        .param("key", usage.providerKey())
        .param("location", usage.providerLocation().key())
        .param("model", usage.modelName())
        .param("group", usage.requestGroupId())
        .param("incident", usage.incidentId())
        .param("position", (long) usage.fallbackPosition())
        .param("categories", JSON.writeValueAsString(usage.dataCategories()))
        .param("outcome", usage.outcome().key())
        .param("input", usage.inputUnits())
        .param("output", usage.outputUnits())
        .param("cost", usage.cost())
        .param("started", Timestamp.from(usage.startedAt()))
        .param("finished", Timestamp.from(usage.finishedAt()))
        .update();
  }

  private static List<String> keys(String json) {
    List<String> keys = new ArrayList<>();
    for (JsonNode key : JSON.readTree(json)) {
      keys.add(key.stringValue());
    }
    return keys;
  }

  private static String actorKind(Actor actor) {
    return actor.kind() == Actor.Kind.USER ? "user" : "module";
  }
}
