package vigil.workflows.api;

import java.time.Instant;
import java.util.UUID;
import vigil.shared.Actor;

/**
 * A workflow version (DM-E-47). {@code contentJson} is the workflow content (DM-V-05) and {@code
 * changeListJson} the change list, each as JSON text; {@code originRef} is {@code null} when not
 * set.
 */
public record WorkflowVersionView(
    UUID id,
    UUID workflowId,
    long number,
    String contentJson,
    VersionOrigin origin,
    UUID originRef,
    Actor publishedBy,
    Instant publishedAt,
    String changeListJson) {}
