package vigil.api;

import java.util.Comparator;
import java.util.List;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;
import vigil.identity.api.Permission;
import vigil.identity.api.UserView;
import vigil.shared.Key;

/**
 * The user of AR-I-12 and AR-I-13: permissions in the order of DM-E06-R1, team keys in Key order.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
record User(String username, String displayName, List<String> permissions, List<String> teamKeys) {

  static User of(UserView view) {
    return new User(
        view.username().value(),
        view.displayName(),
        view.permissions().stream()
            .sorted(Comparator.comparing(Permission::ordinal))
            .map(Permission::key)
            .toList(),
        view.teamKeys().stream().sorted().map(Key::value).toList());
  }
}
