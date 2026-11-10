package vigil.aigateway.api;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import vigil.shared.TenantId;

/**
 * An SL-T-3 request to the named provider only (DM-E72-R2); {@code incidentId} MAY be {@code null}.
 */
public record EmbeddingRequest(
    TenantId tenantId,
    String providerKey,
    UUID requestGroupId,
    UUID incidentId,
    Set<DataCategory> dataCategories,
    List<String> texts) {}
