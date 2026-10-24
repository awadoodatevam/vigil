package vigil.correlation;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import vigil.correlation.api.TieBreak;
import vigil.servicemap.api.Criticality;

/** The hypothesis rule (DM-R-18, DM-E-30) over a set of affected components. */
final class Hypothesis {

  private Hypothesis() {}

  /**
   * The computed values: the affected components sorted, the dependent count of each, the result
   * ({@code null} without an affected component) and the last rule needed to decide.
   */
  record Result(
      List<String> affected,
      Map<String, Long> dependentCounts,
      String component,
      TieBreak tieBreak) {}

  /**
   * For each affected component C, the number of other affected components among {@code
   * dependents(C)}; the largest count wins, ties to the higher criticality ({@code null} below
   * {@code LOW}), then to the key first in code point order.
   */
  static Result compute(
      Collection<String> affected,
      Function<String, Collection<String>> dependents,
      Function<String, Criticality> criticality) {
    List<String> keys = affected.stream().distinct().sorted(GroupingRules.CODE_POINTS).toList();
    Set<String> set = new HashSet<>(keys);
    Map<String, Long> counts = new LinkedHashMap<>();
    for (String key : keys) {
      counts.put(
          key,
          dependents.apply(key).stream()
              .filter(dependent -> !dependent.equals(key) && set.contains(dependent))
              .distinct()
              .count());
    }
    Map<String, Long> dependentCounts = Collections.unmodifiableMap(counts);
    if (keys.isEmpty()) {
      return new Result(keys, dependentCounts, null, TieBreak.NONE);
    }
    long largest = Collections.max(counts.values());
    List<String> top = keys.stream().filter(key -> counts.get(key) == largest).toList();
    if (top.size() == 1) {
      return new Result(keys, dependentCounts, top.getFirst(), TieBreak.NONE);
    }
    int best = top.stream().mapToInt(key -> rank(criticality.apply(key))).min().orElseThrow();
    List<String> highest =
        top.stream().filter(key -> rank(criticality.apply(key)) == best).toList();
    if (highest.size() == 1) {
      return new Result(keys, dependentCounts, highest.getFirst(), TieBreak.CRITICALITY);
    }
    return new Result(keys, dependentCounts, highest.getFirst(), TieBreak.COMPONENT_KEY);
  }

  /** {@code CRITICAL} 0 to {@code LOW} 3; none 4. */
  private static int rank(Criticality criticality) {
    return criticality == null ? Criticality.values().length : criticality.ordinal();
  }
}
