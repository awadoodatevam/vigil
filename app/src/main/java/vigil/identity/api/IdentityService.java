package vigil.identity.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vigil.shared.Key;
import vigil.shared.TenantId;

/** Users, teams, permissions and login sessions (PM-M02). */
public interface IdentityService {

  /**
   * DM-SM02-T1: a new session for the right password of an active user of a {@code ready} tenant;
   * empty otherwise (PM-M02-02). No permission is needed (DM-E06-R2).
   */
  Optional<NewSession> login(TenantId tenantId, String username, String password);

  /** The session a token names; ends it as {@code expired} when past its limits (DM-SM02-T3). */
  SessionCheck authenticate(TenantId tenantId, String token);

  /** DM-SM02-T2: ends an active session as {@code logout}; otherwise nothing changes. */
  void logout(TenantId tenantId, UUID sessionId);

  /** True only for an active user with an active grant of the permission (PM-M02-03). */
  boolean hasPermission(TenantId tenantId, UUID userId, Permission permission);

  /** True only for an active user with an active membership in the active team (PM-M02-03). */
  boolean isMember(TenantId tenantId, UUID userId, Key teamKey);

  /** The active members of the active team, by username (PM-M02-04); empty for no such team. */
  List<UserView> teamMembers(TenantId tenantId, Key teamKey);

  Optional<UserView> user(TenantId tenantId, UUID userId);

  Optional<UserView> userByUsername(TenantId tenantId, Key username);

  Optional<TeamView> team(TenantId tenantId, Key teamKey);
}
