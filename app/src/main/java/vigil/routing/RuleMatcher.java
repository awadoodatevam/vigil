package vigil.routing;

import java.util.List;
import java.util.Optional;
import vigil.routing.api.RoutingCondition;
import vigil.routing.api.RoutingRule;

/** The matching of DM-E43-R1 with the operators of DM-R-26; rules and conditions are complete. */
final class RuleMatcher {

  private RuleMatcher() {}

  /** The first rule in list order whose conditions are all true. */
  static Optional<RoutingRule> first(List<RoutingRule> rules, RoutingInputs inputs) {
    return rules.stream().filter(rule -> matches(rule, inputs)).findFirst();
  }

  /** True when every condition is true; a rule without conditions is true. */
  static boolean matches(RoutingRule rule, RoutingInputs inputs) {
    return rule.conditions().stream().allMatch(condition -> matches(condition, inputs));
  }

  /** The comparison; a field with no value is false for every operator. */
  static boolean matches(RoutingCondition condition, RoutingInputs inputs) {
    Object value = inputs.value(condition.field());
    List<String> values = condition.values();
    String first = values.getFirst();
    return switch (value) {
      case null -> false;
      case List<?> set ->
          switch (condition.operator()) {
            case CONTAINS -> set.contains(first);
            case NOT_CONTAINS -> !set.contains(first);
            default -> false;
          };
      case Long number -> {
        int compared = Long.compare(number, Long.parseLong(first));
        yield switch (condition.operator()) {
          case EQUALS -> compared == 0;
          case NOT_EQUALS -> compared != 0;
          case GREATER_THAN -> compared > 0;
          case GREATER_OR_EQUAL -> compared >= 0;
          case LESS_THAN -> compared < 0;
          case LESS_OR_EQUAL -> compared <= 0;
          default -> false;
        };
      }
      case String text -> {
        // Ordered values are listed from highest, so a lower index is higher.
        List<String> order = condition.field().allowedValues();
        yield switch (condition.operator()) {
          case EQUALS -> text.equals(first);
          case NOT_EQUALS -> !text.equals(first);
          case IN -> values.contains(text);
          case NOT_IN -> !values.contains(text);
          case AT_LEAST -> order.indexOf(text) <= order.indexOf(first);
          case AT_MOST -> order.indexOf(text) >= order.indexOf(first);
          default -> false;
        };
      }
      default -> false;
    };
  }
}
