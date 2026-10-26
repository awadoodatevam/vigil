package vigil.runner.api;

/** An operation (DM-E-12); {@code parametersJson} is the stored array of parameter definitions. */
public record OperationView(
    String operationKey,
    String name,
    PermissionKind permissionKind,
    String commandTemplate,
    String parametersJson,
    String outputParser,
    long timeoutSeconds,
    boolean active) {}
