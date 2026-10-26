package vigil;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.List;
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

  @Test
  @DisplayName("AR-Q-09 Secrets.read is called from vigil.runner only")
  void readIsCalledByTheRunner() {
    JavaClasses production =
        new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("vigil");
    List<JavaMethodCall> calls =
        production.stream()
            .flatMap(javaClass -> javaClass.getMethodCallsFromSelf().stream())
            .filter(call -> call.getTarget().getOwner().isEquivalentTo(Secrets.class))
            .filter(call -> call.getTarget().getName().equals("read"))
            .filter(
                call ->
                    call.getTarget().getRawParameterTypes().stream()
                        .map(JavaClass::getName)
                        .toList()
                        .equals(List.of(TenantId.class.getName(), UUID.class.getName())))
            .toList();
    assertThat(calls).isNotEmpty();
    assertThat(calls)
        .extracting(call -> call.getOriginOwner())
        .extracting(JavaClass::getPackageName)
        .allMatch(name -> name.equals("vigil.runner") || name.startsWith("vigil.runner."));
  }
}
