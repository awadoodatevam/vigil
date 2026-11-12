package vigil.api;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import vigil.identity.api.IdentityService;
import vigil.identity.api.TeamView;
import vigil.identity.api.UserView;
import vigil.servicemap.api.ComponentView;
import vigil.servicemap.api.ServiceMapService;
import vigil.shared.Actor;
import vigil.shared.Key;
import vigil.shared.TenantId;

/**
 * The names of one answer (DC-U-06): each team, user and component is asked for once. Team names
 * come from vigil-identity (the key when the team does not exist), user display names from
 * vigil-identity (the id as text when the user does not exist), component names from the current
 * service map (the key when the component is not in it).
 */
final class Names {

  /** Code point order of the names, ties broken by the key. */
  static final Comparator<TeamName> TEAM_ORDER =
      Comparator.comparing(TeamName::name).thenComparing(TeamName::teamKey);

  private final IdentityService identity;
  private final ServiceMapService serviceMap;
  private final TenantId tenantId;
  private final Map<String, TeamName> teams = new HashMap<>();
  private final Map<UUID, UserName> users = new HashMap<>();
  private final Map<String, String> components = new HashMap<>();

  Names(IdentityService identity, ServiceMapService serviceMap, TenantId tenantId) {
    this.identity = identity;
    this.serviceMap = serviceMap;
    this.tenantId = tenantId;
  }

  TeamName team(String teamKey) {
    return teams.computeIfAbsent(
        teamKey,
        key ->
            new TeamName(
                key, identity.team(tenantId, new Key(key)).map(TeamView::name).orElse(key)));
  }

  /** The distinct teams in code point order of their names, ties broken by the key. */
  List<TeamName> teams(Collection<String> teamKeys) {
    return new LinkedHashSet<>(teamKeys).stream().map(this::team).sorted(TEAM_ORDER).toList();
  }

  UserName user(UUID userId) {
    return users.computeIfAbsent(
        userId,
        id ->
            new UserName(
                id.toString(),
                identity.user(tenantId, id).map(UserView::displayName).orElse(id.toString())));
  }

  ActorName actor(Actor actor) {
    if (actor.kind() == Actor.Kind.MODULE) {
      return new ActorName("module", null, null);
    }
    UserName user = user(actor.userId());
    return new ActorName("user", user.userId(), user.displayName());
  }

  String component(String componentKey) {
    return components.computeIfAbsent(
        componentKey,
        key -> serviceMap.component(tenantId, key).map(ComponentView::name).orElse(key));
  }
}
