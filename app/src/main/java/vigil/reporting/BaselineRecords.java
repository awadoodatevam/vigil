package vigil.reporting;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import vigil.reporting.api.MetricKey;
import vigil.shared.Ids;
import vigil.shared.TenantId;

/** The table {@code reporting.baseline_value}; every statement filters by {@code tenant_id}. */
@Component
class BaselineRecords {

  /** A stored baseline: its value as stored ({@code numeric(12,1)}) and its label. */
  record Baseline(BigDecimal value, String label) {}

  private final JdbcClient jdbc;

  BaselineRecords(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  boolean hasBaselines(TenantId tenantId) {
    return jdbc.sql("SELECT EXISTS (SELECT 1 FROM reporting.baseline_value WHERE tenant_id = :t)")
        .param("t", tenantId.value())
        .query(Boolean.class)
        .single();
  }

  void insert(TenantId tenantId, MetricKey key, BigDecimal value, String label, Instant now) {
    jdbc.sql(
            "INSERT INTO reporting.baseline_value (id, tenant_id, created_at, updated_at,"
                + " metric_key, value, unit, label) VALUES (:id, :t, :now, :now, :key, :value,"
                + " :unit, :label)")
        .param("id", Ids.newId())
        .param("t", tenantId.value())
        .param("now", Timestamp.from(now))
        .param("key", key.key())
        .param("value", value)
        .param("unit", key.unit().key())
        .param("label", label)
        .update();
  }

  /** The tenant's baselines by metric. */
  Map<MetricKey, Baseline> baselines(TenantId tenantId) {
    Map<MetricKey, Baseline> baselines = new EnumMap<>(MetricKey.class);
    jdbc.sql("SELECT metric_key, value, label FROM reporting.baseline_value WHERE tenant_id = :t")
        .param("t", tenantId.value())
        .query(
            (rs, row) -> {
              baselines.put(
                  MetricKey.fromKey(rs.getString("metric_key")).orElseThrow(),
                  new Baseline(rs.getBigDecimal("value"), rs.getString("label")));
              return null;
            })
        .list();
    return baselines;
  }
}
