package vigil.investigation.api;

import java.util.Arrays;
import java.util.Optional;

/** The validation of an AI investigation result (DM-E-68, PM-M17-03). */
public enum Validation {
  ACCEPTED("accepted"),
  REJECTED("rejected");

  private final String key;

  Validation(String key) {
    this.key = key;
  }

  public String key() {
    return key;
  }

  /** The value with exactly this key; empty for {@code null} or an unknown key. */
  public static Optional<Validation> fromKey(String key) {
    return Arrays.stream(values()).filter(value -> value.key.equals(key)).findFirst();
  }
}
