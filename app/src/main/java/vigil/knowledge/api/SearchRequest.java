package vigil.knowledge.api;

import java.util.List;
import java.util.UUID;
import vigil.shared.TenantId;

/**
 * A similar-incident search (PM-M16-03); {@code runId} and {@code hypothesisComponentKey} MAY be
 * {@code null}.
 */
public record SearchRequest(
    TenantId tenantId,
    UUID incidentId,
    UUID runId,
    List<String> alertTypes,
    List<String> serviceKeys,
    String hypothesisComponentKey) {}
