package vigil.ticketing;

import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import vigil.connectors.api.ConnectorInstanceView;
import vigil.connectors.api.ConnectorKind;
import vigil.connectors.api.ConnectorService;
import vigil.connectors.api.TicketingConnector;
import vigil.shared.TenantId;
import vigil.ticketing.api.TicketLinkView;

/**
 * The Jira connector instances of a tenant (CTX-D-069 (2)): the first active {@code jira_cloud}
 * instance for a create, the link's own instance afterwards, their settings, and the {@link
 * TicketingConnector} bean of the instance's kind.
 */
@Component
class JiraInstances {

  private final ConnectorService connectors;
  private final List<TicketingConnector> beans;

  JiraInstances(ConnectorService connectors, List<TicketingConnector> beans) {
    this.connectors = connectors;
    this.beans = List.copyOf(beans);
  }

  /** The first active {@code jira_cloud} instance of the tenant by name, or empty. */
  Optional<ConnectorInstanceView> firstActive(TenantId tenantId) {
    return connectors.activeInstances(tenantId, ConnectorKind.JIRA_CLOUD).stream().findFirst();
  }

  /** The instance the link was made with, or empty when there is none or it is not active. */
  Optional<ConnectorInstanceView> ofLink(TenantId tenantId, TicketLinkView link) {
    return connectors
        .instance(tenantId, link.connectorInstanceId())
        .filter(ConnectorInstanceView::active);
  }

  /** The ticketing connector bean of the instance's kind. */
  TicketingConnector connector(ConnectorInstanceView instance) {
    return beans.stream()
        .filter(bean -> bean.kind() == instance.kind())
        .findFirst()
        .orElseThrow(
            () -> new IllegalStateException("no ticketing connector for " + instance.kind().key()));
  }

  /** The string setting {@code key} of the instance. */
  static String setting(ConnectorInstanceView instance, String key) {
    JsonNode value = instance.settings() == null ? null : instance.settings().get(key);
    if (value == null || !value.isString()) {
      throw new IllegalStateException(
          "connector instance " + instance.id() + " has no " + key + " setting");
    }
    return value.stringValue();
  }
}
