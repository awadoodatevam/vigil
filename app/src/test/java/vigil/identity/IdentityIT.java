package vigil.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.yaml.snakeyaml.Yaml;
import vigil.identity.api.IdentityImport;
import vigil.identity.api.IdentityPart;
import vigil.identity.api.IdentityPart.GrantPart;
import vigil.identity.api.IdentityPart.MembershipPart;
import vigil.identity.api.IdentityPart.TeamPart;
import vigil.identity.api.IdentityPart.UserPart;
import vigil.identity.api.IdentityService;
import vigil.identity.api.NewSession;
import vigil.identity.api.Permission;
import vigil.identity.api.SessionCheck;
import vigil.identity.api.UserView;
import vigil.shared.Actor;
import vigil.shared.CanonicalJson;
import vigil.shared.Ids;
import vigil.shared.Key;
import vigil.shared.TenantId;
import vigil.shared.VigilError;
import vigil.tenancy.api.TenantImport;
import vigil.tenancy.api.TenantService;
import vigil.tenancy.api.TenantSettingsPart;
import vigil.tenancy.api.TenantSettingsView;
import vigil.testsupport.MutableClock;
import vigil.testsupport.VigilDatabase;
import vigil.testsupport.VigilTestApp;

class IdentityIT {

  private static final Path DD_F_01 = Path.of("../docs/31-demo-data/01-tenant-identity.yaml");
  private static final MutableClock CLOCK = new MutableClock(Instant.parse("2026-10-16T08:00:00Z"));

  private static VigilDatabase database;
  private static VigilTestApp app;
  private static TenantService tenants;
  private static TenantImport tenantImport;
  private static IdentityService identity;
  private static IdentityImport identityImport;
  private static JdbcClient appJdbc;
  private static JdbcClient postgres;

  private static Map<String, Object> data;
  private static TenantSettingsPart settingsPart;
  private static IdentityPart identityPart;
  private static Map<String, String> passwords;
  private static TenantId tenant;

  /** The test clock. */
  static class TestClock {

    @Bean
    @Primary
    Clock testClock() {
      return CLOCK;
    }
  }

  @BeforeAll
  @SuppressWarnings("unchecked")
  static void start() throws IOException {
    database = VigilDatabase.fresh();
    app = VigilTestApp.start(database, TestClock.class);
    app.awaitStarted();
    tenants = app.context().getBean(TenantService.class);
    tenantImport = app.context().getBean(TenantImport.class);
    identity = app.context().getBean(IdentityService.class);
    identityImport = app.context().getBean(IdentityImport.class);
    appJdbc = app.context().getBean(JdbcClient.class);
    postgres = database.postgres();

    data = new Yaml().load(Files.readString(DD_F_01));
    Map<String, Object> settings = (Map<String, Object>) data.get("tenant_settings");
    assertThat(settings.get("customer_logo")).isNull();
    settingsPart =
        new TenantSettingsPart(
            (String) settings.get("customer_name"),
            (String) settings.get("environment_label"),
            (String) settings.get("product_display_name"),
            null);
    passwords = new LinkedHashMap<>();
    List<TeamPart> teams = new ArrayList<>();
    for (Map<String, Object> team : (List<Map<String, Object>>) data.get("teams")) {
      teams.add(new TeamPart((String) team.get("team_key"), (String) team.get("name")));
    }
    List<UserPart> users = new ArrayList<>();
    for (Map<String, Object> user : (List<Map<String, Object>>) data.get("users")) {
      String username = (String) user.get("username");
      passwords.put(username, IdentityTypesTest.randomPassword());
      users.add(new UserPart(username, (String) user.get("display_name"), passwords.get(username)));
    }
    List<MembershipPart> memberships = new ArrayList<>();
    for (Map<String, Object> membership : (List<Map<String, Object>>) data.get("memberships")) {
      memberships.add(
          new MembershipPart(
              (String) membership.get("team_key"), (String) membership.get("username")));
    }
    List<GrantPart> grants = new ArrayList<>();
    grants().forEach((username, permissions) -> grants.add(new GrantPart(username, permissions)));
    identityPart = new IdentityPart(teams, users, memberships, grants);
    tenant = readyTenant();
  }

