package vigil.engine.api;

import java.util.Arrays;
import java.util.Optional;

/** The states of a step execution (DM-E-51, DM-SM-08, complete list). */
public enum StepExecutionState {
  RUNNING("running"),
  WAITING("waiting"),
  COMPLETED("completed"),
  CANCELLED("cancelled");

  private final String key;

  StepExecutionState(String key) {
    this.key = key;
  }

  public String key() {
    return key;
  }

  /** The value with exactly this key; empty for {@code null} or an unknown key. */
  public static Optional<StepExecutionState> fromKey(String key) {
    return Arrays.stream(values()).filter(value -> value.key.equals(key)).findFirst();
  }
}
