package vigil.correlation.api;

import java.util.Arrays;
import java.util.Optional;

/**
 * The relation of a grouping decision to its related alert (DM-E-29 {@code relation}, complete
 * list); {@code DEPENDS_ON}: the alert's component depends on the related alert's.
 */
public enum GroupingRelation {
  SAME_COMPONENT("same_component"),
  DEPENDS_ON("depends_on"),
  DEPENDED_ON_BY("depended_on_by"),
  SAME_UNKNOWN_IDENTITY("same_unknown_identity");

  private final String key;

  GroupingRelation(String key) {
    this.key = key;
  }

  public String key() {
    return key;
  }

  /** The value with exactly this key; empty for {@code null} or an unknown key. */
  public static Optional<GroupingRelation> fromKey(String key) {
    return Arrays.stream(values()).filter(value -> value.key.equals(key)).findFirst();
  }
}
