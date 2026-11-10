package vigil.aigateway;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vigil.connectors.api.AiProviderConnector;

class AiGatewayRuleTest {

  @Test
  @DisplayName("SL-B-08 AI provider connectors are used only by vigil-ai-gateway and connectors")
  void onlyTheGatewayCallsProviders() {
    JavaClasses production =
        new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("vigil");
    noClasses()
        .that()
        .resideInAPackage("vigil..")
        .and()
        .resideOutsideOfPackages("vigil.aigateway..", "vigil.connectors..")
        .should()
        .dependOnClassesThat()
        .areAssignableTo(AiProviderConnector.class)
        .check(production);
  }
}
