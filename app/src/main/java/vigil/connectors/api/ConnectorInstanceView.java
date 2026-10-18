package vigil.connectors.api;

import java.util.UUID;
import tools.jackson.databind.JsonNode;
import vigil.shared.TenantId;

/**
 * A connector instance (DM-E-10). {@code credentialRefId} is {@code null} when the instance has
 * none; {@code settings} is a JSON object.
 */
public record ConnectorInstanceView(
    TenantId tenantId,
    UUID id,
    String name,
    ConnectorKind kind,
    String address,
    JsonNode settings,
    UUID credentialRefId,
    boolean active) {}
