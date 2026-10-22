package vigil.routing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import vigil.routing.api.RoutingRule;

/** The change summary of a routing rule set revision and its audit summary (DC-TX-10, BP-A-28). */
final class ChangeSummary {

  private static final int MAX = 300;

  private ChangeSummary() {}

  /**
   * The parts of the rules that differ from {@code previous} ({@code null} for none), matched by
   * {@code rule_key}, or "no rules changed"; cut to 300. The rules' conditions are complete.
   */
  static String of(List<RoutingRule> previous, List<RoutingRule> rules) {
    Map<String, RoutingRule> before = byKey(previous == null ? List.of() : previous);
    Map<String, RoutingRule> after = byKey(rules);
    List<String> beforeOrder = before.keySet().stream().filter(after::containsKey).toList();
    List<String> afterOrder = after.keySet().stream().filter(before::containsKey).toList();
    List<String> parts = new ArrayList<>();
    for (RoutingRule rule : after.values()) {
      RoutingRule old = before.get(rule.ruleKey());
      if (old == null) {
        parts.add("added rule '" + rule.name() + "'");
        continue;
      }
      List<String> changed = new ArrayList<>();
      if (!Objects.equals(rule.name(), old.name())) {
        changed.add("name");
      }
      if (!rule.conditions().equals(old.conditions())) {
        changed.add("conditions");
      }
      if (rule.severity() != old.severity()) {
        changed.add("severity");
      }
      if (rule.ownerMode() != old.ownerMode()
          || !Objects.equals(rule.ownerTeamKey(), old.ownerTeamKey())) {
        changed.add("owner");
      }
      if (!rule.informedTeamKeys().equals(old.informedTeamKeys())) {
        changed.add("informed teams");
      }
      if (rule.informAffectedServiceOwners() != old.informAffectedServiceOwners()) {
        changed.add("inform owners of affected services");
      }
      if (afterOrder.indexOf(rule.ruleKey()) != beforeOrder.indexOf(rule.ruleKey())) {
        changed.add("position");
      }
      if (!changed.isEmpty()) {
        parts.add("changed rule '" + rule.name() + "' (" + String.join(", ", changed) + ")");
      }
    }
    for (RoutingRule old : before.values()) {
      if (!after.containsKey(old.ruleKey())) {
        parts.add("removed rule '" + old.name() + "'");
      }
    }
    return cut(parts.isEmpty() ? "no rules changed" : String.join("; ", parts));
  }

  /** DC-TX-10 {@code configuration_changed}, routing rules; cut to 300. */
  static String audit(long revision, String changeSummary) {
    return cut("Routing rules saved as revision " + revision + ": " + changeSummary + ".");
  }

  private static Map<String, RoutingRule> byKey(List<RoutingRule> rules) {
    Map<String, RoutingRule> map = new LinkedHashMap<>();
    rules.forEach(rule -> map.put(rule.ruleKey(), RoutingJson.stored(rule)));
    return map;
  }

  private static String cut(String text) {
    if (text.codePointCount(0, text.length()) <= MAX) {
      return text;
    }
    return text.substring(0, text.offsetByCodePoints(0, MAX));
  }
}
