package vigil.runner;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The five output parsers of DD-F-04 {@code output_parsers} (AR-K-13): each reads the kept output
 * into an ordered array of {@code {name, value}} objects, or finds it not readable.
 */
final class OutputParsers {

  private static final Pattern WHOLE = Pattern.compile("[0-9]+");
  private static final Pattern PERCENT = Pattern.compile("([0-9]+)%");
  private static final Pattern COUNT_BYTES = Pattern.compile("([0-9]+) ([0-9]+)");
  private static final Pattern INTEGER = Pattern.compile("-?[0-9]+");
  private static final BigDecimal GIB = BigDecimal.valueOf(1_073_741_824L);

  private OutputParsers() {}

  /** The values of {@code output} read by {@code parser}; empty when it is not readable. */
  static Optional<ArrayNode> parse(String parser, String output, JsonNode parameters) {
    List<String> lines = new ArrayList<>();
    for (String line : output.split("\n", -1)) {
      if (!line.isEmpty()) {
        lines.add(line);
      }
    }
    Values values = new Values();
    boolean readable =
        switch (parser) {
          case "df_row" -> dfRow(lines, values);
          case "du_top" -> duTop(lines, parameters, values);
          case "count_bytes" -> countBytes(lines, values);
          case "single_number" -> singleNumber(output, values);
          case "removed_files" -> removedFiles(lines, values);
          default -> throw new IllegalArgumentException("unknown parser " + parser);
        };
    return readable ? Optional.of(values.array) : Optional.empty();
  }

  private static boolean dfRow(List<String> lines, Values values) {
    if (lines.isEmpty()) {
      return false;
    }
    String[] fields = lines.getLast().strip().split(" +");
    if (fields.length != 5) {
      return false;
    }
    Long size = whole(fields[1]);
    Long used = whole(fields[2]);
    Long avail = whole(fields[3]);
    Matcher pcent = PERCENT.matcher(fields[4]);
    if (size == null || used == null || avail == null || !pcent.matches()) {
      return false;
    }
    Long pct = whole(pcent.group(1));
    if (pct == null) {
      return false;
    }
    values.text("mount", fields[0]);
    values.number("size_bytes", size);
    values.number("used_bytes", used);
    values.number("avail_bytes", avail);
    values.number("used_pct", pct);
    values.text("size_gib", gib(BigInteger.valueOf(size)));
    values.text("used_gib", gib(BigInteger.valueOf(used)));
    values.text("avail_gib", gib(BigInteger.valueOf(avail)));
    return true;
  }

  private static boolean duTop(List<String> lines, JsonNode parameters, Values values) {
    JsonNode path = parameters == null ? null : parameters.get("path");
    List<String[]> kept = new ArrayList<>();
    for (String line : lines) {
      int tab = line.indexOf('\t');
      if (tab < 0 || whole(line.substring(0, tab)) == null) {
        return false;
      }
      String folder = line.substring(tab + 1);
      if (path == null || !path.isString() || !folder.equals(path.stringValue())) {
        kept.add(new String[] {line.substring(0, tab), folder});
      }
    }
    for (int n = 1; n <= kept.size(); n++) {
      values.text("top" + n + "_path", kept.get(n - 1)[1]);
      values.text("top" + n + "_gib", gib(new BigInteger(kept.get(n - 1)[0])));
    }
    return true;
  }

  private static boolean countBytes(List<String> lines, Values values) {
    if (lines.size() != 1) {
      return false;
    }
    Matcher matcher = COUNT_BYTES.matcher(lines.getFirst());
    if (!matcher.matches()) {
      return false;
    }
    Long files = whole(matcher.group(1));
    Long bytes = whole(matcher.group(2));
    if (files == null || bytes == null) {
      return false;
    }
    values.number("files", files);
    values.number("bytes", bytes);
    values.text("gib", gib(BigInteger.valueOf(bytes)));
    return true;
  }

  private static boolean singleNumber(String output, Values values) {
    String text = output.strip();
    if (!INTEGER.matcher(text).matches()) {
      return false;
    }
    try {
      values.number("value", Long.parseLong(text));
      return true;
    } catch (NumberFormatException e) {
      return false;
    }
  }

  private static boolean removedFiles(List<String> lines, Values values) {
    BigInteger sum = BigInteger.ZERO;
    for (String line : lines) {
      int space = line.indexOf(' ');
      Long bytes = space < 0 ? null : whole(line.substring(0, space));
      if (bytes == null || line.substring(space + 1).isEmpty()) {
        return false;
      }
      sum = sum.add(BigInteger.valueOf(bytes));
    }
    values.number("files_removed", lines.size());
    values.array.addObject().put("name", "bytes_removed").put("value", sum);
    values.text("gib_removed", gib(sum));
    return true;
  }

  /** Digits only, within a signed 64-bit integer; otherwise {@code null}. */
  private static Long whole(String text) {
    if (!WHOLE.matcher(text).matches()) {
      return null;
    }
    try {
      return Long.parseLong(text);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /** Bytes in GiB, rounded half-up to one decimal place, always with one decimal. */
  private static String gib(BigInteger bytes) {
    return new BigDecimal(bytes).divide(GIB, 1, RoundingMode.HALF_UP).toPlainString();
  }

  /** The array of values being built. */
  private static final class Values {

    private final ArrayNode array = Parameters.JSON.createArrayNode();

    void text(String name, String value) {
      ObjectNode entry = array.addObject();
      entry.put("name", name);
      entry.put("value", value);
    }

    void number(String name, long value) {
      ObjectNode entry = array.addObject();
      entry.put("name", name);
      entry.put("value", value);
    }
  }
}
