package democontrol;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;

/**
 * The deployment values (AR-B-07): the files of {@code demo-control.values}, in the format {@code
 * deploy/local/bundle.py} reads (lines {@code name: value}; comments and blank lines ignored; a
 * value in double quotes loses them).
 */
final class Values {

  private static final Pattern LINE = Pattern.compile("^([^\\s:#][^:]*):[ \\t]*(.*?)\\s*$");
  private static final ResourceLoader LOADER = new DefaultResourceLoader();

  private Values() {}

  static Map<String, String> read(List<String> locations) throws Failed {
    Map<String, String> values = new LinkedHashMap<>();
    for (String location : locations) {
      String text;
      try (InputStream in = LOADER.getResource(location).getInputStream()) {
        text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      } catch (IOException e) {
        throw unreadable(location, Failed.text(e));
      }
      String[] lines = text.split("\\R", -1);
      for (int number = 1; number <= lines.length; number++) {
        String line = lines[number - 1];
        if (line.isBlank() || line.stripLeading().startsWith("#")) {
          continue;
        }
        Matcher match = LINE.matcher(line);
        if (!match.matches()) {
          throw unreadable(location, "line " + number + " is not a line name: value");
        }
        String name = match.group(1).strip();
        String value = match.group(2);
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
          value = value.substring(1, value.length() - 1);
        }
        if (values.containsKey(name)) {
          throw new Failed("The value " + name + " is given twice.");
        }
        values.put(name, value);
      }
    }
    return values;
  }

  private static Failed unreadable(String location, String error) {
    String path = location.startsWith("file:") ? location.substring("file:".length()) : location;
    return new Failed("The values file " + path + " could not be read: " + error + ".");
  }
}
