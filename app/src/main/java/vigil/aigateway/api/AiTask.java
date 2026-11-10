package vigil.aigateway.api;

import java.util.Arrays;
import java.util.Optional;

/** The AI tasks of {@code 01-scope-lock} Section 5.5 (complete list). */
public enum AiTask {
  SL_T_1("SL-T-1"),
  SL_T_2("SL-T-2"),
  SL_T_3("SL-T-3");

  private final String key;

  AiTask(String key) {
    this.key = key;
  }

  public String key() {
    return key;
  }

  /** The task with exactly this key; empty for {@code null} or an unknown key. */
  public static Optional<AiTask> fromKey(String key) {
    return Arrays.stream(values()).filter(task -> task.key.equals(key)).findFirst();
  }
}
