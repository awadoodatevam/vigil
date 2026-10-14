package vigil;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConnectorLibraryRuleTest {

  @Test
  @DisplayName("AR-Q-03 http mail and ssh only in connectors and runner")
  void httpMailAndSshOnlyInConnectorsAndRunner() {
    JavaClasses production =
        new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("vigil");
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("vigil..")
            .and()
            .resideOutsideOfPackages("vigil.connectors..", "vigil.runner..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "java.net.http..",
                "jakarta.mail..",
                "org.springframework.mail..",
                "org.apache.sshd..")
            .allowEmptyShould(true);
    rule.check(production);
  }
}
