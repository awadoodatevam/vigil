package vigil.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import vigil.diagnostics.api.DiagnosticDefinitionView;
import vigil.diagnostics.api.DiagnosticsImport;
import vigil.diagnostics.api.DiagnosticsPart;
import vigil.diagnostics.api.DiagnosticsPart.DefinitionPart;
import vigil.diagnostics.api.DiagnosticsService;
import vigil.runner.RunnerFixture;
import vigil.runner.api.PermissionKind;
import vigil.runner.api.RunnerPart.OperationPart;
import vigil.shared.Actor;
import vigil.shared.TenantId;
import vigil.shared.VigilError;
import vigil.tenancy.api.TenantService;
import vigil.testsupport.TestSecrets;
import vigil.testsupport.VigilDatabase;
import vigil.testsupport.VigilTestApp;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DiagnosticsImportIT {

  private VigilTestApp app;
  private JdbcClient postgres;
  private DiagnosticsImport diagnosticsImport;
  private DiagnosticsService diagnostics;
  private TenantService tenants;
  private TenantId tenantA;
  private TenantId tenantB;
  private int count;

  @BeforeAll
  void start() {
    VigilDatabase database = VigilDatabase.fresh();
    app = VigilTestApp.start(database, TestSecrets.create(database, Map.of()).folder(), List.of());
    app.awaitStarted();
    postgres = database.postgres();
    diagnosticsImport = app.context().getBean(DiagnosticsImport.class);
    diagnostics = app.context().getBean(DiagnosticsService.class);
    tenants = app.context().getBean(TenantService.class);
    tenantA = newTenant();
    tenantB = newTenant();
    diagnosticsImport.importPart(tenantA, DiagnosticsFixture.demoPart(), Instant.now(), Map.of());
  }

  @AfterAll
  void stop() {
    app.close();
  }

  private TenantId newTenant() {
    count++;
    return tenants.createTenant("Diagnostics import " + count, Actor.module("vigil-setup"));
  }

  private long rows(TenantId tenant) {
    return postgres
        .sql("SELECT count(*) FROM diagnostics.diagnostic_definition WHERE tenant_id = :t")
        .param("t", tenant.value())
        .query(Long.class)
        .single();
  }

  private void refused(TenantId tenant, DiagnosticsPart part, String... problems) {
    long before = rows(tenant);
    VigilError error =
        catchThrowableOfType(
            VigilError.class,
            () -> diagnosticsImport.importPart(tenant, part, Instant.now(), Map.of()));
    assertThat(error).isNotNull();
    assertThat(error.code()).isEqualTo(VigilError.Code.INVALID);
    assertThat(error.message()).isEqualTo("Part diagnostics refused.");
    assertThat(error.details()).isEqualTo(Map.of("problems", List.of(problems)));
    assertThat(rows(tenant)).isEqualTo(before);
  }

  private void changed(UnaryOperator<List<DefinitionPart>> change, String... problems) {
    List<DefinitionPart> definitions =
        change.apply(new ArrayList<>(DiagnosticsFixture.demoPart().definitions()));
    refused(newTenant(), new DiagnosticsPart(definitions), problems);
  }

  private static List<DefinitionPart> first(
      List<DefinitionPart> definitions, UnaryOperator<DefinitionPart> change) {
    definitions.set(0, change.apply(definitions.getFirst()));
    return definitions;
  }

  private static JsonNode tree(String json) {
    return RunnerFixture.JSON.readTree(json);
  }

  @Test
  @Order(1)
  @DisplayName("DM-E-53 the diagnostic definitions of seed part 12 are stored")
  void definitions() {
    List<DiagnosticDefinitionView> stored = diagnostics.definitions(tenantA);
    assertThat(stored)
        .extracting(DiagnosticDefinitionView::diagnosticKey)
        .containsExactly("disk_growth", "disk_usage", "largest_folders");
    DiagnosticDefinitionView usage = diagnostics.definition(tenantA, "disk_usage").orElseThrow();
    assertThat(usage.name()).isEqualTo("Disk usage of /data");
    assertThat(usage.operationKey()).isEqualTo("fs_usage");
    assertThat(tree(usage.parameterValuesJson())).isEqualTo(tree("{\"mount\":\"/data\"}"));
    assertThat(usage.summaryTemplate())
        .isEqualTo(
            "/data is {used_pct}% full ({used_gib} of {size_gib} GiB used, {avail_gib} GiB free)");
    assertThat(usage.active()).isTrue();
    assertThat(
            tree(
                diagnostics
                    .definition(tenantA, "largest_folders")
                    .orElseThrow()
                    .parameterValuesJson()))
        .isEqualTo(tree("{\"path\":\"/data\",\"count\":4}"));
    assertThat(
            tree(
                diagnostics.definition(tenantA, "disk_growth").orElseThrow().parameterValuesJson()))
        .isEqualTo(tree("{\"path\":\"/data\",\"minutes\":15}"));
    assertThat(diagnostics.definitions(tenantB)).isEmpty();
    assertThat(diagnostics.definition(tenantB, "disk_usage")).isEmpty();
  }

  @Test
  @Order(2)
  @DisplayName("PM-M13-01 the demo set of three definitions, each on a diagnostic operation")
  void demoSet() {
    List<OperationPart> operations = RunnerFixture.demoPart().operations();
    assertThat(diagnostics.definitions(tenantA)).hasSize(3);
    for (DiagnosticDefinitionView definition : diagnostics.definitions(tenantA)) {
      OperationPart operation =
          operations.stream()
              .filter(o -> o.operationKey().equals(definition.operationKey()))
              .findFirst()
              .orElseThrow();
      assertThat(PermissionKind.fromKey(operation.permissionKind()))
          .contains(PermissionKind.DIAGNOSTIC);
    }
    assertThat(diagnostics.definitions(tenantA))
        .extracting(DiagnosticDefinitionView::name)
        .containsExactly(
            "Data written under /data, last 15 minutes",
            "Disk usage of /data",
            "Largest folders under /data");
  }

  @Test
  @Order(3)
  @DisplayName("DM-R-57 part 12 is checked and refused as a whole")
  void refusals() {
    refused(tenantA, DiagnosticsFixture.demoPart(), "diagnostic records already exist");
    String d0 = "diagnostic_definitions[0].";
    changed(
        d -> first(d, f -> definition(f, "disk usage", null, null, null, null)),
        d0 + "diagnostic_key: not a key");
    changed(
        d -> {
          DefinitionPart f = d.get(1);
          d.set(1, definition(f, "disk_usage", null, null, null, null));
          return d;
        },
        "diagnostic_definitions[1].diagnostic_key: used more than once");
    changed(
        d -> first(d, f -> definition(f, null, "", null, null, null)),
        d0 + "name: 1 to 100 characters");
    changed(
        d -> first(d, f -> definition(f, null, null, "fs usage", null, null)),
        d0 + "operation_key: not a key");
    for (JsonNode values : Arrays.asList(null, tree("[]"))) {
      changed(
          d ->
              first(
                  d,
                  f ->
                      new DefinitionPart(
                          f.diagnosticKey(),
                          f.name(),
                          f.operationKey(),
                          values,
                          f.summaryTemplate())),
          d0 + "parameter_values: not a mapping");
    }
    changed(
        d -> first(d, f -> definition(f, null, null, null, null, "s".repeat(201))),
        d0 + "summary_template: 1 to 200 characters");
  }

  private static DefinitionPart definition(
      DefinitionPart f,
      String key,
      String name,
      String operation,
      JsonNode values,
      String template) {
    return new DefinitionPart(
        key == null ? f.diagnosticKey() : key,
        name == null ? f.name() : name,
        operation == null ? f.operationKey() : operation,
        values == null ? f.parameterValues() : values,
        template == null ? f.summaryTemplate() : template);
  }
}
