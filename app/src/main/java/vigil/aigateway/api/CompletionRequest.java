package vigil.aigateway.api;

import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import vigil.shared.TenantId;

/** An SL-T-1 or SL-T-2 request (AR-G-10); {@code incidentId} MAY be {@code null}. */
public record CompletionRequest(
    TenantId tenantId,
    AiTask task,
    UUID requestGroupId,
    UUID incidentId,
    String systemText,
    List<AiPart> parts,
    String outputSchemaName,
    JsonNode outputSchema,
    int maxOutputUnits) {}
