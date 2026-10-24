package vigil.engine.api;

import java.util.Arrays;
import java.util.Optional;

/** The states of a workflow run (DM-E-50, DM-SM-07, complete list). */
public enum RunState {
  RUNNING("running"),
  WAITING("waiting"),
  FINISHED("finished"),
  CANCELLED("cancelled"),
  FAILED("failed");

  private final String key;

  RunState(String key) {
    this.key = key;
  }

  public String key() {
    return key;
  }

  /** The value with exactly this key; empty for {@code null} or an unknown key. */
  public static Optional<RunState> fromKey(String key) {
    return Arrays.stream(values()).filter(value -> value.key.equals(key)).findFirst();
  }
}
