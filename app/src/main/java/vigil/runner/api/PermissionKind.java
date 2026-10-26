package vigil.runner.api;

import java.util.Arrays;
import java.util.Optional;

/** The permission kinds of DM-E-12 and DM-E-13 (complete list). */
public enum PermissionKind {
  DIAGNOSTIC("diagnostic"),
  ACTION("action");

  private final String key;

  PermissionKind(String key) {
    this.key = key;
  }

  public String key() {
    return key;
  }

  /** The value with exactly this key; empty for {@code null} or an unknown key. */
  public static Optional<PermissionKind> fromKey(String key) {
    return Arrays.stream(values()).filter(value -> value.key.equals(key)).findFirst();
  }
}
