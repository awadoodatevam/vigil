package vigil.knowledge;

import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;

/** The search text of DM-R-51, for past incidents and live searches alike. */
final class SearchTexts {

  static final Comparator<String> CODE_POINTS =
      Comparator.comparing(text -> text.codePoints().toArray(), Arrays::compare);

  private SearchTexts() {}

  /**
   * {@code alert types: {sorted, distinct, comma-separated}; services: {likewise}; component: {key,
   * or empty}}.
   */
  static String of(
      Collection<String> alertTypes, Collection<String> serviceKeys, String componentKey) {
    return "alert types: "
        + joined(alertTypes)
        + "; services: "
        + joined(serviceKeys)
        + "; component: "
        + (componentKey == null ? "" : componentKey);
  }

  private static String joined(Collection<String> keys) {
    return String.join(", ", keys.stream().distinct().sorted(CODE_POINTS).toList());
  }

  /** The text cut to {@code max} code points. */
  static String cut(String text, int max) {
    if (text.codePointCount(0, text.length()) <= max) {
      return text;
    }
    return text.substring(0, text.offsetByCodePoints(0, max));
  }
}
