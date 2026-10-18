package vigil.connectors;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import vigil.connectors.api.ConnectorKind;
import vigil.connectors.api.ConnectorType;
import vigil.connectors.api.MonitoringSourceConnector;

/**
 * The registry of monitoring source connectors by kind (PM-M04-02): a new kind is one {@link
 * ConnectorKind} constant and one bean.
 */
@Component
class MonitoringConnectors {

  private final Map<ConnectorKind, MonitoringSourceConnector> byKind =
      new EnumMap<>(ConnectorKind.class);

  MonitoringConnectors(List<MonitoringSourceConnector> connectors) {
    for (MonitoringSourceConnector connector : connectors) {
      ConnectorKind kind = connector.kind();
      if (kind.connectorType() != ConnectorType.MONITORING_SOURCE) {
        throw new IllegalStateException(
            "connector for " + kind.key() + " is not a monitoring source kind");
      }
      if (byKind.putIfAbsent(kind, connector) != null) {
        throw new IllegalStateException("two connectors for " + kind.key());
      }
    }
  }

  Optional<MonitoringSourceConnector> find(ConnectorKind kind) {
    return Optional.ofNullable(byKind.get(kind));
  }
}
