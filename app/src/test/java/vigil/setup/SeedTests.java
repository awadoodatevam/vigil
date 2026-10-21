package vigil.setup;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;
import vigil.setup.api.SeedImportState;
import vigil.setup.api.SeedImportView;
import vigil.setup.api.SeedImports;
import vigil.testsupport.SeedBundles;
import vigil.testsupport.TestSecrets;
import vigil.testsupport.TestTenants;
import vigil.testsupport.VigilDatabase;

/** Helpers of the seed import tests: secrets, bundles changed as text, waiting for an import. */
final class SeedTests {

  /** The five users of DD-F-01. */
  static final List<String> USERS =
      List.of("presenter", "noc-l1", "dbops-engineer", "dbops-lead", "admin");

  private SeedTests() {}

  /** A secret folder with the installation token and the five seed passwords, made now. */
  static TestSecrets secrets(VigilDatabase database) {
    Map<String, String> values = new LinkedHashMap<>();
    values.put("installation-token", TestSecrets.randomToken());
    USERS.forEach(user -> values.put("seed-password-" + user, TestTenants.randomPassword()));
    return TestSecrets.create(database, values);
  }

  /** The demo entries with test values and a random host key fingerprint. */
  static List<Map.Entry<String, byte[]>> demoEntries() {
    byte[] key = new byte[32];
    new java.security.SecureRandom().nextBytes(key);
    return SeedBundles.demoEntries(
        SeedBundles.testValues("SHA256:" + java.util.Base64.getEncoder().encodeToString(key)));
  }

  /** The demo bundle. */
  static byte[] demoBundle() {
    return SeedBundles.zip(demoEntries());
  }

  /** The demo bundle with the text of {@code name} changed. */
  static byte[] demoBundleWith(String name, UnaryOperator<String> change) {
    return SeedBundles.zip(changed(demoEntries(), name, change));
  }

  static List<Map.Entry<String, byte[]>> changed(
      List<Map.Entry<String, byte[]>> entries, String name, UnaryOperator<String> change) {
    List<Map.Entry<String, byte[]>> result = new ArrayList<>();
    for (Map.Entry<String, byte[]> entry : entries) {
      if (entry.getKey().equals(name)) {
        String text = new String(entry.getValue(), StandardCharsets.UTF_8);
        result.add(Map.entry(name, change.apply(text).getBytes(StandardCharsets.UTF_8)));
      } else {
        result.add(entry);
      }
    }
    return result;
  }

  /** The text of an entry. */
  static String text(List<Map.Entry<String, byte[]>> entries, String name) {
    return entries.stream()
        .filter(entry -> entry.getKey().equals(name))
        .map(entry -> new String(entry.getValue(), StandardCharsets.UTF_8))
        .findFirst()
        .orElseThrow();
  }

  /** The version that {@code bundle.yaml} of the demo bundle holds (the README Version). */
  static String version() {
    String text = text(demoEntries(), "bundle.yaml");
    return text.substring(text.indexOf("bundle_version: \"") + 17, text.lastIndexOf('"'));
  }

  /** Replaces {@code old} once; fails when it is not in the text exactly once. */
  static UnaryOperator<String> replacing(String old, String replacement) {
    return text -> {
      assertThat(text.split(java.util.regex.Pattern.quote(old), -1)).as(old).hasSize(2);
      return text.replace(old, replacement);
    };
  }

  /** Replaces a top-level section (its key line and the indented lines below it). */
  static UnaryOperator<String> section(String key, String replacement) {
    return text -> {
      List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));
      int start = -1;
      for (int i = 0; i < lines.size(); i++) {
        if (lines.get(i).startsWith(key + ":")) {
          start = i;
          break;
        }
      }
      assertThat(start).as("section %s", key).isNotNegative();
      int end = start + 1;
      while (end < lines.size() && (lines.get(end).isEmpty() || lines.get(end).startsWith(" "))) {
        end++;
      }
      List<String> result = new ArrayList<>(lines.subList(0, start));
      result.add(replacement);
      result.add("");
      result.addAll(lines.subList(end, lines.size()));
      return String.join("\n", result);
    };
  }

  /** Polls the record every 100 ms until it is no longer {@code running}, at most 60 s. */
  static SeedImportView await(SeedImports seedImports, UUID id) {
    long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
    while (true) {
      SeedImportView view = seedImports.seedImport(id).orElseThrow();
      if (view.state() != SeedImportState.RUNNING) {
        return view;
      }
      if (System.nanoTime() > deadline) {
        throw new AssertionError("the import " + id + " is still running after 60 s");
      }
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }
  }
}
