package vigil;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vigil.connectors.api.Secrets;
import vigil.shared.TenantId;

class SecretCallerRuleTest {

  @Test
  @DisplayName("AR-Q-09 each Secrets operation is called only by its own module")
  void secretsHaveTheirCallers() {
    JavaClasses production =
        new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("vigil");
    noClasses()
        .that()
        .resideOutsideOfPackages("vigil.connectors..", "vigil.runner..")
        .should()
        .callMethod(Secrets.class, "read", TenantId.class, UUID.class)
        .allowEmptyShould(true)
        .check(production);
    noClasses()
        .that()
        .resideOutsideOfPackages("vigil.connectors..", "vigil.setup..")
        .should()
        .callMethod(Secrets.class, "readSeedPassword", String.class)
        .allowEmptyShould(true)
        .check(production);
    noClasses()
        .that()
        .resideOutsideOfPackages("vigil.connectors..", "vigil.api..")
        .should()
        .callMethod(Secrets.class, "verifyInstallationToken", String.class)
        .allowEmptyShould(true)
        .check(production);
    noClasses()
        .that()
        .resideOutsideOfPackages("vigil.connectors..", "vigil.intake..")
        .should()
        .callMethod(Secrets.class, "verify", TenantId.class, UUID.class, String.class)
        .allowEmptyShould(true)
        .check(production);
  }
}
