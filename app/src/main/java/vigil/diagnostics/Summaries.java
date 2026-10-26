package vigil.diagnostics;

import tools.jackson.databind.JsonNode;

/** The summary of a diagnostic result: the definition's template filled (DD-R-10, DC-TX-03). */
final class Summaries {

  private Summaries() {}

  /**
   * One left-to-right scan: {@code {name}} of a value of the array is replaced by the value's text
   * (a JSON string as it is, a number as its JSON text); every other brace is kept.
   */
  static String fill(String template, JsonNode values) {
    StringBuilder summary = new StringBuilder();
    int i = 0;
    while (i < template.length()) {
      char c = template.charAt(i);
      if (c == '{') {
        int end = template.indexOf('}', i + 1);
        JsonNode value = end > 0 ? value(values, template.substring(i + 1, end)) : null;
        if (value != null) {
          summary.append(value.isString() ? value.stringValue() : value.toString());
          i = end + 1;
          continue;
        }
      }
      summary.append(c);
      i++;
    }
    return summary.toString();
  }

  private static JsonNode value(JsonNode values, String name) {
    for (JsonNode entry : values) {
      if (entry.path("name").isString() && entry.get("name").stringValue().equals(name)) {
        return entry.get("value");
      }
    }
    return null;
  }
}
