package vigil.setup;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import vigil.connectors.api.Secrets;
import vigil.identity.api.IdentityImport;
import vigil.identity.api.IdentityPart;
import vigil.identity.api.IdentityPart.GrantPart;
import vigil.identity.api.IdentityPart.MembershipPart;
import vigil.identity.api.IdentityPart.TeamPart;
import vigil.identity.api.IdentityPart.UserPart;
import vigil.setup.SeedShapes.Row;
import vigil.shared.TenantId;
import vigil.shared.VigilError;

/**
 * Seed part 2, identity, imported through {@link IdentityImport} (DM-R-56 (2)); each user's
 * password is its secret {@code seed-password-{username}} (AR-S-06).
 */
@Component
class IdentityAdapter implements SeedPartImporter {

  private final IdentityImport identityImport;
  private final Secrets secrets;

  IdentityAdapter(IdentityImport identityImport, Secrets secrets) {
    this.identityImport = identityImport;
    this.secrets = secrets;
  }

  @Override
  public int part() {
    return 2;
  }

  @Override
  public PartResult importPart(
      TenantId tenantId,
      SeedBundle bundle,
      Map<String, JsonNode> files,
      Instant importStartedAt,
      Map<String, UUID> handles) {
    JsonNode file = files.get(SeedParts.TENANT_IDENTITY);
    SeedShapes shapes = new SeedShapes();
    List<Row> teams = shapes.list(file, "teams", List.of("team_key", "name"), Set.of());
    List<Row> users = shapes.list(file, "users", List.of("username", "display_name"), Set.of());
    List<Row> memberships =
        shapes.list(file, "memberships", List.of("team_key", "username"), Set.of());
    Map<String, List<String>> grants = shapes.lists(file, "permission_grants");
    shapes.refuseIfAny(SeedParts.part(part()).name());
    List<UserPart> userParts = new ArrayList<>();
    for (Row user : users) {
      userParts.add(new UserPart(user.get("username"), user.get("display_name"), password(user)));
    }
    List<GrantPart> grantParts = new ArrayList<>();
    grants.forEach((username, permissions) -> grantParts.add(new GrantPart(username, permissions)));
    identityImport.importPart(
        tenantId,
        new IdentityPart(
            teams.stream().map(t -> new TeamPart(t.get("team_key"), t.get("name"))).toList(),
            userParts,
            memberships.stream()
                .map(m -> new MembershipPart(m.get("team_key"), m.get("username")))
                .toList(),
            grantParts),
        importStartedAt,
        handles);
    Map<String, Integer> counts = new LinkedHashMap<>();
    counts.put("teams", teams.size());
    counts.put("users", users.size());
    counts.put("memberships", memberships.size());
    counts.put("permission_grants", grants.size());
    return new PartResult(counts, Map.of());
  }

  /** The user's seed password, or {@code null} when it has none. */
  private String password(Row user) {
    String username = user.get("username");
    if (username == null) {
      return null;
    }
    try {
      return secrets.readSeedPassword(username);
    } catch (IllegalArgumentException e) {
      return null;
    } catch (VigilError e) {
      if (e.code() == VigilError.Code.DEPENDENCY_FAILED) {
        return null;
      }
      throw e;
    }
  }
}
