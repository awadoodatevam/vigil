package vigil.testsupport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

/**
 * Seed bundle files for the tests, built from {@code docs/31-demo-data/} as {@code demo-control}
 * builds them (AR-B-05, AR-B-07): only the placeholders are replaced, as text.
 */
public final class SeedBundles {

  /** The folder of the demo data set, from the working directory {@code app/}. */
  private static final Path DATA = Path.of("../docs/31-demo-data");

  /** The nine data files of a bundle, in the entry order of AR-B-05. */
  private static final List<String> DATA_FILES =
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

  /** The nine mailbox keys of AR-B-07. */
  private static final List<String> MAILBOXES =
      List.of(
          "noc-l1",
          "db-ops",
          "vas-service-owners",
          "it-apps",
          "noc-shift-lead",
          "noc-management",
          "customer-care",
          "dbops-engineer",
          "dbops-lead");

  private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]*)}");
  private static final Pattern VERSION = Pattern.compile("(?m)^\\| Version \\| ([^ |]+) \\|$");
  private static final SecureRandom RANDOM = new SecureRandom();

  private SeedBundles() {}

  /**
   * A test value for every placeholder of DD-R-05 except {@code webhook_base_url}, which no bundle
   * holds.
   */
  public static Map<String, String> testValues(String hostKeyFingerprint) {
    Map<String, String> values = new LinkedHashMap<>();
    values.put("demo_domain", "vigil.test");
    values.put("endpoint.jira", "http://jira.test:8000");
    values.put("endpoint.smtp", "smtp://smtp.test:1025");
    values.put("smtp_starttls", "false");
    values.put("endpoint.claude", "http://claude.test:8000");
    values.put("endpoint.openai", "http://openai.test:8000");
    values.put("endpoint.local_embedding", "http://embed.test:11434");
    values.put("gpu_host_ip", "gpu.test");
    values.put("lab_db_host_key_sha256", hostKeyFingerprint);
    MAILBOXES.forEach(key -> values.put("mailbox." + key, key + "@vigil.test"));
    return values;
  }

  /** The Version in the header table of {@code docs/31-demo-data/README.md}. */
  private static String dataVersion() {
    Matcher matcher = VERSION.matcher(read(DATA.resolve("README.md")));
    if (!matcher.find()) {
      throw new IllegalStateException("no Version in docs/31-demo-data/README.md");
    }
    return matcher.group(1);
  }

  /** The text of {@code bundle.yaml} that {@code demo-control} writes (quoted values). */
  private static String bundleYaml() {
    return "bundle_name: \"jazz-demo\"\nbundle_version: \"" + dataVersion() + "\"\n";
  }

  /**
   * The entries of the demo bundle in entry order: {@code bundle.yaml}, then the nine data files
   * with every placeholder replaced by its value (a name not in {@code values} throws).
   */
  public static List<Map.Entry<String, byte[]>> demoEntries(Map<String, String> values) {
    List<Map.Entry<String, byte[]>> entries = new ArrayList<>();
    entries.add(Map.entry("bundle.yaml", bundleYaml().getBytes(StandardCharsets.UTF_8)));
    for (String file : DATA_FILES) {
      entries.add(
          Map.entry(
              file, replace(read(DATA.resolve(file)), values).getBytes(StandardCharsets.UTF_8)));
    }
    return entries;
  }

  /** {@code zip(demoEntries(values))}. */
  public static byte[] demoBundle(Map<String, String> values) {
    return zip(demoEntries(values));
  }

  /**
   * A ZIP of the entries in the given order, written here (local headers, {@code STORED} entries
   * with CRC-32, central directory, end record), so that a name may appear twice.
   */
  public static byte[] zip(List<Map.Entry<String, byte[]>> entries) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream central = new ByteArrayOutputStream();
    for (Map.Entry<String, byte[]> entry : entries) {
      byte[] name = entry.getKey().getBytes(StandardCharsets.UTF_8);
      byte[] content = entry.getValue();
      CRC32 crc = new CRC32();
      crc.update(content);
      int offset = out.size();
      int32(out, 0x04034b50);
      int16(out, 20);
      int16(out, 0x0800);
      int16(out, 0);
      int16(out, 0);
      int16(out, 0x21);
      int32(out, (int) crc.getValue());
      int32(out, content.length);
      int32(out, content.length);
      int16(out, name.length);
      int16(out, 0);
      out.writeBytes(name);
      out.writeBytes(content);
      int32(central, 0x02014b50);
      int16(central, 20);
      int16(central, 20);
      int16(central, 0x0800);
      int16(central, 0);
      int16(central, 0);
      int16(central, 0x21);
      int32(central, (int) crc.getValue());
      int32(central, content.length);
      int32(central, content.length);
      int16(central, name.length);
      int16(central, 0);
      int16(central, 0);
      int16(central, 0);
      int16(central, 0);
      int32(central, 0);
      int32(central, offset);
      central.writeBytes(name);
    }
    int centralOffset = out.size();
    out.writeBytes(central.toByteArray());
    int32(out, 0x06054b50);
    int16(out, 0);
    int16(out, 0);
    int16(out, entries.size());
    int16(out, entries.size());
    int32(out, central.size());
    int32(out, centralOffset);
    int16(out, 0);
    return out.toByteArray();
  }

  /** A {@code multipart/form-data} body and its content type with the boundary. */
  public record MultipartBody(byte[] content, String contentType) {}

  /** A form with one file part {@code field} ({@code Content-Type: application/zip}). */
  public static MultipartBody form(String field, String fileName, byte[] content) {
    byte[] random = new byte[16];
    RANDOM.nextBytes(random);
    String boundary = "vigil-" + HexFormat.of().formatHex(random);
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    body.writeBytes(
        ("--"
                + boundary
                + "\r\nContent-Disposition: form-data; name=\""
                + field
                + "\"; filename=\""
                + fileName
                + "\"\r\nContent-Type: application/zip\r\n\r\n")
            .getBytes(StandardCharsets.UTF_8));
    body.writeBytes(content);
    body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
    return new MultipartBody(body.toByteArray(), "multipart/form-data; boundary=" + boundary);
  }

  private static String replace(String text, Map<String, String> values) {
    Matcher matcher = PLACEHOLDER.matcher(text);
    StringBuilder filled = new StringBuilder();
    while (matcher.find()) {
      String value = values.get(matcher.group(1));
      if (value == null) {
        throw new IllegalArgumentException("no value for placeholder " + matcher.group(1));
      }
      matcher.appendReplacement(filled, Matcher.quoteReplacement(value));
    }
    matcher.appendTail(filled);
    return filled.toString();
  }

  private static String read(Path file) {
    try {
      return Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void int16(ByteArrayOutputStream out, int value) {
    out.write(value & 0xff);
    out.write((value >>> 8) & 0xff);
  }

  private static void int32(ByteArrayOutputStream out, int value) {
    int16(out, value & 0xffff);
    int16(out, (value >>> 16) & 0xffff);
  }
}
