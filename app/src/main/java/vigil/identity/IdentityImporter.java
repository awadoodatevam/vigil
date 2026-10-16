package vigil.identity;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vigil.identity.api.IdentityImport;
import vigil.identity.api.IdentityPart;
import vigil.identity.api.IdentityPart.GrantPart;
import vigil.identity.api.IdentityPart.MembershipPart;
import vigil.identity.api.IdentityPart.TeamPart;
import vigil.identity.api.IdentityPart.UserPart;
import vigil.identity.api.Permission;
import vigil.shared.Ids;
import vigil.shared.Key;
import vigil.shared.TenantId;
import vigil.shared.Timestamps;
import vigil.shared.VigilError;
import vigil.tenancy.api.TenantService;
import vigil.tenancy.api.TenantState;
import vigil.tenancy.api.TenantView;

/** The seed import of part 2: teams, users, memberships, grants (AR-B-11, DM-R-57, AR-S-06). */
@Service
class IdentityImporter implements IdentityImport {

  private static final int NAME_MAX = 100;
  private static final Set<String> PERMISSIONS =
      Arrays.stream(Permission.values()).map(Permission::key).collect(Collectors.toSet());

  private final JdbcClient jdbc;
  private final Clock clock;
  private final Passwords passwords;
  private final TenantService tenants;

  IdentityImporter(JdbcClient jdbc, Clock clock, Passwords passwords, TenantService tenants) {
    this.jdbc = jdbc;
    this.clock = clock;
    this.passwords = passwords;
    this.tenants = tenants;
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED)
  public Map<String, UUID> importPart(
      TenantId tenantId, IdentityPart part, Instant importStartedAt, Map<String, UUID> handles) {
    if (tenantId == null) {
      throw new IllegalArgumentException("tenantId: required");
    }
    checkShape(part);
    List<String> problems = problems(tenantId, part);
    if (!problems.isEmpty()) {
      throw new VigilError(
          VigilError.Code.INVALID, "Part identity refused.", Map.of("problems", problems));
    }
    store(tenantId, part);
    return Map.of();
  }

  private static void checkShape(IdentityPart part) {
    if (part == null) {
      throw new IllegalArgumentException("part: required");
    }
    notNull(part.teams(), "teams");
    notNull(part.users(), "users");
    notNull(part.memberships(), "memberships");
    notNull(part.grants(), "grants");
    for (GrantPart grant : part.grants()) {
      if (grant.permissions() == null) {
        throw new IllegalArgumentException("grants: permissions: required");
      }
    }
  }

  private static void notNull(List<?> list, String field) {
    if (list == null || list.stream().anyMatch(item -> item == null)) {
      throw new IllegalArgumentException(field + ": a list without null entries");
    }
  }

  /** Every problem of the part, in the order of P-08 spec Section 6.5.3. */
  private List<String> problems(TenantId tenantId, IdentityPart part) {
    List<String> problems = new ArrayList<>();
    Optional<TenantView> tenant = tenants.tenant(tenantId);
    if (tenant.isEmpty() || tenant.get().state() != TenantState.PROVISIONING) {
      problems.add("tenant is not provisioning");
    }
    if (hasRecords(tenantId)) {
      problems.add("identity records already exist");
    }
    List<String> teamKeys = part.teams().stream().map(TeamPart::teamKey).toList();
    List<String> usernames = part.users().stream().map(UserPart::username).toList();
    Set<String> invalidTeams = new LinkedHashSet<>();
    teamKeys.stream().filter(k -> !isKey(k)).forEach(k -> invalidTeams.add(text(k)));
    invalidTeams.forEach(k -> problems.add("teams: " + k + " is not a valid key"));
    Set<String> invalidUsers = new LinkedHashSet<>();
    usernames.stream().filter(u -> !isKey(u)).forEach(u -> invalidUsers.add(text(u)));
    invalidUsers.forEach(u -> problems.add("users: " + u + " is not a valid key"));
    duplicates(teamKeys).forEach(k -> problems.add("teams: " + k + " appears more than once"));
    duplicates(usernames).forEach(u -> problems.add("users: " + u + " appears more than once"));
    Set<String> badNames = new LinkedHashSet<>();
    for (TeamPart team : part.teams()) {
      if (!lengthBetween(team.name(), 1, NAME_MAX)) {
        badNames.add("teams: name of " + text(team.teamKey()) + ": 1 to 100 characters");
      }
    }
    problems.addAll(badNames);
    Set<String> badDisplayNames = new LinkedHashSet<>();
    Set<String> noPasswords = new LinkedHashSet<>();
    for (UserPart user : part.users()) {
      if (!lengthBetween(user.displayName(), 1, NAME_MAX)) {
        badDisplayNames.add(
            "users: display_name of " + text(user.username()) + ": 1 to 100 characters");
      }
      if (user.password() == null || user.password().isEmpty()) {
        noPasswords.add("users: no password for " + text(user.username()));
      }
    }
    problems.addAll(badDisplayNames);
    problems.addAll(noPasswords);
    Set<String> knownTeams = new HashSet<>(teamKeys);
    Set<String> knownUsers = new HashSet<>(usernames);
    Set<String> unknownTeams = new LinkedHashSet<>();
    Set<String> unknownUsers = new LinkedHashSet<>();
    List<String> pairs = new ArrayList<>();
    for (MembershipPart membership : part.memberships()) {
      if (!knownTeams.contains(membership.teamKey())) {
        unknownTeams.add("memberships: unknown team " + text(membership.teamKey()));
      }
      if (!knownUsers.contains(membership.username())) {
        unknownUsers.add("memberships: unknown user " + text(membership.username()));
      }
      pairs.add(text(membership.teamKey()) + " " + text(membership.username()));
    }
    problems.addAll(unknownTeams);
    problems.addAll(unknownUsers);
    duplicates(pairs).forEach(p -> problems.add("memberships: " + p + " appears more than once"));
    Set<String> grantUsers = new LinkedHashSet<>();
    Set<String> unknownPermissions = new LinkedHashSet<>();
    List<String> grants = new ArrayList<>();
    for (GrantPart grant : part.grants()) {
      if (!knownUsers.contains(grant.username())) {
        grantUsers.add("permission_grants: unknown user " + text(grant.username()));
      }
      for (String permission : grant.permissions()) {
        if (!PERMISSIONS.contains(permission)) {
          unknownPermissions.add("permission_grants: unknown permission " + text(permission));
        }
        grants.add(text(grant.username()) + " " + text(permission));
      }
    }
    problems.addAll(grantUsers);
    problems.addAll(unknownPermissions);
    duplicates(grants)
        .forEach(g -> problems.add("permission_grants: " + g + " appears more than once"));
    return problems;
  }

