package vigil.runner.api;

import java.util.Arrays;
import java.util.Optional;

/** The states of an operation run (DM-SM-10, complete list). */
public enum OperationRunState {
  RUNNING("running"),
  SUCCEEDED("succeeded"),
  FAILED("failed"),
  UNKNOWN("unknown"),
  REFUSED("refused");

  private final String key;

  OperationRunState(String key) {
    this.key = key;
  }

  public String key() {
    return key;
  }

  /** The value with exactly this key; empty for {@code null} or an unknown key. */
  public static Optional<OperationRunState> fromKey(String key) {
    return Arrays.stream(values()).filter(value -> value.key.equals(key)).findFirst();
  }
}
