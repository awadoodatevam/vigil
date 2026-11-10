package vigil.knowledge.api;

import java.util.Arrays;
import java.util.Optional;

/** The outcome of a similarity search (DM-E-66). */
public enum SearchOutcome {
  MATCH("match"),
  NO_MATCH("no_match");

  private final String key;

  SearchOutcome(String key) {
    this.key = key;
  }

  public String key() {
    return key;
  }

  /** The value with exactly this key; empty for {@code null} or an unknown key. */
  public static Optional<SearchOutcome> fromKey(String key) {
    return Arrays.stream(values()).filter(value -> value.key.equals(key)).findFirst();
  }
}
