package vigil.incidents.api;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The copy of an affected service (DM-V-10); {@code viaComponentKeys} are kept sorted in code point
 * order. {@code criticality} is {@code null} when not set.
 */
public record AffectedServiceCopy(
    String serviceKey, String name, String criticality, List<String> viaComponentKeys) {

  private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

  public AffectedServiceCopy {
    if (serviceKey == null || !KEY.matcher(serviceKey).matches()) {
      throw new IllegalArgumentException("affected service: serviceKey not a valid key");
    }
    if (name == null || name.isEmpty() || name.codePointCount(0, name.length()) > 100) {
      throw new IllegalArgumentException("affected service: name 1 to 100 characters");
    }
    if (criticality != null && !HypothesisCopy.CRITICALITIES.contains(criticality)) {
      throw new IllegalArgumentException("affected service: unknown criticality");
    }
    if (viaComponentKeys == null
        || viaComponentKeys.isEmpty()
        || viaComponentKeys.stream().anyMatch(k -> k == null || !KEY.matcher(k).matches())) {
      throw new IllegalArgumentException("affected service: viaComponentKeys of valid keys");
    }
    viaComponentKeys = viaComponentKeys.stream().sorted().toList();
  }
}
