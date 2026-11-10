package vigil.investigation.api;

import java.util.Arrays;
import java.util.Optional;

/** The source of an incident summary (DM-E-69). */
public enum SummarySource {
  AI("ai"),
  TEMPLATE("template");

  private final String key;

  SummarySource(String key) {
    this.key = key;
  }

  public String key() {
    return key;
  }

  /** The value with exactly this key; empty for {@code null} or an unknown key. */
  public static Optional<SummarySource> fromKey(String key) {
    return Arrays.stream(values()).filter(value -> value.key.equals(key)).findFirst();
  }
}
