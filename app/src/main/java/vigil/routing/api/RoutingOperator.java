package vigil.routing.api;

import java.util.Arrays;
import java.util.Optional;

/** The operators of DM-R-26 that the routing value types use (complete list). */
public enum RoutingOperator {
  EQUALS("equals"),
  NOT_EQUALS("not_equals"),
  IN("in"),
  NOT_IN("not_in"),
  AT_LEAST("at_least"),
  AT_MOST("at_most"),
  CONTAINS("contains"),
  NOT_CONTAINS("not_contains"),
  GREATER_THAN("greater_than"),
  GREATER_OR_EQUAL("greater_or_equal"),
  LESS_THAN("less_than"),
  LESS_OR_EQUAL("less_or_equal");

  private final String key;

  RoutingOperator(String key) {
    this.key = key;
  }

  public String key() {
    return key;
  }

  /** The value with exactly this key; empty for {@code null} or an unknown key. */
  public static Optional<RoutingOperator> fromKey(String key) {
    return Arrays.stream(values()).filter(value -> value.key.equals(key)).findFirst();
  }
}
