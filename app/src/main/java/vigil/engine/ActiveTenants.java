package vigil.engine;

import java.util.Set;
import org.springframework.stereotype.Component;
import vigil.shared.TenantId;

/**
 * The engine's set of active tenants (AR-R-20 case (a), AR-R-19), written by {@code EngineWork} and
 * read by the run executors without a lock.
 */
@Component
class ActiveTenants {

  private volatile Set<TenantId> tenants = Set.of();

  boolean contains(TenantId tenantId) {
    return tenants.contains(tenantId);
  }

  Set<TenantId> all() {
    return tenants;
  }

  void set(Set<TenantId> active) {
    tenants = Set.copyOf(active);
  }
}
