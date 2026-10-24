package vigil.correlation.api;

import java.util.Arrays;
import java.util.Optional;

/** The decision of a grouping decision (DM-E-29 {@code decision}, complete list). */
public enum Decision {
  CREATED_INCIDENT("created_incident"),
  JOINED("joined"),
  REOPENED("reopened");

  private final String key;

  Decision(String key) {
    this.key = key;
  }

  public String key() {
    return key;
  }

  /** The value with exactly this key; empty for {@code null} or an unknown key. */
  public static Optional<Decision> fromKey(String key) {
    return Arrays.stream(values()).filter(value -> value.key.equals(key)).findFirst();
  }
}
