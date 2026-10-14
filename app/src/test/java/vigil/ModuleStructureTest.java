package vigil;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.ApplicationModule;
import org.springframework.modulith.Modulithic;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.core.NamedInterface;

class ModuleStructureTest {

  /**
   * One row of the table in 50-architecture Section 7.1 (without "shared", which every row has).
   */
  private record Row(String javaPackage, List<String> allowedDependencies) {}

  /** Copy of the table in 50-architecture Section 7.1, rows AR-M-01 to AR-M-23, in table order. */
  private static final List<Row> TABLE =
      List.of(
          new Row("audit", List.of()),
          new Row("tenancy", List.of("audit")),
          new Row("identity", List.of("tenancy", "audit")),
          new Row("connectors", List.of("audit")),
          new Row("runner", List.of("tenancy", "connectors", "audit")),
          new Row("servicemap", List.of("audit")),
          new Row("intake", List.of("tenancy", "connectors", "audit")),
          new Row("aigateway", List.of("connectors", "audit")),
          new Row("knowledge", List.of("tenancy", "aigateway", "audit")),
          new Row("incidents", List.of("tenancy", "identity", "audit")),
          new Row(
              "investigation",
              List.of("incidents", "servicemap", "knowledge", "aigateway", "audit")),
          new Row(
              "notifications",
              List.of("connectors", "identity", "incidents", "investigation", "tenancy", "audit")),
          new Row(
              "ticketing",
              List.of("connectors", "intake", "incidents", "investigation", "tenancy", "audit")),
          new Row(
              "routing",
              List.of(
                  "incidents", "servicemap", "identity", "notifications", "ticketing", "audit")),
          new Row(
              "correlation",
              List.of("tenancy", "intake", "servicemap", "incidents", "routing", "audit")),
          new Row("recovery", List.of("intake", "incidents", "correlation", "servicemap", "audit")),
          new Row("diagnostics", List.of("runner", "servicemap", "incidents", "audit")),
          new Row(
              "actions",
              List.of(
                  "knowledge",
                  "runner",
                  "servicemap",
                  "incidents",
                  "identity",
                  "notifications",
                  "recovery",
                  "audit")),
          new Row(
              "engine",
              List.of(
                  "tenancy",
                  "intake",
                  "incidents",
                  "diagnostics",
                  "investigation",
                  "actions",
                  "recovery",
                  "routing",
                  "notifications",
                  "ticketing",
                  "knowledge",
                  "servicemap",
                  "identity",
                  "audit")),
          new Row(
              "workflows",
              List.of(
                  "tenancy", "engine", "incidents", "routing", "identity", "diagnostics", "audit")),
          new Row("reporting", List.of("incidents", "intake", "knowledge", "tenancy", "audit")),
          new Row(
              "setup",
              List.of(
                  "connectors",
                  "intake",
                  "servicemap",
                  "identity",
                  "audit",
                  "tenancy",
                  "runner",
                  "aigateway",
                  "knowledge",
                  "incidents",
                  "correlation",
                  "routing",
                  "diagnostics",
                  "recovery",
                  "actions",
                  "notifications",
                  "workflows",
                  "reporting")),
          new Row(
              "api",
              List.of(
                  "audit",
                  "tenancy",
                  "identity",
                  "connectors",
                  "runner",
                  "servicemap",
                  "intake",
                  "aigateway",
                  "knowledge",
                  "incidents",
                  "investigation",
                  "notifications",
                  "ticketing",
                  "routing",
                  "correlation",
                  "recovery",
                  "diagnostics",
                  "actions",
                  "engine",
                  "workflows",
                  "reporting",
                  "setup")));

  private static final ApplicationModules MODULES = ApplicationModules.of(VigilApplication.class);

