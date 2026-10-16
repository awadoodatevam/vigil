package vigil.identity.api;

import java.util.List;

/**
 * The content of seed part 2 (DM-R-56 (2)). Keys and permissions arrive as text, so the import
 * reports every bad value as a problem.
 */
public record IdentityPart(
    List<TeamPart> teams,
    List<UserPart> users,
    List<MembershipPart> memberships,
    List<GrantPart> grants) {

  /** A team of the part. */
  public record TeamPart(String teamKey, String name) {}

  /** A user of the part with the password from its deployment secret (AR-S-06). */
  public record UserPart(String username, String displayName, String password) {}

  /** A membership of the part. */
  public record MembershipPart(String teamKey, String username) {}

  /** The permissions granted to one user of the part. */
  public record GrantPart(String username, List<String> permissions) {}
}
