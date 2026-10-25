package vigil.workflows.api;

import java.util.UUID;

/** The tenant's workflow (DM-E-45) with its active version marker. */
public record WorkflowView(UUID id, String name, UUID activeVersionId) {}
