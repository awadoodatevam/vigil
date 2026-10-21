package vigil.servicemap;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import vigil.shared.TenantId;

/**
 * The graphs of the current generations, at most 64, least recently used first out; a generation
 * never changes, so a cached graph is never stale.
 */
@Component
class ServiceMapGraphs {

  private static final int MAX = 64;

  private final ServiceMapRecords records;
  private final Map<Key, ServiceMapGraph> cache =
      new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, ServiceMapGraph> eldest) {
          return size() > MAX;
        }
      };

  private record Key(TenantId tenantId, long generation) {}

  ServiceMapGraphs(ServiceMapRecords records) {
    this.records = records;
  }

  /** The graph of the tenant's current generation, or empty when it has none. */
  Optional<ServiceMapGraph> current(TenantId tenantId) {
    if (tenantId == null) {
      throw new IllegalArgumentException("tenantId: required");
    }
    Optional<Long> generation = records.currentNumber(tenantId);
    if (generation.isEmpty()) {
      return Optional.empty();
    }
    Key key = new Key(tenantId, generation.get());
    synchronized (cache) {
      ServiceMapGraph graph = cache.get(key);
      if (graph != null) {
        return Optional.of(graph);
      }
    }
    ServiceMapGraph graph = records.graph(tenantId, generation.get());
    synchronized (cache) {
      cache.putIfAbsent(key, graph);
    }
    return Optional.of(graph);
  }
}
