package democontrol;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The seed bundle (AR-B-05, AR-B-07): the ten entries of {@code deploy/local/bundle.py} in its
 * order, from the data built into the JAR at {@code demo-data/}, with every {@code ${name}}
 * replaced as text by its value.
 */
final class Bundle {

  static final List<String> DATA_FILES =
      List.of(
          "01-tenant-identity.yaml",
          "02-service-map.yaml",
          "03-monitoring.yaml",
          "04-operations-actions-recovery.yaml",
          "05-routing-escalation.yaml",
          "06-workflow.yaml",
          "07-settings.yaml",
          "08-runbooks.md",
          "09-past-incidents.csv");
  private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]*)\\}");
  private static final Pattern VERSION =
      Pattern.compile("^\\| Version \\| ([^ |]+) \\|$", Pattern.MULTILINE);
  private static final LocalDateTime FIXED_TIME = LocalDateTime.of(1980, 1, 1, 0, 0, 0);

  private Bundle() {}

  /** The ten entries in order: name and bytes. */
  static Map<String, byte[]> entries(Map<String, String> values) throws Failed {
    Matcher version = VERSION.matcher(resource("README.md"));
    if (!version.find()) {
      throw new IllegalStateException("demo-data/README.md has no Version");
    }
    Map<String, String> texts = new LinkedHashMap<>();
    texts.put(
        "bundle.yaml",
        "bundle_name: \"jazz-demo\"\nbundle_version: \"" + version.group(1) + "\"\n");
    for (String name : DATA_FILES) {
      texts.put(name, replace(resource(name), values));
    }
    List<String> left = new ArrayList<>();
    for (String text : texts.values()) {
      for (int index = text.indexOf("${"); index >= 0; index = text.indexOf("${", index + 1)) {
        Matcher match = PLACEHOLDER.matcher(text).region(index, text.length());
        String name;
        if (match.lookingAt()) {
          name = match.group(1);
        } else {
          name = text.substring(index + 2, Math.min(text.length(), index + 42)).split("\n")[0];
        }
        if (!left.contains(name)) {
          left.add(name);
        }
      }
    }
    if (!left.isEmpty()) {
      throw new Failed("The bundle still contains placeholders: " + String.join(", ", left) + ".");
    }
    Map<String, byte[]> entries = new LinkedHashMap<>();
    texts.forEach((name, text) -> entries.put(name, text.getBytes(StandardCharsets.UTF_8)));
    return entries;
  }

  /** The bundle ZIP: STORED entries dated 1980-01-01 00:00:00. */
  static byte[] build(Map<String, String> values) throws Failed {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(out)) {
      for (Map.Entry<String, byte[]> entry : entries(values).entrySet()) {
        byte[] content = entry.getValue();
        ZipEntry zipEntry = new ZipEntry(entry.getKey());
        CRC32 crc = new CRC32();
        crc.update(content);
        zipEntry.setMethod(ZipEntry.STORED);
        zipEntry.setSize(content.length);
        zipEntry.setCompressedSize(content.length);
        zipEntry.setCrc(crc.getValue());
        zipEntry.setTimeLocal(FIXED_TIME);
        zip.putNextEntry(zipEntry);
        zip.write(content);
        zip.closeEntry();
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return out.toByteArray();
  }

  private static String replace(String text, Map<String, String> values) {
    return PLACEHOLDER
        .matcher(text)
        .replaceAll(
            match -> Matcher.quoteReplacement(values.getOrDefault(match.group(1), match.group(0))));
  }

  private static String resource(String name) {
    try (InputStream in = Bundle.class.getClassLoader().getResourceAsStream("demo-data/" + name)) {
      if (in == null) {
        throw new IllegalStateException("demo-data/" + name + " is not in the JAR");
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
