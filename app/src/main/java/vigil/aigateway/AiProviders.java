package vigil.aigateway;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import vigil.connectors.api.AiProviderConnector;
import vigil.connectors.api.ConnectorKind;
import vigil.connectors.api.ConnectorType;

/**
 * The registry of AI provider connectors by kind (AR-K-00e, PM-M04-02): a new kind is one {@link
 * ConnectorKind} constant and one bean.
 */
@Component
class AiProviders {

  private final Map<ConnectorKind, AiProviderConnector> byKind = new EnumMap<>(ConnectorKind.class);

  AiProviders(List<AiProviderConnector> connectors) {
    for (AiProviderConnector connector : connectors) {
      ConnectorKind kind = connector.kind();
      if (kind.connectorType() != ConnectorType.AI_PROVIDER) {
        throw new IllegalStateException(
            "connector for " + kind.key() + " is not an AI provider kind");
      }
      if (byKind.putIfAbsent(kind, connector) != null) {
        throw new IllegalStateException("two connectors for " + kind.key());
      }
    }
  }

  Optional<AiProviderConnector> find(ConnectorKind kind) {
    return Optional.ofNullable(byKind.get(kind));
  }
}
