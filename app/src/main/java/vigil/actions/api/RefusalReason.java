package vigil.actions.api;

import java.util.Arrays;
import java.util.Optional;

/** Why an approval request was refused (DM-E-58, DM-R-30, complete list). */
public enum RefusalReason {
  ATTEMPT_LIMIT_REACHED("attempt_limit_reached"),
  UNRESOLVED_EXECUTION("unresolved_execution");

  private final String key;

  RefusalReason(String key) {
    this.key = key;
  }

  public String key() {
    return key;
  }

  /** The value with exactly this key; empty for {@code null} or an unknown key. */
  public static Optional<RefusalReason> fromKey(String key) {
    return Arrays.stream(values()).filter(value -> value.key.equals(key)).findFirst();
  }
}
