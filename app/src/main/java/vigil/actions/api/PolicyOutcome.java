package vigil.actions.api;

import java.util.Arrays;
import java.util.Optional;

/** The outcomes of an action policy (DM-E-56, complete list), most restrictive first. */
public enum PolicyOutcome {
  BLOCKED("blocked"),
  APPROVAL_REQUIRED("approval_required"),
  PREAUTHORIZED("preauthorized");

  private final String key;

  PolicyOutcome(String key) {
    this.key = key;
  }

  public String key() {
    return key;
  }

  /** The value with exactly this key; empty for {@code null} or an unknown key. */
  public static Optional<PolicyOutcome> fromKey(String key) {
    return Arrays.stream(values()).filter(value -> value.key.equals(key)).findFirst();
  }
}
