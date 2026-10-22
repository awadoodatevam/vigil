package vigil.routing.api;

import java.util.Arrays;
import java.util.Optional;

/** How a routing rule chooses the owner team (DM-E-41, complete list). */
public enum OwnerMode {
  FIXED_TEAM("fixed_team"),
  HYPOTHESIS_COMPONENT_OWNER("hypothesis_component_owner");

  private final String key;

  OwnerMode(String key) {
    this.key = key;
  }

  public String key() {
    return key;
  }

  /** The value with exactly this key; empty for {@code null} or an unknown key. */
  public static Optional<OwnerMode> fromKey(String key) {
    return Arrays.stream(values()).filter(value -> value.key.equals(key)).findFirst();
  }
}
