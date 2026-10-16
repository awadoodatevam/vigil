package vigil.identity;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vigil.identity.api.IdentityService;
import vigil.identity.api.NewSession;
import vigil.identity.api.Permission;
import vigil.identity.api.SessionCheck;
import vigil.identity.api.TeamView;
import vigil.identity.api.UserView;
import vigil.shared.Ids;
import vigil.shared.Key;
import vigil.shared.TenantId;
import vigil.shared.Timestamps;
import vigil.tenancy.api.TenantService;
import vigil.tenancy.api.TenantState;
import vigil.tenancy.api.TenantView;

/** Users, teams, permissions and login sessions (PM-M02; DM-E-03 to DM-E-07). */
@Service
class Identities implements IdentityService {

  private final JdbcClient jdbc;
  private final Clock clock;
  private final Passwords passwords;
  private final TenantService tenants;

  Identities(JdbcClient jdbc, Clock clock, Passwords passwords, TenantService tenants) {
    this.jdbc = jdbc;
    this.clock = clock;
    this.passwords = passwords;
    this.tenants = tenants;
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED)
  public Optional<NewSession> login(TenantId tenantId, String username, String password) {
    required(tenantId, "tenantId");
    Optional<Key> key = key(username);
    if (key.isEmpty()) {
      return Optional.empty();
    }
    if (!tenants.tenant(tenantId).map(TenantView::state).equals(Optional.of(TenantState.READY))) {
      return Optional.empty();
    }
    Optional<Map<String, Object>> user =
        jdbc
            .sql(
                "SELECT id, password_hash FROM identity.\"user\""
                    + " WHERE tenant_id = :tenantId AND username = :username AND active")
            .param("tenantId", tenantId.value())
            .param("username", key.get().value())
            .query()
            .listOfRows()
            .stream()
            .findFirst();
    if (user.isEmpty() || !passwords.matches(password, (String) user.get().get("password_hash"))) {
      return Optional.empty();
    }
    UUID userId = (UUID) user.get().get("id");
    byte[] token = passwords.newToken();
    Instant now = Timestamps.now(clock);
    Instant expiresAt = now.plus(SessionLimits.LIFETIME);
    UUID sessionId = Ids.newId();
    jdbc.sql(
            "INSERT INTO identity.login_session (id, tenant_id, created_at, updated_at, user_id,"
                + " token_hash, state, expires_at, last_activity_at) VALUES (:id, :tenantId, :now,"
                + " :now, :userId, :tokenHash, 'active', :expiresAt, :now)")
        .param("id", sessionId)
        .param("tenantId", tenantId.value())
        .param("now", Timestamp.from(now))
        .param("userId", userId)
        .param("tokenHash", Passwords.tokenHash(token))
        .param("expiresAt", Timestamp.from(expiresAt))
        .update();
    return Optional.of(
        new NewSession(
            sessionId, Passwords.encode(token), expiresAt, user(tenantId, userId).orElseThrow()));
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED)
  public SessionCheck authenticate(TenantId tenantId, String token) {
    required(tenantId, "tenantId");
    Optional<byte[]> bytes = Passwords.decode(token);
    if (bytes.isEmpty()) {
      return new SessionCheck(SessionCheck.Status.UNKNOWN, null, null);
    }
    Optional<Map<String, Object>> session =
        jdbc
            .sql(
                "SELECT id, user_id, state, expires_at, last_activity_at FROM identity.login_session"
                    + " WHERE tenant_id = :tenantId AND token_hash = :tokenHash FOR UPDATE")
            .param("tenantId", tenantId.value())
            .param("tokenHash", Passwords.tokenHash(bytes.get()))
            .query()
            .listOfRows()
            .stream()
            .findFirst();
    if (session.isEmpty()) {
      return new SessionCheck(SessionCheck.Status.UNKNOWN, null, null);
    }
    Map<String, Object> row = session.get();
    UUID sessionId = (UUID) row.get("id");
    if (!"active".equals(row.get("state"))) {
      return new SessionCheck(SessionCheck.Status.ENDED, null, null);
    }
    Instant now = Timestamps.now(clock);
    Instant lastActivity = instant(row.get("last_activity_at"));
    if (SessionLimits.expired(now, instant(row.get("expires_at")), lastActivity)) {
      end(tenantId, sessionId, "expired", now);
      return new SessionCheck(SessionCheck.Status.ENDED, null, null);
    }
    if (Duration.between(lastActivity, now).compareTo(SessionLimits.ACTIVITY_UPDATE) >= 0) {
      jdbc.sql(
              "UPDATE identity.login_session SET last_activity_at = :now, updated_at = :now"
                  + " WHERE tenant_id = :tenantId AND id = :id")
          .param("now", Timestamp.from(now))
          .param("tenantId", tenantId.value())
          .param("id", sessionId)
          .update();
    }
    UserView user = user(tenantId, (UUID) row.get("user_id")).orElseThrow();
    return new SessionCheck(SessionCheck.Status.ACTIVE, sessionId, user);
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED)
  public void logout(TenantId tenantId, UUID sessionId) {
    required(tenantId, "tenantId");
    required(sessionId, "sessionId");
    end(tenantId, sessionId, "logout", Timestamps.now(clock));
  }

  private void end(TenantId tenantId, UUID sessionId, String reason, Instant now) {
    jdbc.sql(
            "UPDATE identity.login_session SET state = 'ended', end_reason = :reason,"
                + " ended_at = :now, updated_at = :now"
                + " WHERE tenant_id = :tenantId AND id = :id AND state = 'active'")
        .param("reason", reason)
        .param("now", Timestamp.from(now))
        .param("tenantId", tenantId.value())
        .param("id", sessionId)
        .update();
  }

