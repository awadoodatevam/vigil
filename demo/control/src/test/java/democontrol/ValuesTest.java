package democontrol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ValuesTest {

  @TempDir Path folder;

  @Test
  @DisplayName("AR-B-07 the values of two files are merged as bundle.py reads them")
  void merged() throws Exception {
    Path first = folder.resolve("values.yaml");
    Path second = folder.resolve("derived.yaml");
    Files.writeString(
        first,
        "# a comment\n\ndemo_domain: vigil.test\nsmtp_starttls: false\n"
            + "endpoint.jira:   http://jira.fakes:8000  \nquoted: \"a: b\"\n  # indented comment\n");
    Files.writeString(second, "lab_db_host_key_sha256: \"SHA256:abc\"\n");
    Map<String, String> values = Values.read(List.of("file:" + first, "file:" + second));
    assertThat(values)
        .containsExactly(
            Map.entry("demo_domain", "vigil.test"),
            Map.entry("smtp_starttls", "false"),
            Map.entry("endpoint.jira", "http://jira.fakes:8000"),
            Map.entry("quoted", "a: b"),
            Map.entry("lab_db_host_key_sha256", "SHA256:abc"));
    assertThat(Values.read(List.of("classpath:values/demo.yaml")))
        .containsEntry("product_display_name", "Techfinity")
        .containsKey("demo_domain");
  }

  @Test
  @DisplayName("AR-B-07 a name in two files stops the reset")
  void twice() throws Exception {
    Path first = folder.resolve("values.yaml");
    Path second = folder.resolve("derived.yaml");
    Files.writeString(first, "demo_domain: vigil.test\n");
    Files.writeString(second, "demo_domain: other.test\n");
    assertThatThrownBy(() -> Values.read(List.of("file:" + first, "file:" + second)))
        .isInstanceOf(Failed.class)
        .hasMessage("The value demo_domain is given twice.");
    Path missing = folder.resolve("missing.yaml");
    assertThatThrownBy(() -> Values.read(List.of("file:" + missing)))
        .isInstanceOf(Failed.class)
        .hasMessageStartingWith("The values file " + missing + " could not be read: ")
        .hasMessageEndingWith(".");
  }
}
