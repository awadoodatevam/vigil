package vigil.incidents;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import vigil.incidents.api.AlertCopy;
import vigil.incidents.api.HypothesisCopy;

/** The title of an incident (DM-R-20 with the alert copy of BP-A-27). */
final class IncidentTitle {

  static final int TITLE_MAX = 200;

  private static final Comparator<AlertCopy> EARLIEST =
      Comparator.comparing(AlertCopy::firedAt)
          .thenComparing(AlertCopy::sourceKind, IncidentTexts.CODE_POINTS)
          .thenComparing(AlertCopy::alertKey, IncidentTexts.CODE_POINTS);

  private IncidentTitle() {}

  /**
   * The title over the active members in member order ({@code joined_at}, then id), the hypothesis
   * ({@code null} for none) and the number of affected services, cut to 200.
   */
  static String title(List<AlertCopy> members, HypothesisCopy hypothesis, int services) {
    if (members.isEmpty()) {
      throw new IllegalArgumentException("an incident has at least one member");
    }
    String title;
    if (hypothesis == null) {
      AlertCopy earliest = members.stream().min(EARLIEST).orElseThrow();
      String value =
          earliest.firstIdentityPair() == null
              ? earliest.title()
              : earliest.firstIdentityPair().value();
      title = value + ": " + earliest.alertTypeName();
    } else {
      List<AlertCopy> onComponent =
          members.stream().filter(m -> hypothesis.componentKey().equals(m.componentKey())).toList();
      String primary = mostFrequentType(onComponent.isEmpty() ? members : onComponent);
      String name =
          members.stream()
              .filter(m -> m.alertType().equals(primary))
              .findFirst()
              .orElseThrow()
              .alertTypeName();
      title = hypothesis.name() + ": " + name + suffix(services);
    }
    return IncidentTexts.cut(title, TITLE_MAX);
  }

  /** The alert type held by the most alerts; ties go to the type key first in code point order. */
  private static String mostFrequentType(List<AlertCopy> alerts) {
    Map<String, Integer> counts = new LinkedHashMap<>();
    alerts.forEach(alert -> counts.merge(alert.alertType(), 1, Integer::sum));
    return counts.entrySet().stream()
        .min(
            Comparator.comparing((Map.Entry<String, Integer> e) -> -e.getValue())
                .thenComparing(Map.Entry::getKey, IncidentTexts.CODE_POINTS))
        .orElseThrow()
        .getKey();
  }

  private static String suffix(int services) {
    if (services == 0) {
      return "";
    }
    return services == 1 ? " — 1 service affected" : " — " + services + " services affected";
  }
}
