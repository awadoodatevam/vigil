package vigil.workflows.api;

import java.util.Arrays;
import java.util.Optional;

/** How a workflow version was made (DM-E-47 {@code origin}, complete list). */
public enum VersionOrigin {
  SEED("seed"),
  DRAFT("draft"),
  ROLLBACK("rollback");

  private final String key;

  VersionOrigin(String key) {
    this.key = key;
  }

  public String key() {
    return key;
  }

  /** The value with exactly this key; empty for {@code null} or an unknown key. */
  public static Optional<VersionOrigin> fromKey(String key) {
    return Arrays.stream(values()).filter(value -> value.key.equals(key)).findFirst();
  }
}
