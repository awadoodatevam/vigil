package vigil.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import vigil.shared.VigilError;
import vigil.testsupport.SeedBundles;

class SeedBundleReaderTest {

  private static final Path DATA = Path.of("../docs/31-demo-data");
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

  private static List<Map.Entry<String, byte[]>> demo() {
    return SeedBundles.demoEntries(SeedBundles.testValues("SHA256:" + "A".repeat(43)));
  }

  private static String bundleYaml() {
    return new String(demo().getFirst().getValue(), StandardCharsets.UTF_8);
  }

  private static String version() {
    String text = bundleYaml();
    return text.substring(text.indexOf("bundle_version: \"") + 17, text.lastIndexOf('"'));
  }

  private static List<Map.Entry<String, byte[]>> changed(
      List<Map.Entry<String, byte[]>> entries, String name, UnaryOperator<byte[]> change) {
    List<Map.Entry<String, byte[]>> result = new ArrayList<>();
    for (Map.Entry<String, byte[]> entry : entries) {
      result.add(
          entry.getKey().equals(name) ? Map.entry(name, change.apply(entry.getValue())) : entry);
    }
    return result;
  }

  private static List<Map.Entry<String, byte[]>> withText(String name, String text) {
    return changed(demo(), name, old -> text.getBytes(StandardCharsets.UTF_8));
  }

  private static List<Map.Entry<String, byte[]>> plus(
      List<Map.Entry<String, byte[]>> entries, String name, byte[] content) {
    List<Map.Entry<String, byte[]>> result = new ArrayList<>(entries);
    result.add(Map.entry(name, content));
    return result;
  }

  private static List<Map.Entry<String, byte[]>> without(String name) {
    return demo().stream().filter(entry -> !entry.getKey().equals(name)).toList();
  }

  private static String refusal(List<Map.Entry<String, byte[]>> entries) {
    return refusal(SeedBundles.zip(entries));
  }

  private static String refusal(byte[] bytes) {
    VigilError error = catchThrowableOfType(VigilError.class, () -> SeedBundleReader.read(bytes));
    assertThat(error).as("the bundle is refused").isNotNull();
    assertThat(error.code()).isEqualTo(VigilError.Code.INVALID);
    assertThat(error.details()).containsKey("problems");
    return error.message();
  }

  @Test
  @DisplayName("AR-B-05 the ZIP, its entries and bundle.yaml are checked at upload")
  void zipEntriesAndBundleYaml() {
    assertThat(refusal("not a zip".getBytes(StandardCharsets.UTF_8)))
        .isEqualTo("The bundle cannot be read: the file is not a ZIP file.");
    for (String name : List.of("notes.txt", "docs/01-tenant-identity.yaml", "x/")) {
      assertThat(refusal(plus(demo(), name, new byte[0])))
          .isEqualTo("The bundle cannot be read: entry " + name + " is not allowed.");
    }
    assertThat(refusal(plus(demo(), "bundle.yaml", demo().getFirst().getValue())))
        .isEqualTo("The bundle cannot be read: entry bundle.yaml appears more than once.");
    assertThat(refusal(without("08-runbooks.md")))
        .isEqualTo("The bundle cannot be read: entry 08-runbooks.md is missing.");
    assertThat(
            refusal(changed(demo(), "02-service-map.yaml", old -> new byte[] {(byte) 0xC3, 0x28})))
        .isEqualTo("The bundle cannot be read: 02-service-map.yaml is not UTF-8.");
    assertThat(refusal(plus(without("08-runbooks.md"), "notes.txt", new byte[0])))
        .isEqualTo(
            "The bundle cannot be read: entry notes.txt is not allowed; entry 08-runbooks.md is"
                + " missing.");

    String quotedVersion = "bundle_version: \"" + version() + "\"\n";
    assertThat(refusal(withText("bundle.yaml", "bundle_name: \"\"\n" + quotedVersion)))
        .isEqualTo("The bundle has problems: bundle.yaml: bundle_name: 1 to 100 characters.");
    assertThat(refusal(withText("bundle.yaml", "bundle_name: \"jazz-demo\"\n")))
        .isEqualTo("The bundle has problems: bundle.yaml: bundle_version: 1 to 40 characters.");
    assertThat(
            refusal(withText("bundle.yaml", "bundle_name: \"jazz-demo\"\nbundle_version: 1.5\n")))
        .isEqualTo("The bundle has problems: bundle.yaml: bundle_version: 1 to 40 characters.");
    assertThat(
            refusal(
                withText(
                    "bundle.yaml",
                    "bundle_name: \"jazz-demo\"\nbundle_version: \"" + "v".repeat(41) + "\"\n")))
        .isEqualTo("The bundle has problems: bundle.yaml: bundle_version: 1 to 40 characters.");
    assertThat(refusal(withText("bundle.yaml", bundleYaml() + "extra: 1\n")))
        .isEqualTo("The bundle has problems: bundle.yaml: unknown field extra.");
    assertThat(refusal(withText("bundle.yaml", "- a\n")))
        .isEqualTo("The bundle has problems: bundle.yaml: not a mapping.");
    JacksonException parse =
        catchThrowableOfType(JacksonException.class, () -> SeedYaml.MAPPER.readTree("a: ["));
    String location =
        " (line "
            + parse.getLocation().getLineNr()
            + ", column "
            + parse.getLocation().getColumnNr()
            + ")";
    assertThat(refusal(withText("bundle.yaml", "a: [")))
        .isEqualTo(
            "The bundle has problems: bundle.yaml could not be read: "
                + parse.getOriginalMessage().lines().findFirst().orElseThrow()
                + location
                + ".");

    SeedBundle bundle = SeedBundleReader.read(SeedBundles.zip(demo()));
    assertThat(bundle.bundleName()).isEqualTo("jazz-demo");
    assertThat(bundle.bundleVersion()).isEqualTo(version()).isNotEmpty();
    assertThat(bundle.texts()).containsKeys(SeedParts.REQUIRED.toArray(String[]::new));

    SeedBundle unparsed =
        SeedBundleReader.read(SeedBundles.zip(withText("03-monitoring.yaml", "a: [")));
    assertThat(unparsed.texts().get("03-monitoring.yaml")).isEqualTo("a: [");
  }

