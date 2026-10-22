package vigil.incidents.api;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * The hypothesis component of an incident: DM-E-32 {@code hypothesis_component_key} and the copy
 * {@code hypothesis_snapshot}. {@code criticality} is {@code null} when not set.
 */
public record HypothesisCopy(
    long generation, String componentKey, String name, String kind, String criticality) {

  private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
  private static final Set<String> KINDS =
      Set.of("server", "application", "database", "queue", "check");
  static final Set<String> CRITICALITIES = Set.of("critical", "high", "medium", "low");

  public HypothesisCopy {
    if (generation < 1) {
      throw new IllegalArgumentException("hypothesis: generation >= 1");
    }
    if (componentKey == null || !KEY.matcher(componentKey).matches()) {
      throw new IllegalArgumentException("hypothesis: componentKey not a valid key");
    }
    if (name == null || name.isEmpty() || name.codePointCount(0, name.length()) > 100) {
      throw new IllegalArgumentException("hypothesis: name 1 to 100 characters");
    }
    if (!KINDS.contains(kind)) {
      throw new IllegalArgumentException("hypothesis: unknown kind");
    }
    if (criticality != null && !CRITICALITIES.contains(criticality)) {
      throw new IllegalArgumentException("hypothesis: unknown criticality");
    }
  }
}
