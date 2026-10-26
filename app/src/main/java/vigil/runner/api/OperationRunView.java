package vigil.runner.api;

import java.time.Instant;
import java.util.UUID;

/**
 * An operation run (DM-E-14). {@code outputValuesJson} is the ordered array of {@code {name,
 * value}} objects of the operation's parser, or {@code null}; empty values are {@code null}.
 */
public record OperationRunView(
    UUID id,
    String operationKey,
    String targetKey,
    PermissionKind permissionKind,
    String parametersJson,
    String requestedBy,
    String requestId,
    OperationRunState state,
    Instant startedAt,
    Instant finishedAt,
    Long exitStatus,
    String outputValuesJson,
    String outputText,
    boolean outputTruncated,
    String error) {}
