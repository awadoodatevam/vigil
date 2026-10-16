package vigil.testsupport;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import vigil.identity.api.IdentityImport;
import vigil.identity.api.IdentityPart;
import vigil.identity.api.IdentityPart.GrantPart;
import vigil.identity.api.IdentityPart.MembershipPart;
import vigil.identity.api.IdentityPart.TeamPart;
import vigil.identity.api.IdentityPart.UserPart;
import vigil.identity.api.Permission;
import vigil.shared.Actor;
import vigil.shared.TenantId;
import vigil.tenancy.api.TenantImport;
import vigil.tenancy.api.TenantLogo;
import vigil.tenancy.api.TenantService;
import vigil.tenancy.api.TenantSettingsPart;

/**
 * Creates {@code ready} tenants of a {@link VigilTestApp} through {@code TenantService} and the
 * import interfaces, with settings, users (each with a 20-character password generated at test
 * start, BP-R-14), teams and memberships, and points a host name's alias to each.
 */
public final class TestTenants {

  private static final String ALPHABET =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
  private static final SecureRandom RANDOM = new SecureRandom();

  private final TenantService tenants;
  private final TenantImport tenantImport;
  private final IdentityImport identityImport;

  public TestTenants(VigilTestApp app) {
    this.tenants = app.context().getBean(TenantService.class);
    this.tenantImport = app.context().getBean(TenantImport.class);
    this.identityImport = app.context().getBean(IdentityImport.class);
  }

  /** A new tenant to be built, named {@code name}. */
  public Builder tenant(String name) {
    return new Builder(name);
  }

  /** A password of 20 characters from a secure generator. */
  public static String randomPassword() {
    StringBuilder password = new StringBuilder();
    for (int i = 0; i < 20; i++) {
      password.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
    }
    return password.toString();
  }

  /** A created tenant: its id and the generated password of each user. */
  public record Tenant(TenantId id, Map<String, String> passwords) {

    public String password(String username) {
      String password = passwords.get(username);
      if (password == null) {
        throw new IllegalArgumentException("no user " + username);
      }
      return password;
    }
  }

  /** The content of one tenant. */
  public final class Builder {

    private final String name;
    private final List<String> hosts = new ArrayList<>();
    private String customerName = "Customer";
    private String productDisplayName = "Product";
    private String environmentLabel;
    private TenantLogo logo;
    private final List<TeamPart> teams = new ArrayList<>();
    private final List<UserPart> users = new ArrayList<>();
    private final List<MembershipPart> memberships = new ArrayList<>();
    private final List<GrantPart> grants = new ArrayList<>();
    private final Map<String, String> passwords = new LinkedHashMap<>();

    private Builder(String name) {
      this.name = name;
    }

    /** A host name whose alias points to the tenant. */
    public Builder host(String hostName) {
      hosts.add(hostName);
      return this;
    }

    public Builder settings(
        String customerName, String productDisplayName, String environmentLabel) {
      this.customerName = customerName;
      this.productDisplayName = productDisplayName;
      this.environmentLabel = environmentLabel;
      return this;
    }

    public Builder logo(byte[] bytes, String mediaType) {
      this.logo = new TenantLogo(bytes, mediaType);
      return this;
    }

    public Builder team(String teamKey, String teamName) {
      teams.add(new TeamPart(teamKey, teamName));
      return this;
    }

    /** A user with a generated password and the given permissions. */
    public Builder user(String username, String displayName, Permission... permissions) {
      passwords.put(username, randomPassword());
      users.add(new UserPart(username, displayName, passwords.get(username)));
      if (permissions.length > 0) {
        grants.add(
            new GrantPart(username, Arrays.stream(permissions).map(Permission::key).toList()));
      }
      return this;
    }

    public Builder member(String teamKey, String username) {
      memberships.add(new MembershipPart(teamKey, username));
      return this;
    }

    /** Creates the tenant, imports both parts, marks it ready and sets the aliases. */
    public Tenant create() {
      TenantId id = tenants.createTenant(name, Actor.module("vigil-setup"));
      Instant now = Instant.now();
      tenantImport.importPart(
          id,
          new TenantSettingsPart(customerName, environmentLabel, productDisplayName, logo),
          now,
          Map.of());
      identityImport.importPart(
          id, new IdentityPart(teams, users, memberships, grants), now, Map.of());
      tenants.markReady(id);
      for (String host : hosts) {
        tenants.setAlias(host, id, "installation token");
      }
      return new Tenant(id, Map.copyOf(passwords));
    }
  }
}
