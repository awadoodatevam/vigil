package vigil.connectors.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vigil.shared.TenantId;

/** The requests of other modules to vigil-connectors (PM-M04). */
public interface ConnectorService {

  /** The instance with that id in the tenant, or empty. */
  Optional<ConnectorInstanceView> instance(TenantId tenantId, UUID instanceId);

  /**
   * The tenant's active instances of {@code kind}, by {@code name} in code point order, then id
   * (AR-K-00c). Not an external call.
   */
  List<ConnectorInstanceView> activeInstances(TenantId tenantId, ConnectorKind kind);

  /** The credential reference with that id in the tenant, or empty. */
  Optional<CredentialReferenceView> credentialReference(TenantId tenantId, UUID credentialRefId);

  /**
   * Parses a received body with the monitoring connector of {@code kind}. Not an external call;
   * {@code contentType} may be {@code null}.
   */
  ParseResult parse(ConnectorKind kind, byte[] body, String contentType);

  /**
   * Tests the connection of a monitoring source instance (PM-M04-04).
   *
   * <p>external call: not inside a transaction
   */
  ConnectionTestResult testConnection(TenantId tenantId, UUID instanceId);
}
