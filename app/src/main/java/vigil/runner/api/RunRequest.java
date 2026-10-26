package vigil.runner.api;

/**
 * A request to run an operation on a target (DM-E-14). {@code permissionKind} is the permission the
 * requester runs with: vigil-diagnostics only {@code DIAGNOSTIC}; vigil-actions {@code ACTION} to
 * execute and {@code DIAGNOSTIC} for its state checks (DM-V-08). A request for an operation of the
 * other kind is refused with an exception and stores nothing.
 */
public record RunRequest(
    String requestId,
    PermissionKind permissionKind,
    String operationKey,
    String targetKey,
    String parametersJson) {}
