package vigil.setup;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import vigil.shared.VigilError;

/**
 * Reads a seed bundle file at upload (AR-B-05, AR-B-07, BP-A-23) in three stages: the ZIP, the
 * placeholders and {@code bundle.yaml}. A stage with problems refuses the bundle with {@code
 * INVALID}; the data files are not parsed here.
 */
final class SeedBundleReader {

  static final long UNPACKED_MAX = 52_428_800L;
  static final int LOGO_MAX = 524_288;
  private static final int NAME_MAX = 100;
  private static final int VERSION_MAX = 40;
  private static final Set<String> BUNDLE_FIELDS = Set.of("bundle_name", "bundle_version");

  private SeedBundleReader() {}

  /** The bundle of {@code bytes}, or {@code VigilError INVALID} with its problems. */
  static SeedBundle read(byte[] bytes) {
    List<Map.Entry<String, byte[]>> raw = unzip(bytes);
    Map<String, byte[]> entries = new HashMap<>();
    raw.forEach(entry -> entries.putIfAbsent(entry.getKey(), entry.getValue()));
    Map<String, String> texts = checkEntries(raw, entries);
    checkPlaceholders(texts);
    JsonNode bundle = checkBundleYaml(texts.get(SeedParts.BUNDLE));
    return new SeedBundle(
        entries,
        texts,
        bundle.get("bundle_name").stringValue(),
        bundle.get("bundle_version").stringValue());
  }

  /** Stage A rules 1 and 2: the entries in ZIP order, unpacked within the limit. */
  private static List<Map.Entry<String, byte[]>> unzip(byte[] bytes) {
    List<Map.Entry<String, byte[]>> entries = new ArrayList<>();
    long total = 0;
    byte[] buffer = new byte[8192];
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        int read;
        while ((read = zip.read(buffer)) >= 0) {
          total += read;
          if (total > UNPACKED_MAX) {
            throw unreadable(List.of("the bundle is larger than 50 MiB unpacked"));
          }
          content.write(buffer, 0, read);
        }
        entries.add(Map.entry(entry.getName(), content.toByteArray()));
      }
    } catch (IOException | IllegalArgumentException e) {
      throw unreadable(List.of("the file is not a ZIP file"));
    }
    if (entries.isEmpty()) {
      throw unreadable(List.of("the file is not a ZIP file"));
    }
    return entries;
  }

  /** Stage A rules 3 to 7; returns the decoded text entries in entry order. */
  private static Map<String, String> checkEntries(
      List<Map.Entry<String, byte[]>> raw, Map<String, byte[]> entries) {
    Set<String> problems = new LinkedHashSet<>();
    List<String> names = raw.stream().map(Map.Entry::getKey).toList();
    for (String name : names) {
      if (!SeedParts.ALLOWED.contains(name)) {
        problems.add("entry " + name + " is not allowed");
      }
    }
    List<String> repeated = new ArrayList<>();
    for (String name : SeedParts.ALLOWED) {
      if (names.stream().filter(name::equals).count() > 1) {
        repeated.add(name);
      }
    }
    Set<String> seen = new LinkedHashSet<>();
    for (String name : names) {
      if (!seen.add(name) && !SeedParts.ALLOWED.contains(name)) {
        repeated.add(name);
      }
    }
    repeated.forEach(name -> problems.add("entry " + name + " appears more than once"));
    for (String name : SeedParts.REQUIRED) {
      if (!entries.containsKey(name)) {
        problems.add("entry " + name + " is missing");
      }
    }
    for (String name : List.of(SeedParts.LOGO_PNG, SeedParts.LOGO_SVG)) {
      if (entries.containsKey(name) && entries.get(name).length > LOGO_MAX) {
        problems.add("entry " + name + " is larger than 512 KB");
      }
    }
    Map<String, String> texts = new LinkedHashMap<>();
    for (String name : SeedParts.REQUIRED) {
      if (entries.containsKey(name)) {
        try {
          texts.put(name, strictUtf8(entries.get(name)));
        } catch (CharacterCodingException e) {
          problems.add(name + " is not UTF-8");
        }
      }
    }
    if (!problems.isEmpty()) {
      throw unreadable(List.copyOf(problems));
    }
    return texts;
  }

  /** Stage B (AR-B-07): every placeholder of the text entries, in order of appearance. */
  private static void checkPlaceholders(Map<String, String> texts) {
    List<String> names = placeholders(texts.values());
    if (!names.isEmpty()) {
      String message = "The bundle still contains placeholders: " + String.join(", ", names) + ".";
      throw new VigilError(VigilError.Code.INVALID, message, Map.of("problems", List.of(message)));
    }
  }

  /**
   * The distinct placeholder names of {@code texts} in order of first appearance: for each "$" and
   * "{", the text to the next closing brace on its line, or to the line's end; an empty name is
   * written {@code ""}.
   */
  static List<String> placeholders(Iterable<String> texts) {
    Set<String> names = new LinkedHashSet<>();
    for (String text : texts) {
      for (String line : text.split("\n", -1)) {
        int start = line.indexOf("${");
        while (start >= 0) {
          int end = line.indexOf('}', start + 2);
          String name;
          if (end >= 0) {
            name = line.substring(start + 2, end);
          } else {
            name = line.substring(start + 2);
            if (name.endsWith("\r")) {
              name = name.substring(0, name.length() - 1);
            }
          }
          names.add(name.isEmpty() ? "\"\"" : name);
          start = line.indexOf("${", start + 2);
        }
      }
    }
    return List.copyOf(names);
  }

  /** Stage C: {@code bundle.yaml} is a mapping of exactly {@code bundle_name} and its version. */
  private static JsonNode checkBundleYaml(String text) {
    JsonNode tree;
    try {
      tree = SeedYaml.read(text);
    } catch (JacksonException e) {
      throw problems(
          List.of(SeedParts.BUNDLE + " could not be read: " + SeedYaml.parserMessage(e)));
    }
    if (tree == null || !tree.isObject()) {
      throw problems(List.of(SeedParts.BUNDLE + ": not a mapping"));
    }
    List<String> problems = new ArrayList<>();
    if (!isText(tree.get("bundle_name"), NAME_MAX)) {
      problems.add(SeedParts.BUNDLE + ": bundle_name: 1 to 100 characters");
    }
    if (!isText(tree.get("bundle_version"), VERSION_MAX)) {
      problems.add(SeedParts.BUNDLE + ": bundle_version: 1 to 40 characters");
    }
    for (String key : tree.propertyNames()) {
      if (!BUNDLE_FIELDS.contains(key)) {
        problems.add(SeedParts.BUNDLE + ": unknown field " + key);
      }
    }
    if (!problems.isEmpty()) {
      throw problems(problems);
    }
    return tree;
  }

  private static boolean isText(JsonNode node, int max) {
    if (node == null || !node.isString()) {
      return false;
    }
    String value = node.stringValue();
    int length = value.codePointCount(0, value.length());
    return length >= 1 && length <= max;
  }

  private static String strictUtf8(byte[] bytes) throws CharacterCodingException {
    return StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString();
  }

  private static VigilError unreadable(List<String> problems) {
    return refusal("The bundle cannot be read: ", problems);
  }

  private static VigilError problems(List<String> problems) {
    return refusal("The bundle has problems: ", problems);
  }

  private static VigilError refusal(String prefix, List<String> problems) {
    return new VigilError(
        VigilError.Code.INVALID,
        prefix + String.join("; ", problems) + ".",
        Map.of("problems", List.copyOf(problems)));
  }
}