  @Override
  @Transactional(readOnly = true)
  public boolean hasPermission(TenantId tenantId, UUID userId, Permission permission) {
    required(tenantId, "tenantId");
    required(userId, "userId");
    required(permission, "permission");
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM identity.permission_grant g"
                + " JOIN identity.\"user\" u ON u.id = g.user_id AND u.tenant_id = g.tenant_id"
                + " WHERE g.tenant_id = :tenantId AND g.user_id = :userId"
                + " AND g.permission = :permission AND g.active AND u.active)")
        .param("tenantId", tenantId.value())
        .param("userId", userId)
        .param("permission", permission.key())
        .query(Boolean.class)
        .single();
  }

  @Override
  @Transactional(readOnly = true)
  public boolean isMember(TenantId tenantId, UUID userId, Key teamKey) {
    required(tenantId, "tenantId");
    required(userId, "userId");
    required(teamKey, "teamKey");
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM identity.team_membership m"
                + " JOIN identity.team t ON t.id = m.team_id AND t.tenant_id = m.tenant_id"
                + " JOIN identity.\"user\" u ON u.id = m.user_id AND u.tenant_id = m.tenant_id"
                + " WHERE m.tenant_id = :tenantId AND m.user_id = :userId"
                + " AND t.team_key = :teamKey AND m.active AND t.active AND u.active)")
        .param("tenantId", tenantId.value())
        .param("userId", userId)
        .param("teamKey", teamKey.value())
        .query(Boolean.class)
        .single();
  }

  @Override
  @Transactional(readOnly = true)
  public List<UserView> teamMembers(TenantId tenantId, Key teamKey) {
    required(tenantId, "tenantId");
    required(teamKey, "teamKey");
    List<UUID> ids =
        jdbc.sql(
                "SELECT u.id FROM identity.team_membership m"
                    + " JOIN identity.team t ON t.id = m.team_id AND t.tenant_id = m.tenant_id"
                    + " JOIN identity.\"user\" u ON u.id = m.user_id AND u.tenant_id = m.tenant_id"
                    + " WHERE m.tenant_id = :tenantId AND t.team_key = :teamKey"
                    + " AND m.active AND t.active AND u.active")
            .param("tenantId", tenantId.value())
            .param("teamKey", teamKey.value())
            .query(UUID.class)
            .list();
    return ids.stream()
        .distinct()
        .map(id -> user(tenantId, id).orElseThrow())
        .sorted(Comparator.comparing(UserView::username))
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<UserView> user(TenantId tenantId, UUID userId) {
    required(tenantId, "tenantId");
    required(userId, "userId");
    return userWhere(tenantId, "id = :value", userId);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<UserView> userByUsername(TenantId tenantId, Key username) {
    required(tenantId, "tenantId");
    required(username, "username");
    return userWhere(tenantId, "username = :value", username.value());
  }

  private Optional<UserView> userWhere(TenantId tenantId, String condition, Object value) {
    Optional<Map<String, Object>> row =
        jdbc
            .sql(
                "SELECT id, username, display_name, active FROM identity.\"user\""
                    + " WHERE tenant_id = :tenantId AND "
                    + condition)
            .param("tenantId", tenantId.value())
            .param("value", value)
            .query()
            .listOfRows()
            .stream()
            .findFirst();
    if (row.isEmpty()) {
      return Optional.empty();
    }
    UUID userId = (UUID) row.get().get("id");
    Set<Permission> permissions = EnumSet.noneOf(Permission.class);
    jdbc.sql(
            "SELECT permission FROM identity.permission_grant"
                + " WHERE tenant_id = :tenantId AND user_id = :userId AND active")
        .param("tenantId", tenantId.value())
        .param("userId", userId)
        .query(String.class)
        .list()
        .forEach(p -> permissions.add(Permission.valueOf(p.toUpperCase(Locale.ROOT))));
    Set<Key> teamKeys = new TreeSet<>();
    jdbc.sql(
            "SELECT t.team_key FROM identity.team_membership m"
                + " JOIN identity.team t ON t.id = m.team_id AND t.tenant_id = m.tenant_id"
                + " WHERE m.tenant_id = :tenantId AND m.user_id = :userId AND m.active AND t.active")
        .param("tenantId", tenantId.value())
        .param("userId", userId)
        .query(String.class)
        .list()
        .forEach(k -> teamKeys.add(new Key(k)));
    return Optional.of(
        new UserView(
            userId,
            new Key((String) row.get().get("username")),
            (String) row.get().get("display_name"),
            (Boolean) row.get().get("active"),
            Set.copyOf(permissions),
            Set.copyOf(teamKeys)));
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<TeamView> team(TenantId tenantId, Key teamKey) {
    required(tenantId, "tenantId");
    required(teamKey, "teamKey");
    return jdbc.sql(
            "SELECT id, team_key, name, active FROM identity.team"
                + " WHERE tenant_id = :tenantId AND team_key = :teamKey")
        .param("tenantId", tenantId.value())
        .param("teamKey", teamKey.value())
        .query(
            (rs, row) ->
                new TeamView(
                    rs.getObject("id", UUID.class),
                    new Key(rs.getString("team_key")),
                    rs.getString("name"),
                    rs.getBoolean("active")))
        .optional();
  }

  private static Optional<Key> key(String value) {
    try {
      return Optional.of(new Key(value));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  private static Instant instant(Object timestamp) {
    return ((Timestamp) timestamp).toInstant();
  }

  private static void required(Object value, String field) {
    if (value == null) {
      throw new IllegalArgumentException(field + ": required");
    }
  }
}