  @AfterAll
  static void stop() {
    app.close();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, List<String>> grants() {
    return (Map<String, List<String>>) data.get("permission_grants");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, String> displayNames() {
    return ((List<Map<String, Object>>) data.get("users"))
        .stream()
            .collect(
                Collectors.toMap(
                    u -> (String) u.get("username"), u -> (String) u.get("display_name")));
  }

  private static TenantId provisioning() {
    return tenants.createTenant("tenant " + Ids.newId(), Actor.module("vigil-setup"));
  }

  private static TenantId readyTenant() {
    TenantId created = provisioning();
    tenantImport.importPart(created, settingsPart, CLOCK.instant(), Map.of());
    assertThat(identityImport.importPart(created, identityPart, CLOCK.instant(), Map.of()))
        .isEmpty();
    tenants.markReady(created);
    return created;
  }

  private static UserView user(TenantId tenantId, String username) {
    return identity.userByUsername(tenantId, new Key(username)).orElseThrow();
  }

  private static int rows(String table, TenantId tenantId) {
    return postgres
        .sql("SELECT count(*) FROM identity." + table + " WHERE tenant_id = ?")
        .param(tenantId.value())
        .query(Integer.class)
        .single();
  }

  @SuppressWarnings("unchecked")
  private static List<String> problems(TenantId tenantId, IdentityPart part) {
    try {
      identityImport.importPart(tenantId, part, CLOCK.instant(), Map.of());
    } catch (VigilError error) {
      assertThat(error.code()).isEqualTo(VigilError.Code.INVALID);
      assertThat(error.message()).isEqualTo("Part identity refused.");
      return (List<String>) error.details().get("problems");
    }
    throw new AssertionError("the part was not refused");
  }

  @Test
  @DisplayName("DD-F-01 tenant settings of the demo data after import")
  void tenantSettings() {
    assertThat(tenants.settings(tenant))
        .isEqualTo(
            new TenantSettingsView(
                "Jazz", false, "Demo environment — fictional data", "Techfinity"));
  }

  @Test
  @DisplayName("SL-A3-07 the demo users, teams, memberships and grants of DD-F-01")
  void demoIdentity() {
    for (TeamPart team : identityPart.teams()) {
      assertThat(identity.team(tenant, new Key(team.teamKey())))
          .hasValueSatisfying(
              view -> {
                assertThat(view.name()).isEqualTo(team.name());
                assertThat(view.active()).isTrue();
              });
    }
    assertThat(identityPart.teams()).hasSize(9);
    assertThat(displayNames()).hasSize(5);
    displayNames()
        .forEach(
            (username, displayName) -> {
              UserView view = user(tenant, username);
              assertThat(view.displayName()).isEqualTo(displayName);
              assertThat(view.active()).isTrue();
              assertThat(view.permissions())
                  .extracting(Permission::key)
                  .containsExactlyInAnyOrderElementsOf(grants().get(username));
            });
    assertThat(user(tenant, "noc-l1").teamKeys()).containsExactly(new Key("noc-l1"));
    assertThat(user(tenant, "dbops-engineer").teamKeys()).containsExactly(new Key("db-ops"));
    assertThat(user(tenant, "dbops-lead").teamKeys()).containsExactly(new Key("db-ops"));
    assertThat(user(tenant, "admin").teamKeys()).isEmpty();
    assertThat(user(tenant, "presenter").teamKeys()).isEmpty();
  }

  @Test
  @DisplayName("PM-M02-01 users, teams, memberships and grants are stored as data, all active")
  void storedAsData() {
    assertThat(rows("\"user\"", tenant)).isEqualTo(5);
    assertThat(rows("team", tenant)).isEqualTo(9);
    assertThat(rows("team_membership", tenant)).isEqualTo(3);
    int grantCount = grants().values().stream().mapToInt(List::size).sum();
    assertThat(rows("permission_grant", tenant)).isEqualTo(grantCount);
    for (String table : List.of("\"user\"", "team", "team_membership", "permission_grant")) {
      assertThat(
              postgres
                  .sql(
                      "SELECT count(*) FROM identity."
                          + table
                          + " WHERE tenant_id = ? AND NOT active")
                  .param(tenant.value())
                  .query(Integer.class)
                  .single())
          .as("inactive rows of %s", table)
          .isZero();
    }
  }

  @Test
  @DisplayName("DM-E-03 user: username, display name, password hash, active")
  void userRecord() {
    Map<String, Object> row =
        postgres
            .sql("SELECT * FROM identity.\"user\" WHERE tenant_id = ? AND username = 'admin'")
            .param(tenant.value())
            .query()
            .singleRow();
    assertThat(row.get("display_name")).isEqualTo("Platform Administrator");
    assertThat((String) row.get("password_hash")).startsWith("$argon2id$");
    assertThat(row.get("active")).isEqualTo(true);
    assertThat(row.get("created_at")).isEqualTo(row.get("updated_at"));
  }

  @Test
  @DisplayName("DM-E-04 team: key, name, active")
  void teamRecord() {
    assertThat(identity.team(tenant, new Key("db-ops")).orElseThrow().name())
        .isEqualTo("Database Operations");
    assertThat(identity.team(tenant, new Key("no-such-team"))).isEmpty();
  }

  @Test
  @DisplayName("DM-E-05 team membership: team, user, active")
  void membershipRecord() {
    UUID team = identity.team(tenant, new Key("db-ops")).orElseThrow().id();
    List<UUID> members =
        postgres
            .sql(
                "SELECT user_id FROM identity.team_membership WHERE tenant_id = ? AND team_id = ? AND active")
            .params(tenant.value(), team)
            .query(UUID.class)
            .list();
    assertThat(members)
        .containsExactlyInAnyOrder(
            user(tenant, "dbops-engineer").id(), user(tenant, "dbops-lead").id());
  }

  @Test
  @DisplayName("DM-E-06 permission grant: user, permission, active")
  void grantRecord() {
    List<String> permissions =
        postgres
            .sql(
                "SELECT permission FROM identity.permission_grant WHERE tenant_id = ? AND user_id = ?")
            .params(tenant.value(), user(tenant, "dbops-lead").id())
            .query(String.class)
            .list();
    assertThat(permissions).containsExactlyInAnyOrder("acknowledge", "approve_action");
  }

  @Test
  @DisplayName(
      "AR-S-06 the import stores Argon2id hashes of the given passwords and never the password")
  void passwordsHashed() {
    Passwords encoder = new Passwords();
    for (Map.Entry<String, String> password : passwords.entrySet()) {
      String hash =
          postgres
              .sql(
                  "SELECT password_hash FROM identity.\"user\" WHERE tenant_id = ? AND username = ?")
              .params(tenant.value(), password.getKey())
              .query(String.class)
              .single();
      assertThat(hash).startsWith("$argon2id$v=19$m=19456,t=2,p=1$");
      assertThat(encoder.matches(password.getValue(), hash)).isTrue();
    }
    List<Map<String, Object>> columns =
        postgres
            .sql(
                "SELECT table_name, column_name FROM information_schema.columns"
                    + " WHERE table_schema = 'identity'"
                    + " AND data_type IN ('character varying', 'text', 'character')")
            .query()
            .listOfRows();
    assertThat(columns).isNotEmpty();
    for (Map<String, Object> column : columns) {
      for (String password : passwords.values()) {
        int found =
            postgres
                .sql(
                    "SELECT count(*) FROM identity.\""
                        + column.get("table_name")
                        + "\" WHERE \""
                        + column.get("column_name")
                        + "\" LIKE ?")
                .param("%" + password + "%")
                .query(Integer.class)
                .single();
        assertThat(found).as("password in %s", column).isZero();
      }
    }
  }

  private static IdentityPart minimal() {
    return new IdentityPart(
        List.of(new TeamPart("t1", "Team 1")),
        List.of(new UserPart("u1", "User 1", "secret-1"), new UserPart("u2", "User 2", "secret-2")),
        List.of(new MembershipPart("t1", "u1")),
        List.of(new GrantPart("u1", List.of("view"))));
  }

  private static IdentityPart with(
      List<TeamPart> teams,
      List<UserPart> users,
      List<MembershipPart> memberships,
      List<GrantPart> grants) {
    IdentityPart base = minimal();
    return new IdentityPart(
        teams == null ? base.teams() : teams,
        users == null ? base.users() : users,
        memberships == null ? base.memberships() : memberships,
        grants == null ? base.grants() : grants);
  }

  private static UserPart user(String username, String displayName, String password) {
    return new UserPart(username, displayName, password);
  }

  @Test
  @DisplayName("AR-B-11 the import of part 2: every problem text, all in order, nothing stored")
  void importPartTwo() {
    TeamPart t1 = new TeamPart("t1", "Team 1");
    UserPart u1 = user("u1", "User 1", "secret-1");
    UserPart u2 = user("u2", "User 2", "secret-2");
    Map<IdentityPart, String> single = new LinkedHashMap<>();
    single.put(
        with(List.of(t1, new TeamPart("bad key", "T")), null, null, null),
        "teams: bad key is not a valid key");
    single.put(
        with(List.of(t1, new TeamPart(null, "T")), null, null, null),
        "teams: null is not a valid key");
    single.put(
        with(null, List.of(u1, u2, user("bad user", "U", "s")), null, null),
        "users: bad user is not a valid key");
    single.put(
        with(null, List.of(u1, u2, user(null, "U", "s")), null, null),
        "users: null is not a valid key");
    single.put(with(List.of(t1, t1, t1), null, null, null), "teams: t1 appears more than once");
    single.put(with(null, List.of(u1, u2, u1, u1), null, null), "users: u1 appears more than once");
    single.put(
        with(List.of(t1, new TeamPart("t2", "")), null, null, null),
        "teams: name of t2: 1 to 100 characters");
    single.put(
        with(List.of(t1, new TeamPart("t2", null)), null, null, null),
        "teams: name of t2: 1 to 100 characters");
    single.put(
        with(List.of(t1, new TeamPart("t2", "x".repeat(101))), null, null, null),
        "teams: name of t2: 1 to 100 characters");
    single.put(
        with(null, List.of(u1, u2, user("u3", "", "s")), null, null),
        "users: display_name of u3: 1 to 100 characters");
    single.put(
        with(null, List.of(u1, u2, user("u3", null, "s")), null, null),
        "users: display_name of u3: 1 to 100 characters");
    single.put(
        with(null, List.of(u1, u2, user("u3", "U3", "")), null, null), "users: no password for u3");
    single.put(
        with(null, List.of(u1, u2, user("u3", "U3", null)), null, null),
        "users: no password for u3");
    single.put(
        with(
            null,
            null,
            List.of(new MembershipPart("t9", "u1"), new MembershipPart("t9", "u2")),
            null),
        "memberships: unknown team t9");
    single.put(
        with(null, null, List.of(new MembershipPart("t1", "u9")), null),
        "memberships: unknown user u9");
    single.put(
        with(
            null,
            null,
            List.of(new MembershipPart("t1", "u1"), new MembershipPart("t1", "u1")),
            null),
        "memberships: t1 u1 appears more than once");
    single.put(
        with(null, null, null, List.of(new GrantPart("u9", List.of("view")))),
        "permission_grants: unknown user u9");
    single.put(
        with(null, null, null, List.of(new GrantPart("u1", List.of("fly")))),
        "permission_grants: unknown permission fly");
    List<String> nullPermission = new ArrayList<>();
    nullPermission.add(null);
    single.put(
        with(null, null, null, List.of(new GrantPart("u1", nullPermission))),
        "permission_grants: unknown permission null");
    single.put(
        with(
            null,
            null,
            null,
            List.of(new GrantPart("u1", List.of("view")), new GrantPart("u1", List.of("view")))),
        "permission_grants: u1 view appears more than once");
    for (Map.Entry<IdentityPart, String> bad : single.entrySet()) {
      TenantId target = provisioning();
      assertThat(problems(target, bad.getKey()))
          .as("problems of %s", bad.getKey())
          .containsExactly(bad.getValue());
      for (String table : List.of("\"user\"", "team", "team_membership", "permission_grant")) {
        assertThat(rows(table, target)).isZero();
      }
    }

    IdentityPart everything =
        new IdentityPart(
            List.of(new TeamPart("bad key", "T"), t1, t1, new TeamPart("t2", "")),
            List.of(user("bad user", "U", "s"), u1, u1, user("u2", "", "s"), user("u3", "U3", "")),
            List.of(
                new MembershipPart("t9", "u1"),
                new MembershipPart("t1", "u9"),
                new MembershipPart("t1", "u1"),
                new MembershipPart("t1", "u1")),
            List.of(
                new GrantPart("u9", List.of("view")),
                new GrantPart("u1", List.of("fly")),
                new GrantPart("u1", List.of("view", "view"))));
    assertThat(problems(tenant, everything))
        .containsExactly(
            "tenant is not provisioning",
            "identity records already exist",
            "teams: bad key is not a valid key",
            "users: bad user is not a valid key",
            "teams: t1 appears more than once",
            "users: u1 appears more than once",
            "teams: name of t2: 1 to 100 characters",
            "users: display_name of u2: 1 to 100 characters",
            "users: no password for u3",
            "memberships: unknown team t9",
            "memberships: unknown user u9",
            "memberships: t1 u1 appears more than once",
            "permission_grants: unknown user u9",
            "permission_grants: unknown permission fly",
            "permission_grants: u1 view appears more than once");
    TenantId fine = provisioning();
    assertThat(
            identityImport.importPart(fine, minimal(), CLOCK.instant(), Map.of("x", Ids.newId())))
        .isEmpty();
    assertThat(rows("\"user\"", fine)).isEqualTo(2);
    assertThatThrownBy(
            () -> identityImport.importPart(provisioning(), null, CLOCK.instant(), Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                identityImport.importPart(
                    provisioning(),
                    new IdentityPart(null, List.of(), List.of(), List.of()),
                    CLOCK.instant(),
                    Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("DM-E05-R1 at most one active membership per team and user")
  void oneActiveMembership() {
    assertThat(
            problems(
                provisioning(),
                with(
                    null,
                    null,
                    List.of(new MembershipPart("t1", "u1"), new MembershipPart("t1", "u1")),
                    null)))
        .containsExactly("memberships: t1 u1 appears more than once");
    UUID team = identity.team(tenant, new Key("db-ops")).orElseThrow().id();
    UUID user = user(tenant, "dbops-lead").id();
    assertThatThrownBy(
            () ->
                appJdbc
                    .sql(
                        "INSERT INTO identity.team_membership (id, tenant_id, created_at, updated_at,"
                            + " team_id, user_id, active) VALUES (?, ?, now(), now(), ?, ?, true)")
                    .params(Ids.newId(), tenant.value(), team, user)
                    .update())
        .isInstanceOf(DuplicateKeyException.class);
  }

  @Test
  @DisplayName("DM-E06-R3 at most one active grant per user and permission")
  void oneActiveGrant() {
    assertThat(
            problems(
                provisioning(),
                with(null, null, null, List.of(new GrantPart("u1", List.of("view", "view"))))))
        .containsExactly("permission_grants: u1 view appears more than once");
    assertThatThrownBy(
            () ->
                appJdbc
                    .sql(
                        "INSERT INTO identity.permission_grant (id, tenant_id, created_at, updated_at,"
                            + " user_id, permission, active) VALUES (?, ?, now(), now(), ?, 'view', true)")
                    .params(Ids.newId(), tenant.value(), user(tenant, "admin").id())
                    .update())
        .isInstanceOf(DuplicateKeyException.class);
    assertThat(problems(tenant, identityPart))
        .containsExactly("tenant is not provisioning", "identity records already exist");
  }

  @Test
  @DisplayName("DM-C-10 usernames and team keys are unique per tenant only")
  void uniquePerTenant() {
    TenantId second = readyTenant();
    assertThat(rows("\"user\"", second)).isEqualTo(5);
    assertThat(user(second, "admin").id()).isNotEqualTo(user(tenant, "admin").id());
  }

  @Test
  @DisplayName("PM-M02-03 hasPermission follows DM-E06-R1 for all users; isMember")
  void permissionsAndMembership() {
    for (String username : displayNames().keySet()) {
      UUID id = user(tenant, username).id();
      for (Permission permission : Permission.values()) {
        assertThat(identity.hasPermission(tenant, id, permission))
            .as("%s %s", username, permission.key())
            .isEqualTo(grants().get(username).contains(permission.key()));
      }
    }
    assertThat(identity.isMember(tenant, user(tenant, "noc-l1").id(), new Key("noc-l1"))).isTrue();
    assertThat(identity.isMember(tenant, user(tenant, "dbops-lead").id(), new Key("db-ops")))
        .isTrue();
    assertThat(identity.isMember(tenant, user(tenant, "dbops-lead").id(), new Key("noc-l1")))
        .isFalse();
    UUID admin = user(tenant, "admin").id();
    for (TeamPart team : identityPart.teams()) {
      assertThat(identity.isMember(tenant, admin, new Key(team.teamKey()))).isFalse();
    }
  }

  @Test
  @DisplayName("PM-M02-04 teamMembers of db-ops by username; a team without members is empty")
  void teamMembers() {
    assertThat(identity.teamMembers(tenant, new Key("db-ops")))
        .extracting(view -> view.username().value())
        .containsExactly("dbops-engineer", "dbops-lead");
    assertThat(identity.teamMembers(tenant, new Key("customer-care"))).isEmpty();
    assertThat(identity.teamMembers(tenant, new Key("no-such-team"))).isEmpty();
  }

  @Test
  @DisplayName(
      "PM-M02-02 login with the right password; wrong password, unknown, invalid or inactive user give nothing")
  void login() {
    TenantId own = readyTenant();
    NewSession session =
        identity.login(own, "dbops-lead", passwords.get("dbops-lead")).orElseThrow();
    assertThat(session.user().username()).isEqualTo(new Key("dbops-lead"));
    int stored = rows("login_session", own);
    assertThat(identity.login(own, "dbops-lead", passwords.get("noc-l1"))).isEmpty();
    assertThat(identity.login(own, "nobody", passwords.get("noc-l1"))).isEmpty();
    assertThat(identity.login(own, "not a key", "x")).isEmpty();
    postgres
        .sql(
            "UPDATE identity.\"user\" SET active = false WHERE tenant_id = ? AND username = 'noc-l1'")
        .param(own.value())
        .update();
    assertThat(identity.login(own, "noc-l1", passwords.get("noc-l1"))).isEmpty();
    assertThat(rows("login_session", own)).isEqualTo(stored);
  }

  @Test
  @DisplayName("DM-E06-R2 login needs no permission")
  void loginNeedsNoPermission() {
    TenantId own = readyTenant();
    postgres
        .sql(
            "UPDATE identity.permission_grant SET active = false WHERE tenant_id = ? AND user_id ="
                + " (SELECT id FROM identity.\"user\" WHERE tenant_id = ? AND username = 'admin')")
        .params(own.value(), own.value())
        .update();
    NewSession session = identity.login(own, "admin", passwords.get("admin")).orElseThrow();
    assertThat(session.user().permissions()).isEmpty();
  }

  @Test
  @DisplayName("DM-E01-R1 logins are refused for a tenant that is not ready")
  void loginOnlyWhenReady() {
    TenantId notReady = provisioning();
    identityImport.importPart(notReady, identityPart, CLOCK.instant(), Map.of());
    assertThat(identity.login(notReady, "admin", passwords.get("admin"))).isEmpty();
    tenants.markFailed(notReady);
    assertThat(identity.login(notReady, "admin", passwords.get("admin"))).isEmpty();
    assertThat(rows("login_session", notReady)).isZero();
  }

  private static Map<String, Object> session(UUID id) {
    return postgres
        .sql("SELECT * FROM identity.login_session WHERE id = ?")
        .param(id)
        .query()
        .singleRow();
  }

  private static Instant at(Map<String, Object> row, String column) {
    return ((Timestamp) row.get(column)).toInstant();
  }

  @Test
  @DisplayName("DM-E-07 login session: token hash, state, expiry, activity")
  void sessionRecord() {
    Instant login = CLOCK.advance(Duration.ofDays(1));
    NewSession session =
        identity.login(tenant, "presenter", passwords.get("presenter")).orElseThrow();
    assertThat(session.token()).hasSize(43).matches("[A-Za-z0-9_-]{43}");
    byte[] bytes = Base64.getUrlDecoder().decode(session.token());
    assertThat(bytes).hasSize(32);
    Map<String, Object> row = session(session.sessionId());
    assertThat(row.get("token_hash")).isEqualTo(CanonicalJson.sha256Hex(bytes));
    assertThat(row.get("state")).isEqualTo("active");
    assertThat(at(row, "expires_at")).isEqualTo(login.plus(Duration.ofHours(12)));
    assertThat(session.expiresAt()).isEqualTo(login.plus(Duration.ofHours(12)));
    assertThat(at(row, "last_activity_at")).isEqualTo(login);
    assertThat(row.get("ended_at")).isNull();
    assertThat(row.get("end_reason")).isNull();
  }

  @Test
  @DisplayName(
      "AR-S-02 token of 32 random bytes, its SHA-256 stored; activity written at most once per 60 s")
  void sessionToken() {
    Instant login = CLOCK.advance(Duration.ofDays(1));
    NewSession session = identity.login(tenant, "noc-l1", passwords.get("noc-l1")).orElseThrow();
    NewSession other = identity.login(tenant, "noc-l1", passwords.get("noc-l1")).orElseThrow();
    assertThat(session.token()).isNotEqualTo(other.token());
    assertThat(session(session.sessionId()).toString()).doesNotContain(session.token());
    CLOCK.set(login.plusSeconds(59));
    SessionCheck check = identity.authenticate(tenant, session.token());
    assertThat(check.status()).isEqualTo(SessionCheck.Status.ACTIVE);
    assertThat(check.sessionId()).isEqualTo(session.sessionId());
    assertThat(check.user().username()).isEqualTo(new Key("noc-l1"));
    assertThat(at(session(session.sessionId()), "last_activity_at")).isEqualTo(login);
    CLOCK.set(login.plusSeconds(61));
    assertThat(identity.authenticate(tenant, session.token()).status())
        .isEqualTo(SessionCheck.Status.ACTIVE);
    assertThat(at(session(session.sessionId()), "last_activity_at"))
        .isEqualTo(login.plusSeconds(61));
    for (String garbage :
        List.of(
            "", "not base64!", "c2hvcnQ", Base64.getUrlEncoder().encodeToString(new byte[33]))) {
      assertThat(identity.authenticate(tenant, garbage).status())
          .isEqualTo(SessionCheck.Status.UNKNOWN);
    }
    assertThat(identity.authenticate(tenant, null).status()).isEqualTo(SessionCheck.Status.UNKNOWN);
    assertThat(
            identity
                .authenticate(
                    tenant, Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]))
                .status())
        .isEqualTo(SessionCheck.Status.UNKNOWN);
  }

  @Test
  @DisplayName("AR-TM-03 lifetime 12 h and idle limit 2 h")
  void lifetimeAndIdle() {
    Instant login = CLOCK.advance(Duration.ofDays(1));
    NewSession first =
        identity.login(tenant, "dbops-engineer", passwords.get("dbops-engineer")).orElseThrow();
    NewSession second =
        identity.login(tenant, "dbops-engineer", passwords.get("dbops-engineer")).orElseThrow();
    CLOCK.set(login.plus(Duration.ofHours(2)));
    assertThat(identity.authenticate(tenant, first.token()).status())
        .isEqualTo(SessionCheck.Status.ACTIVE);
    CLOCK.set(login.plus(Duration.ofHours(2)).plusMillis(1));
    assertThat(identity.authenticate(tenant, second.token()).status())
        .isEqualTo(SessionCheck.Status.ENDED);
    Map<String, Object> ended = session(second.sessionId());
    assertThat(ended.get("state")).isEqualTo("ended");
    assertThat(ended.get("end_reason")).isEqualTo("expired");
    assertThat(at(ended, "ended_at")).isEqualTo(login.plus(Duration.ofHours(2)).plusMillis(1));

    Instant start = CLOCK.advance(Duration.ofDays(1));
    NewSession used =
        identity.login(tenant, "dbops-lead", passwords.get("dbops-lead")).orElseThrow();
    for (int hour = 1; hour < 12; hour++) {
      CLOCK.set(start.plus(Duration.ofHours(hour)));
      assertThat(identity.authenticate(tenant, used.token()).status())
          .as("hour %d", hour)
          .isEqualTo(SessionCheck.Status.ACTIVE);
    }
    CLOCK.set(start.plus(Duration.ofHours(12)));
    assertThat(identity.authenticate(tenant, used.token()).status())
        .isEqualTo(SessionCheck.Status.ENDED);
    assertThat(session(used.sessionId()).get("end_reason")).isEqualTo("expired");
  }

  @Test
  @DisplayName("DM-SM-02 active to ended by logout or expiry; an ended session stays ended")
  void sessionStateMachine() {
    CLOCK.advance(Duration.ofDays(1));
    NewSession session = identity.login(tenant, "admin", passwords.get("admin")).orElseThrow();
    assertThat(session(session.sessionId()).get("state")).isEqualTo("active");
    Instant out = CLOCK.advance(Duration.ofMinutes(5));
    identity.logout(tenant, session.sessionId());
    Map<String, Object> row = session(session.sessionId());
    assertThat(row.get("state")).isEqualTo("ended");
    assertThat(row.get("end_reason")).isEqualTo("logout");
    assertThat(at(row, "ended_at")).isEqualTo(out);
    assertThat(identity.authenticate(tenant, session.token()).status())
        .isEqualTo(SessionCheck.Status.ENDED);
    CLOCK.advance(Duration.ofMinutes(5));
    identity.logout(tenant, session.sessionId());
    identity.logout(tenant, Ids.newId());
    assertThat(at(session(session.sessionId()), "ended_at")).isEqualTo(out);
  }

  @Test
  @DisplayName("SL-B-01 tenant B sees none of tenant A's sessions, users, teams and grants")
  void tenantSeparation() {
    TenantId other = readyTenant();
    CLOCK.advance(Duration.ofDays(1));
    NewSession session = identity.login(tenant, "admin", passwords.get("admin")).orElseThrow();
    assertThat(identity.authenticate(other, session.token()).status())
        .isEqualTo(SessionCheck.Status.UNKNOWN);
    UUID adminOfA = user(tenant, "admin").id();
    assertThat(identity.user(other, adminOfA)).isEmpty();
    assertThat(identity.userByUsername(other, new Key("admin")).orElseThrow().id())
        .isNotEqualTo(adminOfA);
    assertThat(identity.team(other, new Key("db-ops")).orElseThrow().id())
        .isNotEqualTo(identity.team(tenant, new Key("db-ops")).orElseThrow().id());
    assertThat(identity.hasPermission(other, adminOfA, Permission.VIEW)).isFalse();
    assertThat(identity.teamMembers(other, new Key("db-ops")))
        .extracting(UserView::id)
        .doesNotContainAnyElementsOf(
            Set.of(user(tenant, "dbops-lead").id(), user(tenant, "dbops-engineer").id()));
    TenantId unknown = new TenantId(Ids.newId());
    assertThat(identity.userByUsername(unknown, new Key("admin"))).isEmpty();
    assertThat(identity.teamMembers(unknown, new Key("db-ops"))).isEmpty();
  }

  @Test
  @DisplayName(
      "SL-B-13 user and userByUsername name the user with active permissions and team keys")
  void namedUsers() {
    UserView byName = user(tenant, "dbops-engineer");
    assertThat(identity.user(tenant, byName.id())).contains(byName);
    assertThat(byName.username()).isEqualTo(new Key("dbops-engineer"));
    assertThat(byName.displayName()).isEqualTo("Usman Tariq (DB Ops)");
    assertThat(byName.permissions())
        .containsExactlyInAnyOrder(Permission.ACKNOWLEDGE, Permission.APPROVE_ACTION);
    assertThat(byName.teamKeys()).containsExactly(new Key("db-ops"));
    Map<String, UserView> all = new HashMap<>();
    displayNames().keySet().forEach(u -> all.put(u, user(tenant, u)));
    assertThat(all.values()).extracting(UserView::id).doesNotHaveDuplicates();
  }
}
