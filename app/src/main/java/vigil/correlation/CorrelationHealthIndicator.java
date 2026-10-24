package vigil.correlation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The health indicator {@code correlation} (AR-H-12, AR-R-17): {@code DOWN} with the tenant, alert
 * and attempt number of each pending retry of a failed decision; {@code UP} otherwise.
 */
@Component("correlationHealthIndicator")
@Profile("!migrate")
class CorrelationHealthIndicator implements HealthIndicator {

  private final CorrelationWork work;

  CorrelationHealthIndicator(CorrelationWork work) {
    this.work = work;
  }

  @Override
  public Health health() {
    List<CorrelationWork.PendingRetry> pending = work.pendingRetries();
    if (pending.isEmpty()) {
      return Health.up().build();
    }
    List<Map<String, Object>> retries =
        pending.stream()
            .map(
                retry -> {
                  Map<String, Object> entry = new LinkedHashMap<>();
                  entry.put("tenant_id", retry.tenantId().value().toString());
                  entry.put("alert_id", retry.alertId().toString());
                  entry.put("attempt", retry.attempt());
                  return entry;
                })
            .toList();
    return Health.down().withDetail("retries", retries).build();
  }
}
