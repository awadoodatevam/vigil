package vigil.intake;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import vigil.intake.api.MonitoringKind;

/** DM-E17-R1: the alert type of an entry from the alert type mappings. */
final class AlertTypeRule {

  static final String UNCLASSIFIED = "unclassified";

  private AlertTypeRule() {}

  /** An alert type mapping (DM-E-17); {@code operator} is a key of DM-E-17. */
  record Mapping(
      MonitoringKind sourceKind,
      long order,
      String field,
      String operator,
      String value,
      String alertType) {}

  /**
   * The {@code alert_type} of the first mapping of {@code kind}, in ascending {@code order}, whose
   * comparison is true for the entry's mapping fields; {@code unclassified} when none is.
   */
  static String classify(
      MonitoringKind kind, List<Mapping> mappings, Map<String, String> mappingFields) {
    return mappings.stream()
        .filter(mapping -> mapping.sourceKind() == kind)
        .sorted(Comparator.comparingLong(Mapping::order))
        .filter(mapping -> matches(mapping, mappingFields.get(mapping.field())))
        .map(Mapping::alertType)
        .findFirst()
        .orElse(UNCLASSIFIED);
  }

  /** Case-sensitive comparison; a field the entry does not have makes it false. */
  private static boolean matches(Mapping mapping, String fieldValue) {
    if (fieldValue == null) {
      return false;
    }
    return switch (mapping.operator()) {
      case "equals" -> fieldValue.equals(mapping.value());
      case "starts_with" -> fieldValue.startsWith(mapping.value());
      case "contains" -> fieldValue.contains(mapping.value());
      default -> false;
    };
  }
}
