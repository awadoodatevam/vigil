package vigil.routing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import vigil.routing.api.RoutingCondition;
import vigil.routing.api.RoutingField;
import vigil.routing.api.RoutingRule;
import vigil.routing.api.ValueType;

/**
 * The validation of a routing rule set (DM-E41-R1) with the texts of DC-TX-11 and BP-A-28, and the
 * place of each problem on the routing rules screen (DC-S08-04).
 */
final class RuleSetValidation {

  static final String LAST_RULE = "The last rule must be the only rule without conditions.";

  private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
  private static final Pattern WHOLE = Pattern.compile("^[0-9]{1,9}$");

  private RuleSetValidation() {}

  /**
   * One problem: its text and where the screen shows it ({@code rule} and {@code field} {@code
   * null} for the list text, {@code condition} only for an incomplete or invalid condition).
   */
  record Problem(String text, Integer rule, String field, Integer condition) {

    Map<String, Object> place() {
      Map<String, Object> place = new LinkedHashMap<>();
      place.put("rule", rule);
      place.put("field", field);
      place.put("condition", condition);
      return place;
    }
  }

  /** The problems in the order of P-15 spec Section 6.6; {@code activeTeam} tests a team key. */
  static List<Problem> problems(List<RoutingRule> rules, Predicate<String> activeTeam) {
    List<Problem> problems = new ArrayList<>();
    for (int i = 0; i < rules.size(); i++) {
      RoutingRule rule = rules.get(i);
      String prefix =
          "Rule '" + (blank(rule.name()) ? Integer.toString(i + 1) : rule.name()) + "': ";
      if (blank(rule.name())) {
        problems.add(new Problem(prefix + "a name is needed.", i, "name", null));
      }
      List<RoutingCondition> conditions = rule.conditions();
      for (int j = 0; j < conditions.size(); j++) {
        if (!complete(conditions.get(j))) {
          problems.add(
              new Problem(prefix + "complete or remove the empty condition.", i, "conditions", j));
          break;
        }
      }
      for (int j = 0; j < conditions.size(); j++) {
        RoutingCondition condition = conditions.get(j);
        if (!complete(condition)) {
          continue;
        }
        for (String value : condition.values()) {
          if (!valid(condition.field(), value)) {
            problems.add(
                new Problem(
                    prefix
                        + "'"
                        + value
                        + "' is not valid for "
                        + condition.field().displayName()
                        + ".",
                    i,
                    "conditions",
                    j));
          }
        }
      }
      if (i < rules.size() - 1 && conditions.isEmpty()) {
        problems.add(new Problem(prefix + "add at least one condition.", i, "conditions", null));
      }
      if (blank(rule.ownerTeamKey())) {
        problems.add(new Problem(prefix + "choose a team.", i, "owner_team_key", null));
      } else if (!activeTeam.test(rule.ownerTeamKey())) {
        problems.add(notActive(prefix, rule.ownerTeamKey(), i, "owner_team_key"));
      }
      for (String key : new LinkedHashSet<>(rule.informedTeamKeys())) {
        if (!activeTeam.test(key)) {
          problems.add(notActive(prefix, key, i, "informed_team_keys"));
        }
      }
    }
    if (rules.isEmpty() || !rules.getLast().conditions().isEmpty()) {
      problems.add(new Problem(LAST_RULE, null, null, null));
    }
    return problems;
  }

  private static Problem notActive(String prefix, String key, int rule, String field) {
    return new Problem(prefix + "'" + key + "' is not an active team.", rule, field, null);
  }

  /** Field, operator and at least one value set, no value {@code null} or blank. */
  static boolean complete(RoutingCondition condition) {
    return condition.field() != null
        && condition.operator() != null
        && condition.values() != null
        && !condition.values().isEmpty()
        && condition.values().stream().noneMatch(RuleSetValidation::blank);
  }

  /** True when {@code value} is a value of the field's type. */
  static boolean valid(RoutingField field, String value) {
    if (!field.allowedValues().isEmpty()) {
      return field.allowedValues().contains(value);
    }
    return field.valueType() == ValueType.INTEGER ? WHOLE.matcher(value).matches() : isKey(value);
  }

  static boolean isKey(String value) {
    return value != null && KEY.matcher(value).matches();
  }

  static boolean blank(String text) {
    return text == null || text.isBlank();
  }
}
