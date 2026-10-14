package vigil;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests run with the folder app/ as working directory. */
class SeparationTest {

  private static final Path APP_MAIN = Path.of("src/main");
  private static final Path WEB_SRC = Path.of("../web/src");

  /** BP-R-06: strings that product code must not contain. */
  private static final List<String> FORBIDDEN =
      List.of("demo-control", "demo-faults", "dev-fakes", "fakes", "172.31.0.1");

  @Test
  @DisplayName("BP-R-06 product code does not reference demo or test tooling")
  void productCodeDoesNotReferenceDemoOrTestTooling() throws IOException {
    JavaClasses production =
        new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("vigil");
    noClasses()
        .that()
        .resideInAPackage("vigil..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("democontrol..")
        .allowEmptyShould(true)
        .check(production);
    for (String forbidden : FORBIDDEN) {
      assertThat(filesContaining(forbidden)).as("files containing '%s'", forbidden).isEmpty();
    }
  }

  @Test
  @DisplayName("SL-B-10 no demo logic in the product")
  void noDemoLogicInTheProduct() throws IOException {
    assertThat(Files.isDirectory(APP_MAIN)).isTrue();
    assertThat(Files.isDirectory(WEB_SRC)).isTrue();
    for (String forbidden : List.of("demo-control", "demo-faults", "dev-fakes", "fakes")) {
      assertThat(filesContaining(forbidden)).as("files containing '%s'", forbidden).isEmpty();
    }
  }

  @Test
  @DisplayName("AR-R-03 product has no address of demo units")
  void productHasNoAddressOfDemoUnits() throws IOException {
    for (String forbidden : List.of("demo-control", "demo-faults", "172.31.0.1")) {
      assertThat(filesContaining(forbidden)).as("files containing '%s'", forbidden).isEmpty();
    }
  }

  @Test
  @DisplayName("AR-R-02 product and demo are built from separate folders")
  void productAndDemoAreBuiltFromSeparateFolders() throws IOException {
    assertThat(Path.of("../web")).isDirectory();
    assertThat(Path.of("../demo/control/pom.xml")).isRegularFile();
    String appPom = Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8);
    assertThat(appPom).doesNotContain("demo-control");
    assertThat(Files.readString(Path.of("../pom.xml"), StandardCharsets.UTF_8))
        .contains("<module>app</module>")
        .contains("<module>demo/control</module>");
  }

  private static List<Path> filesContaining(String text) throws IOException {
    try (Stream<Path> files = Stream.concat(Files.walk(APP_MAIN), Files.walk(WEB_SRC))) {
      return files
          .filter(Files::isRegularFile)
          .filter(
              file -> {
                try {
                  return Files.readString(file, StandardCharsets.UTF_8).contains(text);
                } catch (IOException e) {
                  throw new IllegalStateException(file.toString(), e);
                }
              })
          .toList();
    }
  }
}
