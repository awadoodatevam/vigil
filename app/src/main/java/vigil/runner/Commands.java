package vigil.runner;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** The command of a run: the operation's template with its placeholders filled (AR-K-12). */
final class Commands {

  private Commands() {}

  /**
   * One left-to-right scan of the template (DD-R-10): {@code {name}} of a defined parameter is
   * replaced by the quoted text of its value; every other brace is kept.
   */
  static String fill(String template, JsonNode definitions, Map<String, String> texts) {
    Set<String> names = new HashSet<>();
    definitions.forEach(definition -> names.add(definition.get("name").stringValue()));
    StringBuilder command = new StringBuilder();
    int i = 0;
    while (i < template.length()) {
      char c = template.charAt(i);
      if (c == '{') {
        int end = template.indexOf('}', i + 1);
        if (end > 0 && names.contains(template.substring(i + 1, end))) {
          command.append(quote(texts.get(template.substring(i + 1, end))));
          i = end + 1;
          continue;
        }
      }
      command.append(c);
      i++;
    }
    return command.toString();
  }

  /** The value in single quotes for bash, each {@code '} written as {@code '\''}. */
  static String quote(String value) {
    return "'" + value.replace("'", "'\\''") + "'";
  }
}