  @Test
  @DisplayName("AR-Q-01 modules verify")
  void modulesVerify() {
    assertThatCode(MODULES::verify).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("AR-T-03 module verification runs")
  void moduleVerificationRuns() {
    assertThatCode(() -> ApplicationModules.of(VigilApplication.class).verify())
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("AR-M-01 audit allowed dependencies")
  void auditAllowedDependencies() {
    assertModule("audit");
  }

  @Test
  @DisplayName("AR-M-02 tenancy allowed dependencies")
  void tenancyAllowedDependencies() {
    assertModule("tenancy");
  }

  @Test
  @DisplayName("AR-M-03 identity allowed dependencies")
  void identityAllowedDependencies() {
    assertModule("identity");
  }

  @Test
  @DisplayName("AR-M-04 connectors allowed dependencies")
  void connectorsAllowedDependencies() {
    assertModule("connectors");
  }

  @Test
  @DisplayName("AR-M-05 runner allowed dependencies")
  void runnerAllowedDependencies() {
    assertModule("runner");
  }

  @Test
  @DisplayName("AR-M-06 servicemap allowed dependencies")
  void servicemapAllowedDependencies() {
    assertModule("servicemap");
  }

  @Test
  @DisplayName("AR-M-07 intake allowed dependencies")
  void intakeAllowedDependencies() {
    assertModule("intake");
  }

  @Test
  @DisplayName("AR-M-08 aigateway allowed dependencies")
  void aigatewayAllowedDependencies() {
    assertModule("aigateway");
  }

  @Test
  @DisplayName("AR-M-09 knowledge allowed dependencies")
  void knowledgeAllowedDependencies() {
    assertModule("knowledge");
  }

  @Test
  @DisplayName("AR-M-10 incidents allowed dependencies")
  void incidentsAllowedDependencies() {
    assertModule("incidents");
  }

  @Test
  @DisplayName("AR-M-11 investigation allowed dependencies")
  void investigationAllowedDependencies() {
    assertModule("investigation");
  }

  @Test
  @DisplayName("AR-M-12 notifications allowed dependencies")
  void notificationsAllowedDependencies() {
    assertModule("notifications");
  }

  @Test
  @DisplayName("AR-M-13 ticketing allowed dependencies")
  void ticketingAllowedDependencies() {
    assertModule("ticketing");
  }

  @Test
  @DisplayName("AR-M-14 routing allowed dependencies")
  void routingAllowedDependencies() {
    assertModule("routing");
  }

  @Test
  @DisplayName("AR-M-15 correlation allowed dependencies")
  void correlationAllowedDependencies() {
    assertModule("correlation");
  }

  @Test
  @DisplayName("AR-M-16 recovery allowed dependencies")
  void recoveryAllowedDependencies() {
    assertModule("recovery");
  }

  @Test
  @DisplayName("AR-M-17 diagnostics allowed dependencies")
  void diagnosticsAllowedDependencies() {
    assertModule("diagnostics");
  }

  @Test
  @DisplayName("AR-M-18 actions allowed dependencies")
  void actionsAllowedDependencies() {
    assertModule("actions");
  }

  @Test
  @DisplayName("AR-M-19 engine allowed dependencies")
  void engineAllowedDependencies() {
    assertModule("engine");
  }

  @Test
  @DisplayName("AR-M-20 workflows allowed dependencies")
  void workflowsAllowedDependencies() {
    assertModule("workflows");
  }

  @Test
  @DisplayName("AR-M-21 reporting allowed dependencies")
  void reportingAllowedDependencies() {
    assertModule("reporting");
  }

  @Test
  @DisplayName("AR-M-22 setup allowed dependencies")
  void setupAllowedDependencies() {
    assertModule("setup");
  }

  @Test
  @DisplayName("AR-M-23 api allowed dependencies")
  void apiAllowedDependencies() {
    assertModule("api");
  }

  @Test
  @DisplayName("AR-R-04 every module has one api named interface")
  void everyModuleHasOneApiNamedInterface() {
    for (Row row : TABLE) {
      var module = MODULES.getModuleByName(row.javaPackage()).orElseThrow();
      var explicit = module.getNamedInterfaces().stream().filter(NamedInterface::isNamed).toList();
      assertThat(explicit)
          .as("explicit named interfaces of %s", row.javaPackage())
          .extracting(NamedInterface::getName)
          .containsExactly("api");
      assertThat(
              apiPackageInfo(row.javaPackage())
                  .getAnnotation(org.springframework.modulith.NamedInterface.class)
                  .value())
          .as("named interface declared in vigil.%s.api", row.javaPackage())
          .containsExactly("api");
    }
  }

  @Test
  @DisplayName("AR-R-05 allowed dependencies match the architecture")
  void allowedDependenciesMatchTheArchitecture() {
    Set<String> expected = TABLE.stream().map(Row::javaPackage).collect(Collectors.toSet());
    expected.add("shared");
    Set<String> detected =
        MODULES.stream().map(m -> m.getIdentifier().toString()).collect(Collectors.toSet());
    assertThat(detected).isEqualTo(expected);
    assertThat(TABLE).hasSize(23);
    for (Row row : TABLE) {
      assertThat(declaredDependencies(row.javaPackage()))
          .as("allowed dependencies of %s", row.javaPackage())
          .containsExactlyElementsOf(expectedDeclaration(row));
    }
    assertThatCode(MODULES::verify).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("AR-R-06 shared is open and has no dependencies")
  void sharedIsOpenAndHasNoDependencies() {
    var shared = MODULES.getModuleByName("shared").orElseThrow();
    assertThat(shared.isOpen()).isTrue();
    assertThat(declaredDependencies("shared")).isEmpty();
    assertThat(shared.getBasePackage().getAnnotation(ApplicationModule.class))
        .get()
        .extracting(ApplicationModule::type)
        .isEqualTo(ApplicationModule.Type.OPEN);
    assertThat(MODULES.getSharedModules()).contains(shared);
    assertThat(VigilApplication.class.getAnnotation(Modulithic.class).sharedModules())
        .containsExactly("shared");
    assertThat(
            shared.getNamedInterfaces().stream()
                .filter(NamedInterface::isNamed)
                .map(NamedInterface::getName))
        .isEmpty();
  }

  @Test
  @DisplayName("PM-R-03 dependencies point to earlier modules only")
  void dependenciesPointToEarlierModulesOnly() {
    List<String> earlier = new ArrayList<>();
    for (Row row : TABLE) {
      assertThat(earlier)
          .as("modules before %s", row.javaPackage())
          .containsAll(row.allowedDependencies());
      assertThat(declaredDependencies(row.javaPackage()))
          .as("declared dependencies of %s", row.javaPackage())
          .filteredOn(d -> !d.equals("shared"))
          .allSatisfy(d -> assertThat(earlier).contains(d.replace(" :: api", "")));
      earlier.add(row.javaPackage());
    }
  }

  private static void assertModule(String javaPackage) {
    Row row = TABLE.stream().filter(r -> r.javaPackage().equals(javaPackage)).findFirst().get();
    var module = MODULES.getModuleByName(javaPackage).orElseThrow();
    assertThat(module.getBasePackage().getName()).isEqualTo("vigil." + javaPackage);
    assertThat(module.getNamedInterfaces().getByName("api")).isPresent();
    assertThat(declaredDependencies(javaPackage))
        .containsExactlyElementsOf(expectedDeclaration(row));
  }

  /** The package-info type of the package vigil.{javaPackage}.api. */
  private static Class<?> apiPackageInfo(String javaPackage) {
    try {
      return Class.forName("vigil." + javaPackage + ".api.package-info");
    } catch (ClassNotFoundException e) {
      throw new AssertionError("vigil." + javaPackage + ".api has no package-info.java", e);
    }
  }

  /** The dependencies a module declares, as written in its package-info.java. */
  private static List<String> declaredDependencies(String javaPackage) {
    var module = MODULES.getModuleByName(javaPackage).orElseThrow();
    return List.of(
        module
            .getBasePackage()
            .getAnnotation(ApplicationModule.class)
            .orElseThrow()
            .allowedDependencies());
  }

  /** "shared" first, then each module of the table row as "{package} :: api". */
  private static List<String> expectedDeclaration(Row row) {
    List<String> declaration = new ArrayList<>();
    if (!row.javaPackage().equals("shared")) {
      declaration.add("shared");
    }
    row.allowedDependencies().forEach(d -> declaration.add(d + " :: api"));
    return declaration;
  }
}
