package vigil.runner;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import vigil.connectors.api.ConnectorKind;
import vigil.connectors.api.ConnectorType;
import vigil.connectors.api.TargetConnector;

/**
 * The registry of target connectors by kind (AR-K-00b, PM-M04-02): a new kind is one {@link
 * ConnectorKind} constant and one bean.
 */
@Component
class TargetConnectors {

  private final Map<ConnectorKind, TargetConnector> byKind = new EnumMap<>(ConnectorKind.class);

  TargetConnectors(List<TargetConnector> connectors) {
    for (TargetConnector connector : connectors) {
      ConnectorKind kind = connector.kind();
      if (kind.connectorType() != ConnectorType.TARGET) {
        throw new IllegalStateException("connector for " + kind.key() + " is not a target kind");
      }
      if (byKind.putIfAbsent(kind, connector) != null) {
        throw new IllegalStateException("two connectors for " + kind.key());
      }
    }
  }

  Optional<TargetConnector> find(ConnectorKind kind) {
    return Optional.ofNullable(byKind.get(kind));
  }
}
