package democontrol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BundleTest {

  private static final Path ROOT = Path.of("../..");
  private static final List<String> VALUES =
      List.of("file:../../deploy/values/local.yaml", "classpath:test-values/derived.yaml");

  @TempDir Path folder;

  static Map<String, byte[]> unzip(byte[] zip) throws Exception {
    Map<String, byte[]> entries = new LinkedHashMap<>();
    try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
      for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
        entries.put(entry.getName(), in.readAllBytes());
      }
    }
    return entries;
  }

  @Test
  @DisplayName("AR-B-05 the ten entries in order, bundle.yaml from the README's Version")
  void entries() throws Exception {
    byte[] zip = Bundle.build(Values.read(VALUES));
    Map<String, byte[]> entries = unzip(zip);
    assertThat(entries.keySet())
        .containsExactly(
            "bundle.yaml",
            "01-tenant-identity.yaml",
            "02-service-map.yaml",
            "03-monitoring.yaml",
            "04-operations-actions-recovery.yaml",
            "05-routing-escalation.yaml",
            "06-workflow.yaml",
            "07-settings.yaml",
            "08-runbooks.md",
            "09-past-incidents.csv");
    Matcher version =
        Pattern.compile("^\\| Version \\| ([^ |]+) \\|$", Pattern.MULTILINE)
            .matcher(Files.readString(ROOT.resolve("docs/31-demo-data/README.md")));
    assertThat(version.find()).isTrue();
    assertThat(new String(entries.get("bundle.yaml")))
        .isEqualTo("bundle_name: \"jazz-demo\"\nbundle_version: \"" + version.group(1) + "\"\n");
    try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
      for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
        assertThat(entry.getMethod()).isEqualTo(ZipEntry.STORED);
        assertThat(entry.getTimeLocal()).hasToString("1980-01-01T00:00");
      }
    }
    assertThat(new String(entries.get("07-settings.yaml"))).doesNotContain("${");
  }

  @Test
  @DisplayName("AR-B-07 the bundle equals the one bundle.py builds for the same values")
  void equalsBundlePy() throws Exception {
    Path derived = folder.resolve("derived.yaml");
    try (InputStream in = getClass().getResourceAsStream("/test-values/derived.yaml")) {
      Files.write(derived, in.readAllBytes());
    }
    Path out = folder.resolve("bundle.zip");
    Process process =
        new ProcessBuilder(
                "python3",
                ROOT.resolve("deploy/local/bundle.py").toString(),
                "--data",
                ROOT.resolve("docs/31-demo-data").toString(),
                "--values",
                ROOT.resolve("deploy/values/local.yaml").toString(),
                "--values",
                derived.toString(),
                "--out",
                out.toString())
            .redirectErrorStream(true)
            .start();
    String output = new String(process.getInputStream().readAllBytes());
    assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
    assertThat(process.exitValue()).as(output).isZero();
    Map<String, byte[]> python = unzip(Files.readAllBytes(out));
    Map<String, byte[]> java = unzip(Bundle.build(Values.read(VALUES)));
    assertThat(java.keySet()).containsExactlyElementsOf(python.keySet());
    for (String name : python.keySet()) {
      assertThat(java.get(name)).as(name).isEqualTo(python.get(name));
    }
  }

  @Test
  @DisplayName("AR-B-07 a placeholder left over refuses the bundle")
  void placeholders() throws Exception {
    Map<String, String> values = new LinkedHashMap<>(Values.read(VALUES));
    values.remove("endpoint.smtp");
    values.remove("demo_domain");
    assertThatThrownBy(() -> Bundle.build(values))
        .isInstanceOf(Failed.class)
        .hasMessageStartingWith("The bundle still contains placeholders: ")
        .hasMessageContaining("demo_domain")
        .hasMessageContaining("endpoint.smtp")
        .hasMessageEndingWith(".");
    Map<String, String> one = new LinkedHashMap<>(Values.read(VALUES));
    one.remove("gpu_host_ip");
    assertThatThrownBy(() -> Bundle.build(one))
        .hasMessage("The bundle still contains placeholders: gpu_host_ip.");
  }

  @Test
  @DisplayName("AR-X-05 the data and demo.yaml are in the JAR")
  void inTheJar() throws Exception {
    for (String name : Bundle.DATA_FILES) {
      assertThat(resource("/demo-data/" + name))
          .as(name)
          .isEqualTo(Files.readAllBytes(ROOT.resolve("docs/31-demo-data").resolve(name)));
    }
    assertThat(resource("/demo-data/README.md"))
        .isEqualTo(Files.readAllBytes(ROOT.resolve("docs/31-demo-data/README.md")));
    assertThat(resource("/values/demo.yaml"))
        .isEqualTo(Files.readAllBytes(ROOT.resolve("deploy/values/demo.yaml")));
    assertThat(getClass().getResource("/demo-data/10-lab-and-faults.yaml")).isNull();
    assertThat(
            Files.readString(ROOT.resolve("demo/control/pom.xml"))
                .contains("<targetPath>demo-data"))
        .isTrue();
  }

  private byte[] resource(String name) throws Exception {
    try (InputStream in = getClass().getResourceAsStream(name)) {
      assertThat(in).as(name).isNotNull();
      return in.readAllBytes();
    }
  }
}
