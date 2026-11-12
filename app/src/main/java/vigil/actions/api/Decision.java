package vigil.actions.api;

import java.util.Arrays;
import java.util.Optional;

/** The decisions of an approver (DM-E-59, complete list). */
public enum Decision {
  APPROVE("approve"),
  REJECT("reject");

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
