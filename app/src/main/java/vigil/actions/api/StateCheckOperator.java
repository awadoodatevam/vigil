package vigil.actions.api;

import java.util.Arrays;
import java.util.Optional;

/** The operators of a state check (DM-V-08, complete list). */
public enum StateCheckOperator {
  EQUALS("equals"),
  NOT_EQUALS("not_equals"),
  GREATER_THAN("greater_than"),
  GREATER_OR_EQUAL("greater_or_equal"),
  LESS_THAN("less_than"),
  LESS_OR_EQUAL("less_or_equal");

  private final String key;

  StateCheckOperator(String key) {
    this.key = key;
  }

  public String key() {
    return key;
  }

  /** The value with exactly this key; empty for {@code null} or an unknown key. */
  public static Optional<StateCheckOperator> fromKey(String key) {
    return Arrays.stream(values()).filter(value -> value.key.equals(key)).findFirst();
  }
}
