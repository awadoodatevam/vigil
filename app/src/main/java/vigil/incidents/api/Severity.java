package vigil.incidents.api;

import java.util.Arrays;
import java.util.Optional;

/** The severities of an incident (DM-E-32, complete list). */
public enum Severity {
  P1("P1"),
  P2("P2"),
  P3("P3"),
  P4("P4");

  private final String key;

  Severity(String key) {
    this.key = key;
  }

  public String key() {
    return key;
  }

  /** The value with exactly this key; empty for {@code null} or an unknown key. */
  public static Optional<Severity> fromKey(String key) {
    return Arrays.stream(values()).filter(value -> value.key.equals(key)).findFirst();
  }
}
