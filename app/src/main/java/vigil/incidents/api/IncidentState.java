package vigil.incidents.api;

import java.util.Arrays;
import java.util.Optional;

/** The states of an incident (DM-E-32, DM-SM-05, complete list). */
public enum IncidentState {
  FORMING("forming"),
  OPEN("open"),
  RESOLVED("resolved"),
  CLOSED("closed"),
  MERGED("merged");

  private final String key;

  IncidentState(String key) {
    this.key = key;
  }

  public String key() {
    return key;
  }

  /** The value with exactly this key; empty for {@code null} or an unknown key. */
  public static Optional<IncidentState> fromKey(String key) {
    return Arrays.stream(values()).filter(value -> value.key.equals(key)).findFirst();
  }
}
