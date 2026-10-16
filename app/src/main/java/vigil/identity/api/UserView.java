package vigil.identity.api;

import java.util.Set;
import java.util.UUID;
import vigil.shared.Key;

/**
 * A user (DM-E-03): {@code permissions} are the user's active grants; {@code teamKeys} the keys of
 * the active teams in which the user has an active membership.
 */
public record UserView(
    UUID id,
    Key username,
    String displayName,
    boolean active,
    Set<Permission> permissions,
    Set<Key> teamKeys) {}
