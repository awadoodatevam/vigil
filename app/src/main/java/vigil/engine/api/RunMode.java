package vigil.engine.api;

import java.util.Arrays;
import java.util.Optional;

/** The mode of a workflow run (DM-E-50 {@code mode}, complete list). */
public enum RunMode {
  LIVE("live"),
  TEST("test");

  private final String key;

  RunMode(String key) {
    this.key = key;
  }

  public String key() {
    return key;
  }

  /** The value with exactly this key; empty for {@code null} or an unknown key. */
  public static Optional<RunMode> fromKey(String key) {
    return Arrays.stream(values()).filter(value -> value.key.equals(key)).findFirst();
  }
}
