package vigil.setup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The reader of {@code 09-past-incidents.csv}, CSV as RFC 4180 (P-20 spec Section 6.5). */
final class PastIncidentFile {

  static final String FILE = "09-past-incidents.csv";
  static final List<String> COLUMNS =
      List.of(
          "past_incident_key",
          "title",
          "record_label",
          "opened_minutes_before_import",
          "acknowledged_after_minutes",
          "recovered_after_minutes",
          "alert_count",
          "alert_types",
          "affected_service_keys",
          "hypothesis_component_key",
          "summary",
          "fix_description",
          "fix_action_key",
          "fix_success_count");

  /** The rows read, each the 14 values by name, and the problems found. */
  record Read(List<Map<String, String>> rows, List<String> problems) {

    Read {
      rows = List.copyOf(rows);
      problems = List.copyOf(problems);
    }
  }

  /** One record: its fields, or the problem that ends it. */
  private record Record(List<String> fields, String problem, boolean stop, int next) {}

  private PastIncidentFile() {}

  static Read read(String text) {
    List<Map<String, String>> rows = new ArrayList<>();
    List<String> problems = new ArrayList<>();
    String header = FILE + ": the header must be " + String.join(",", COLUMNS);
    int position = 0;
    int number = 0;
    while (position < text.length() || number == 0) {
      Record record = record(text, position, number);
      if (number == 0) {
        if (record.problem() != null || !COLUMNS.equals(record.fields())) {
          return new Read(List.of(), List.of(header));
        }
      } else if (record.problem() != null) {
        problems.add(record.problem());
        if (record.stop()) {
          break;
        }
      } else if (record.fields().size() != COLUMNS.size()) {
        problems.add(
            FILE
                + " record "
                + number
                + ": "
                + record.fields().size()
                + " fields, "
                + COLUMNS.size()
                + " expected");
      } else {
        Map<String, String> row = new LinkedHashMap<>();
        for (int i = 0; i < COLUMNS.size(); i++) {
          row.put(COLUMNS.get(i), record.fields().get(i));
        }
        rows.add(Collections.unmodifiableMap(row));
      }
      position = record.next();
      number++;
    }
    return new Read(rows, problems);
  }

  /** The record that starts at {@code position}. */
  private static Record record(String text, int position, int number) {
    List<String> fields = new ArrayList<>();
    int i = position;
    int length = text.length();
    while (true) {
      StringBuilder field = new StringBuilder();
      if (i < length && text.charAt(i) == '"') {
        i++;
        while (true) {
          if (i >= length) {
            return new Record(
                null, FILE + " record " + number + ": a quoted field is not closed", true, length);
          }
          char c = text.charAt(i);
          if (c == '"') {
            if (i + 1 < length && text.charAt(i + 1) == '"') {
              field.append('"');
              i += 2;
            } else {
              i++;
              break;
            }
          } else {
            field.append(c);
            i++;
          }
        }
        fields.add(field.toString());
        if (i >= length) {
          return new Record(fields, null, false, length);
        }
        int end = lineEnd(text, i);
        if (end >= 0) {
          return new Record(fields, null, false, end);
        }
        if (text.charAt(i) == ',') {
          i++;
          continue;
        }
        int next = text.indexOf('\n', i);
        return new Record(
            null,
            FILE + " record " + number + ": text after a closing quote",
            false,
            next < 0 ? length : next + 1);
      }
      while (i < length && text.charAt(i) != ',' && lineEnd(text, i) < 0) {
        field.append(text.charAt(i));
        i++;
      }
      fields.add(field.toString());
      if (i >= length) {
        return new Record(fields, null, false, length);
      }
      int end = lineEnd(text, i);
      if (end >= 0) {
        return new Record(fields, null, false, end);
      }
      i++;
    }
  }

  /** The position after the record end at {@code i} ({@code \n} or {@code \r\n}), else -1. */
  private static int lineEnd(String text, int i) {
    if (text.charAt(i) == '\n') {
      return i + 1;
    }
    if (text.charAt(i) == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
      return i + 2;
    }
    return -1;
  }
}
