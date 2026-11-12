package vigil.actions.api;

import java.util.Arrays;
import java.util.Optional;

/** The states of an action execution (DM-E-60, DM-SM-12, complete list). */
public enum ExecutionState {
  RECORDED("recorded"),
  RECHECKING("rechecking"),
  NOT_EXECUTED("not_executed"),
  EXECUTING("executing"),
  RECONCILING("reconciling"),
  SUCCEEDED("succeeded"),
  FAILED("failed"),
  UNRESOLVED("unresolved");

  private final String key;

  ExecutionState(String key) {
    this.key = key;
  }

  public String key() {
    return key;
  }

  /** The value with exactly this key; empty for {@code null} or an unknown key. */
  public static Optional<ExecutionState> fromKey(String key) {
    return Arrays.stream(values()).filter(value -> value.key.equals(key)).findFirst();
  }
}
