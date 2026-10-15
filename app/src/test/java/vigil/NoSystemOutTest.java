package vigil;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NoSystemOutTest {

  @Test
  @DisplayName("BP-R-16 no class of vigil-app uses System.out or System.err")
  void noSystemOutOrErr() {
    JavaClasses classes =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("vigil");
    noClasses()
        .that()
        .resideInAPackage("vigil..")
        .should()
        .accessField(System.class, "out")
        .orShould()
        .accessField(System.class, "err")
        .check(classes);
  }
}
