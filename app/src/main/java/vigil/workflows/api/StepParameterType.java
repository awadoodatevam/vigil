package vigil.workflows.api;

import java.util.Arrays;
import java.util.Optional;

/** The value type of a step parameter (DM Section 9, complete list). */
public enum StepParameterType {
  ENUM("enum"),
  ENUM_SET("enum_set"),
  KEY_LIST("key_list"),
  KEY_SET("key_set"),
  DURATION("duration"),
  CONDITION_BRANCHES("condition_branches");

  private final String key;

  StepParameterType(String key) {
    this.key = key;
  }

  public String key() {
    return key;
  }

  /** The value with exactly this key; empty for {@code null} or an unknown key. */
  public static Optional<StepParameterType> fromKey(String key) {
    return Arrays.stream(values()).filter(value -> value.key.equals(key)).findFirst();
  }
}