  private void store(TenantId tenantId, IdentityPart part) {
    Timestamp now = Timestamp.from(Timestamps.now(clock));
    Map<String, UUID> teamIds = new HashMap<>();
    for (TeamPart team : part.teams()) {
      UUID id = Ids.newId();
      teamIds.put(team.teamKey(), id);
      jdbc.sql(
              "INSERT INTO identity.team (id, tenant_id, created_at, updated_at, team_key, name,"
                  + " active) VALUES (:id, :tenantId, :now, :now, :teamKey, :name, true)")
          .param("id", id)
          .param("tenantId", tenantId.value())
          .param("now", now)
          .param("teamKey", team.teamKey())
          .param("name", team.name())
          .update();
    }
    Map<String, UUID> userIds = new HashMap<>();
    for (UserPart user : part.users()) {
      UUID id = Ids.newId();
      userIds.put(user.username(), id);
      jdbc.sql(
              "INSERT INTO identity.\"user\" (id, tenant_id, created_at, updated_at, username,"
                  + " display_name, password_hash, active) VALUES (:id, :tenantId, :now, :now,"
                  + " :username, :displayName, :passwordHash, true)")
          .param("id", id)
          .param("tenantId", tenantId.value())
          .param("now", now)
          .param("username", user.username())
          .param("displayName", user.displayName())
          .param("passwordHash", passwords.hash(user.password()))
          .update();
    }
    for (MembershipPart membership : part.memberships()) {
      jdbc.sql(
              "INSERT INTO identity.team_membership (id, tenant_id, created_at, updated_at,"
                  + " team_id, user_id, active) VALUES (:id, :tenantId, :now, :now, :teamId,"
                  + " :userId, true)")
          .param("id", Ids.newId())
          .param("tenantId", tenantId.value())
          .param("now", now)
          .param("teamId", teamIds.get(membership.teamKey()))
          .param("userId", userIds.get(membership.username()))
          .update();
    }
    for (GrantPart grant : part.grants()) {
      for (String permission : grant.permissions()) {
        jdbc.sql(
                "INSERT INTO identity.permission_grant (id, tenant_id, created_at, updated_at,"
                    + " user_id, permission, active) VALUES (:id, :tenantId, :now, :now, :userId,"
                    + " :permission, true)")
            .param("id", Ids.newId())
            .param("tenantId", tenantId.value())
            .param("now", now)
            .param("userId", userIds.get(grant.username()))
            .param("permission", permission)
            .update();
      }
    }
  }

  private boolean hasRecords(TenantId tenantId) {
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM identity.team WHERE tenant_id = :tenantId)"
                + " OR EXISTS (SELECT 1 FROM identity.\"user\" WHERE tenant_id = :tenantId)"
                + " OR EXISTS (SELECT 1 FROM identity.team_membership WHERE tenant_id = :tenantId)"
                + " OR EXISTS (SELECT 1 FROM identity.permission_grant WHERE tenant_id = :tenantId)")
        .param("tenantId", tenantId.value())
        .query(Boolean.class)
        .single();
  }

  /** The values that appear more than once, each once, in the order of first appearance. */
  private static Set<String> duplicates(List<String> values) {
    Map<String, Integer> counts = new LinkedHashMap<>();
    values.forEach(value -> counts.merge(text(value), 1, Integer::sum));
    Set<String> repeated = new LinkedHashSet<>();
    counts.forEach(
        (value, count) -> {
          if (count > 1) {
            repeated.add(value);
          }
        });
    return repeated;
  }

  private static boolean isKey(String value) {
    try {
      new Key(value);
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private static boolean lengthBetween(String value, int min, int max) {
    if (value == null) {
      return false;
    }
    int length = value.codePointCount(0, value.length());
    return length >= min && length <= max;
  }

  private static String text(String value) {
    return String.valueOf(value);
  }
}
