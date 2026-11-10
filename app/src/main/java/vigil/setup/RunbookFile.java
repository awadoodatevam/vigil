package vigil.setup;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import vigil.knowledge.api.KnowledgePart.RunbookPart;

/** The reader of {@code 08-runbooks.md} by DD-R-09 (P-20 spec Section 6.5). */
final class RunbookFile {

  static final String FILE = "08-runbooks.md";
  static final List<String> FIELDS =
      List.of(
          "runbook_key",
          "version",
          "title",
          "applicable_alert_types",
          "applicable_component_kinds");

  /** The runbooks read and the problems found, in file order. */
  record Read(List<RunbookPart> runbooks, List<String> problems) {

    Read {
      runbooks = List.copyOf(runbooks);
      problems = List.copyOf(problems);
    }
  }

  private RunbookFile() {}

  static Read read(String text) {
    List<String> lines = new ArrayList<>();
    for (String line : text.split("\n", -1)) {
      lines.add(line.endsWith("\r") ? line.substring(0, line.length() - 1) : line);
    }
    List<Integer> headings = new ArrayList<>();
    for (int i = 0; i < lines.size(); i++) {
      if (lines.get(i).startsWith("## ")) {
        headings.add(i);
      }
    }
    List<RunbookPart> runbooks = new ArrayList<>();
    List<String> problems = new ArrayList<>();
    if (headings.isEmpty()) {
      problems.add(FILE + ": no runbook");
      return new Read(runbooks, problems);
    }
    for (int h = 0; h < headings.size(); h++) {
      int start = headings.get(h) + 1;
      int end = h + 1 < headings.size() ? headings.get(h + 1) : lines.size();
      String heading = lines.get(headings.get(h)).substring(3).strip();
      RunbookPart runbook = runbook(heading, lines.subList(start, end), problems);
      if (runbook != null) {
        runbooks.add(runbook);
      }
    }
    return new Read(runbooks, problems);
  }

  private static RunbookPart runbook(String heading, List<String> lines, List<String> problems) {
    String at = FILE + " '" + heading + "': ";
    int first = 0;
    while (first < lines.size() && lines.get(first).isBlank()) {
      first++;
    }
    int last = first;
    while (last < lines.size() && lines.get(last).startsWith("|")) {
      last++;
    }
    if (last - first < 2) {
      problems.add(at + "no field table");
      return null;
    }
    Map<String, String> fields = new LinkedHashMap<>();
    boolean fieldProblem = false;
    for (int i = first + 2; i < last; i++) {
      String row = lines.get(i).strip();
      int n = i - first - 1;
      String[] cells =
          row.length() >= 2 && row.startsWith("|") && row.endsWith("|")
              ? row.substring(1, row.length() - 1).split("\\|", -1)
              : null;
      if (cells == null || cells.length != 2) {
        problems.add(at + "table row " + n + " is not '| field | value |'");
        continue;
      }
      String field = cells[0].strip();
      String value = cells[1].strip();
      if (!FIELDS.contains(field)) {
        problems.add(at + "unknown field " + field);
        fieldProblem = true;
      } else if (fields.containsKey(field)) {
        problems.add(at + "field " + field + " appears more than once");
        fieldProblem = true;
      } else {
        fields.put(field, value);
      }
    }
    for (String field : FIELDS) {
      if (!fields.containsKey(field)) {
        problems.add(at + "missing field " + field);
        fieldProblem = true;
      }
    }
    if (fieldProblem) {
      return null;
    }
    String text = String.join("\n", lines.subList(last, lines.size())).strip();
    return new RunbookPart(
        fields.get("runbook_key"),
        fields.get("version"),
        fields.get("title"),
        list(fields.get("applicable_alert_types")),
        list(fields.get("applicable_component_kinds")),
        text);
  }

  private static List<String> list(String value) {
    if (value.equals("(any)")) {
      return List.of();
    }
    List<String> elements = new ArrayList<>();
    for (String element : value.split(",", -1)) {
      elements.add(element.strip());
    }
    return List.copyOf(elements);
  }
}
