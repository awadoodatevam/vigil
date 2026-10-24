package vigil.correlation.api;

import java.util.Arrays;
import java.util.Optional;

/** The last rule needed to decide a hypothesis (DM-E-30 {@code tie_break}, complete list). */
public enum TieBreak {
  NONE("none"),
  CRITICALITY("criticality"),
  COMPONENT_KEY("component_key");

  private final String key;

  TieBreak(String key) {
    this.key = key;
  }

  public String key() {
    return key;
  }

  /** The value with exactly this key; empty for {@code null} or an unknown key. */
  public static Optional<TieBreak> fromKey(String key) {
    return Arrays.stream(values()).filter(value -> value.key.equals(key)).findFirst();
  }
}
