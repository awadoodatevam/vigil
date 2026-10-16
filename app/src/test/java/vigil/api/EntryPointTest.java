package vigil.api;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

class EntryPointTest {

  private static final JavaClasses PRODUCTION =
      new ClassFileImporter()
          .withImportOption(new ImportOption.DoNotIncludeTests())
          .importPackages("vigil");

  @Test
  @DisplayName("PM-M23-01 HTTP handlers exist only in vigil.api")
  void handlersOnlyInApi() {
    classes()
        .that()
        .areMetaAnnotatedWith(Controller.class)
        .should()
        .resideInAPackage("vigil.api")
        .check(PRODUCTION);
  }

  @Test
  @DisplayName("PM-M23-02 every handler method declares its access")
  void everyHandlerDeclaresItsAccess() {
    methods()
        .that()
        .areDeclaredInClassesThat()
        .areMetaAnnotatedWith(Controller.class)
        .and()
        .areMetaAnnotatedWith(RequestMapping.class)
        .should(carryExactlyOneAccessAnnotation())
        .check(PRODUCTION);
  }

  private static ArchCondition<JavaMethod> carryExactlyOneAccessAnnotation() {
    return new ArchCondition<>("carry exactly one of @Public, @LoggedIn, @RequiresPermission") {
      @Override
      public void check(JavaMethod method, ConditionEvents events) {
        long count =
            Stream.of(Public.class, LoggedIn.class, RequiresPermission.class)
                .filter(method::isAnnotatedWith)
                .count();
        events.add(
            new SimpleConditionEvent(
                method, count == 1, method.getFullName() + " carries " + count));
      }
    };
  }
}
