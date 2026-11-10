package vigil.investigation.api;

import java.util.Arrays;
import java.util.Optional;

/** The origin of an AI investigation result (DM-E-68). */
public enum Origin {
  WORKFLOW_STEP("workflow_step"),
  RERUN("rerun");

  private final String key;

  Origin(String key) {
    this.key = key;
  }

  public String key() {
    return key;
  }

  /** The value with exactly this key; empty for {@code null} or an unknown key. */
  public static Optional<Origin> fromKey(String key) {
    return Arrays.stream(values()).filter(value -> value.key.equals(key)).findFirst();
  }
}