  @Test
  @DisplayName("BP-A-23 the 50 MiB unpacked limit and the 512 KB logo limit at upload")
  void sizeLimits() {
    assertThat(refusal(plus(demo(), "logo.png", new byte[52_428_801])))
        .isEqualTo("The bundle cannot be read: the bundle is larger than 50 MiB unpacked.");
    assertThat(refusal(plus(demo(), "logo.svg", new byte[524_289])))
        .isEqualTo("The bundle cannot be read: entry logo.svg is larger than 512 KB.");
    SeedBundle accepted =
        SeedBundleReader.read(SeedBundles.zip(plus(demo(), "logo.png", new byte[524_288])));
    assertThat(accepted.entries().get("logo.png")).hasSize(524_288);
  }

  @Test
  @DisplayName("AR-B-07 a bundle that still contains placeholders is refused with their names")
  void placeholders() throws IOException {
    List<Map.Entry<String, byte[]>> unreplaced = new ArrayList<>();
    unreplaced.add(demo().getFirst());
    List<String> texts = new ArrayList<>();
    for (String file : DATA_FILES) {
      String text = Files.readString(DATA.resolve(file), StandardCharsets.UTF_8);
      texts.add(text);
      unreplaced.add(Map.entry(file, text.getBytes(StandardCharsets.UTF_8)));
    }
    String expected =
        "The bundle still contains placeholders: demo_domain, lab_db_host_key_sha256,"
            + " endpoint.jira, smtp_starttls, endpoint.smtp, endpoint.claude, endpoint.openai,"
            + " gpu_host_ip, endpoint.local_embedding, mailbox.noc-l1, mailbox.db-ops,"
            + " mailbox.vas-service-owners, mailbox.it-apps, mailbox.noc-shift-lead,"
            + " mailbox.noc-management, mailbox.customer-care, mailbox.dbops-engineer,"
            + " mailbox.dbops-lead.";
    assertThat(refusal(unreplaced)).isEqualTo(expected);
    assertThat("The bundle still contains placeholders: " + String.join(", ", names(texts)) + ".")
        .isEqualTo(expected);
    VigilError error =
        catchThrowableOfType(
            VigilError.class, () -> SeedBundleReader.read(SeedBundles.zip(unreplaced)));
    assertThat(error.details()).isEqualTo(Map.of("problems", List.of(expected)));

    assertThat(refusal(withText("08-runbooks.md", "text ${x\r\nmore")))
        .isEqualTo("The bundle still contains placeholders: x.");
    assertThat(refusal(withText("08-runbooks.md", "text ${} more")))
        .isEqualTo("The bundle still contains placeholders: \"\".");
    List<Map.Entry<String, byte[]>> both =
        changed(
            withText("08-runbooks.md", "${a}"),
            "bundle.yaml",
            old -> "- a\n".getBytes(StandardCharsets.UTF_8));
    assertThat(refusal(both)).isEqualTo("The bundle still contains placeholders: a.");
  }

  /** The rule of the spec's Section 6.3, computed independently from the files. */
  private static List<String> names(List<String> texts) {
    List<String> names = new ArrayList<>();
    for (String text : texts) {
      int at = text.indexOf("${");
      while (at >= 0) {
        int lineEnd = text.indexOf('\n', at);
        int close = text.indexOf('}', at);
        String name =
            close >= 0 && (lineEnd < 0 || close < lineEnd)
                ? text.substring(at + 2, close)
                : text.substring(at + 2, lineEnd < 0 ? text.length() : lineEnd);
        if (!names.contains(name)) {
          names.add(name);
        }
        at = text.indexOf("${", at + 2);
      }
    }
    return names;
  }
}
