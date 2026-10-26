package vigil.runner.api;

import java.util.UUID;

/** A target access grant (DM-E-13). */
public record GrantView(
    UUID id,
    String targetKey,
    UUID connectorInstanceId,
    PermissionKind permissionKind,
    UUID credentialRefId,
    boolean active) {}
