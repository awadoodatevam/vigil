package vigil.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import vigil.connectors.api.ConnectorImport;
import vigil.runner.api.GrantView;
import vigil.runner.api.OperationView;
import vigil.runner.api.PermissionKind;
import vigil.runner.api.RunnerImport;
import vigil.runner.api.RunnerPart;
import vigil.runner.api.RunnerPart.GrantPart;
import vigil.runner.api.RunnerPart.OperationPart;
import vigil.runner.api.RunnerService;
import vigil.shared.Actor;
import vigil.shared.TenantId;
import vigil.shared.VigilError;
import vigil.tenancy.api.TenantService;
import vigil.testsupport.TestSecrets;
import vigil.testsupport.VigilDatabase;
import vigil.testsupport.VigilTestApp;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RunnerImportIT {

  private VigilTestApp app;
  private JdbcClient postgres;
  private RunnerImport runnerImport;
  private RunnerService runner;
  private TenantService tenants;
  private TenantId tenantA;
  private Map<String, UUID> handlesA;
  private int count;

  @BeforeAll
  void start() {
    VigilDatabase database = VigilDatabase.fresh();
    app = VigilTestApp.start(database, TestSecrets.create(database, Map.of()).folder(), List.of());
    app.awaitStarted();
    postgres = database.postgres();
    runnerImport = app.context().getBean(RunnerImport.class);
    runner = app.context().getBean(RunnerService.class);
    tenants = app.context().getBean(TenantService.class);
    tenantA = newTenant();
    handlesA = handles(tenantA);
    runnerImport.importPart(tenantA, RunnerFixture.demoPart(), Instant.now(), handlesA);
  }

  @AfterAll
  void stop() {
    app.close();
  }

  private TenantId newTenant() {
    count++;
    return tenants.createTenant("Runner import " + count, Actor.module("vigil-setup"));
  }

  /** The connectors part of the fixture imported into the tenant; its handles. */
  private Map<String, UUID> handles(TenantId tenant) {
    return app.context()
        .getBean(ConnectorImport.class)
        .importPart(
            tenant,
            RunnerFixture.connectorsPart("nowhere.invalid", 22, "SHA256:x"),
            Instant.now(),
            Map.of());
  }

  private long rows(TenantId tenant) {
    return postgres
            .sql("SELECT count(*) FROM runner.operation WHERE tenant_id = :t")
            .param("t", tenant.value())
            .query(Long.class)
            .single()
        + postgres
            .sql("SELECT count(*) FROM runner.target_access_grant WHERE tenant_id = :t")
            .param("t", tenant.value())
            .query(Long.class)
            .single();
  }

  /** On a new tenant, the part is refused with exactly the problems; then the demo part imports. */
  private void refused(RunnerPart part, String... problems) {
    TenantId tenant = newTenant();
    Map<String, UUID> handles = handles(tenant);
    VigilError error =
        catchThrowableOfType(
            VigilError.class, () -> runnerImport.importPart(tenant, part, Instant.now(), handles));
    assertThat(error).as(List.of(problems).toString()).isNotNull();
    assertThat(error.code()).isEqualTo(VigilError.Code.INVALID);
    assertThat(error.message()).isEqualTo("Part runner refused.");
    assertThat(error.details()).isEqualTo(Map.of("problems", List.of(problems)));
    assertThat(rows(tenant)).isZero();
    runnerImport.importPart(tenant, RunnerFixture.demoPart(), Instant.now(), handles);
    assertThat(rows(tenant)).isEqualTo(7);
  }

  private static RunnerPart operation(int index, UnaryOperator<OperationPart> change) {
    RunnerPart part = RunnerFixture.demoPart();
    List<OperationPart> operations = new ArrayList<>(part.operations());
    operations.set(index, change.apply(operations.get(index)));
    return new RunnerPart(operations, part.grants());
  }

  private static RunnerPart grant(int index, UnaryOperator<GrantPart> change) {
    RunnerPart part = RunnerFixture.demoPart();
    List<GrantPart> grants = new ArrayList<>(part.grants());
    grants.set(index, change.apply(grants.get(index)));
    return new RunnerPart(part.operations(), grants);
  }

  private static OperationPart key(OperationPart o, String key) {
    return new OperationPart(
        key,
        o.name(),
        o.permissionKind(),
        o.commandTemplate(),
        o.parameters(),
        o.outputParser(),
        o.timeout());
  }

  private static OperationPart name(OperationPart o, String name) {
    return new OperationPart(
        o.operationKey(),
        name,
        o.permissionKind(),
        o.commandTemplate(),
        o.parameters(),
        o.outputParser(),
        o.timeout());
  }

  private static OperationPart kind(OperationPart o, String kind) {
    return new OperationPart(
        o.operationKey(),
        o.name(),
        kind,
        o.commandTemplate(),
        o.parameters(),
        o.outputParser(),
        o.timeout());
  }

  private static OperationPart template(OperationPart o, String template) {
    return new OperationPart(
        o.operationKey(),
        o.name(),
        o.permissionKind(),
        template,
        o.parameters(),
        o.outputParser(),
        o.timeout());
  }

  private static OperationPart parameters(OperationPart o, JsonNode parameters) {
    return new OperationPart(
        o.operationKey(),
        o.name(),
        o.permissionKind(),
        o.commandTemplate(),
        parameters,
        o.outputParser(),
        o.timeout());
  }

  private static OperationPart parser(OperationPart o, String parser) {
    return new OperationPart(
        o.operationKey(),
        o.name(),
        o.permissionKind(),
        o.commandTemplate(),
        o.parameters(),
        parser,
        o.timeout());
  }

  private static OperationPart timeout(OperationPart o, String timeout) {
    return new OperationPart(
        o.operationKey(),
        o.name(),
        o.permissionKind(),
        o.commandTemplate(),
        o.parameters(),
        o.outputParser(),
        timeout);
  }

  /** The operation's parameters with definition {@code j} changed. */
  private static OperationPart definition(
      OperationPart o, int j, UnaryOperator<ObjectNode> change) {
    ArrayNode parameters = (ArrayNode) o.parameters().deepCopy();
    parameters.set(j, change.apply((ObjectNode) parameters.get(j)));
    return parameters(o, parameters);
  }

  /** The operation's parameters with a second definition added. */
  private static OperationPart added(OperationPart o, String definitionJson) {
    ArrayNode parameters = (ArrayNode) o.parameters().deepCopy();
    parameters.add(RunnerFixture.JSON.readTree(definitionJson));
    return parameters(o, parameters);
  }

  private static GrantPart target(GrantPart g, String target) {
    return new GrantPart(target, g.connectorInstance(), g.permissionKind(), g.credentialRef());
  }

  private static GrantPart instance(GrantPart g, String instance) {
    return new GrantPart(g.targetKey(), instance, g.permissionKind(), g.credentialRef());
  }

  private static GrantPart kind(GrantPart g, String kind) {
    return new GrantPart(g.targetKey(), g.connectorInstance(), kind, g.credentialRef());
  }

  private static GrantPart credential(GrantPart g, String credential) {
    return new GrantPart(g.targetKey(), g.connectorInstance(), g.permissionKind(), credential);
  }

  @Test
  @Order(1)
  @DisplayName("DM-E-12 the operations of seed part 4 are stored")
  void operations() {
    OperationPart demo = RunnerFixture.demoPart().operations().getFirst();
    OperationView usage = runner.operation(tenantA, "fs_usage").orElseThrow();
    assertThat(usage.operationKey()).isEqualTo("fs_usage");
    assertThat(usage.name()).isEqualTo("Disk usage of one filesystem");
    assertThat(usage.permissionKind()).isEqualTo(PermissionKind.DIAGNOSTIC);
    assertThat(usage.commandTemplate()).isEqualTo(demo.commandTemplate());
    assertThat(RunnerFixture.JSON.readTree(usage.parametersJson()))
        .isEqualTo(
            RunnerFixture.JSON.readTree(
                "[{\"name\":\"mount\",\"value_type\":\"path\",\"required\":true,"
                    + "\"allowed_prefixes\":[\"/data\"]}]"));
    assertThat(usage.outputParser()).isEqualTo("df_row");
    assertThat(usage.timeoutSeconds()).isEqualTo(30);
    assertThat(usage.active()).isTrue();
    OperationView purge = runner.operation(tenantA, "purge_old_archives").orElseThrow();
    assertThat(purge.permissionKind()).isEqualTo(PermissionKind.ACTION);
    assertThat(purge.commandTemplate())
        .isEqualTo(RunnerFixture.demoPart().operations().get(4).commandTemplate())
        .startsWith("set -o pipefail; cd -- {path} && LC_ALL=C find");
    assertThat(purge.outputParser()).isEqualTo("removed_files");
    assertThat(purge.timeoutSeconds()).isEqualTo(120);
    for (String key : List.of("largest_dirs", "recent_growth", "fs_used_pct")) {
      OperationView operation = runner.operation(tenantA, key).orElseThrow();
      assertThat(operation.permissionKind()).isEqualTo(PermissionKind.DIAGNOSTIC);
      assertThat(operation.active()).isTrue();
    }
  }

  @Test
  @Order(2)
  @DisplayName("DM-E-13 the target access grants of seed part 4 are stored")
  void grants() {
    List<GrantView> grants = runner.grants(tenantA);
    assertThat(grants).hasSize(2);
    assertThat(grants)
        .extracting(GrantView::targetKey, GrantView::permissionKind)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("lab-db-01", PermissionKind.ACTION),
            org.assertj.core.groups.Tuple.tuple("lab-db-01", PermissionKind.DIAGNOSTIC));
    for (GrantView grant : grants) {
      assertThat(grant.connectorInstanceId()).isEqualTo(handlesA.get("linux-lab-db-01"));
      assertThat(grant.active()).isTrue();
    }
    assertThat(grants.get(0).credentialRefId()).isEqualTo(handlesA.get("cred-lab-db-01-action"));
    assertThat(grants.get(1).credentialRefId()).isEqualTo(handlesA.get("cred-lab-db-01-diag"));
  }

  @Test
  @Order(3)
  @DisplayName("DD-R-07 timeout_s stored as timeout in seconds; every imported record active")
  void storedForms() {
    assertThat(runner.operation(tenantA, "fs_usage").orElseThrow().timeoutSeconds()).isEqualTo(30);
    assertThat(runner.operation(tenantA, "purge_old_archives").orElseThrow().timeoutSeconds())
        .isEqualTo(120);
    for (String table : List.of("operation", "target_access_grant")) {
      assertThat(
              postgres
                  .sql("SELECT bool_and(active) FROM runner." + table + " WHERE tenant_id = :t")
                  .param("t", tenantA.value())
                  .query(Boolean.class)
                  .single())
          .isTrue();
    }
  }

  @Test
  @Order(4)
  @DisplayName("DM-E13-R2 a second grant for a target and permission kind is refused")
  void secondGrant() {
    RunnerPart demo = RunnerFixture.demoPart();
    List<GrantPart> grants = new ArrayList<>(demo.grants());
    grants.add(demo.grants().getFirst());
    refused(
        new RunnerPart(demo.operations(), grants),
        "target_access_grants[2]: a second grant for lab-db-01 diagnostic");
  }

  @Test
  @Order(5)
  @DisplayName("DM-R-57 part 4 is checked and refused as a whole")
  void refusals() {
    TenantId again = newTenant();
    Map<String, UUID> handles = handles(again);
    runnerImport.importPart(again, RunnerFixture.demoPart(), Instant.now(), handles);
    VigilError second =
        catchThrowableOfType(
            VigilError.class,
            () -> runnerImport.importPart(again, RunnerFixture.demoPart(), Instant.now(), handles));
    assertThat(second.details())
        .isEqualTo(Map.of("problems", List.of("runner records already exist")));
    assertThat(rows(again)).isEqualTo(7);
    String o0 = "operations[0].";
    String d0 = "operations[0].parameters[0]";
    String o1d1 = "operations[1].parameters[1]";
    String o3d1 = "operations[3].parameters[1]";
    refused(operation(0, o -> key(o, "fs usage")), o0 + "operation_key: not a key");
    refused(
        operation(1, o -> key(o, "fs_usage")), "operations[1].operation_key: used more than once");
    refused(operation(0, o -> name(o, "")), o0 + "name: 1 to 100 characters");
    refused(operation(0, o -> name(o, "n".repeat(101))), o0 + "name: 1 to 100 characters");
    refused(operation(0, o -> kind(o, "read")), o0 + "permission_kind: one of diagnostic, action");
    refused(
        operation(0, o -> template(o, "t".repeat(2001))),
        o0 + "command_template: 1 to 2000 characters");
    refused(operation(0, o -> parameters(o, null)), o0 + "parameters: not a list");
    refused(
        operation(0, o -> parameters(o, RunnerFixture.JSON.readTree("[5]"))),
        d0 + ": not a mapping");
    refused(
        operation(0, o -> definition(o, 0, d -> d.put("default", "/data"))),
        d0 + ": unknown field default");
    refused(
        operation(1, o -> definition(o, 1, d -> d.put("name", "path"))),
        o1d1 + ".name: used more than once");
    refused(
        operation(0, o -> definition(o, 0, d -> d.put("value_type", "file"))),
        d0 + ".value_type: one of integer, decimal, text, path, duration, enum, boolean");
    refused(
        operation(0, o -> definition(o, 0, d -> d.put("required", "yes"))),
        d0 + ".required: true or false");
    refused(
        operation(0, o -> definition(o, 0, d -> d.put("min", 1))),
        d0 + ".min: not allowed for path");
    refused(
        operation(1, o -> definition(o, 1, d -> d.put("min", 2.5))),
        o1d1 + ".min: not a whole number");
    refused(
        operation(1, o -> definition(o, 1, d -> d.put("min", 12))), o1d1 + ".max: less than min");
    refused(
        operation(
            0,
            o ->
                definition(
                    o,
                    0,
                    d -> {
                      d.remove("allowed_prefixes");
                      return d;
                    })),
        d0 + ".allowed_prefixes: missing");
    refused(
        operation(0, o -> definition(o, 0, d -> d.set("allowed_prefixes", array("[]")))),
        d0 + ".allowed_prefixes: at least one path");
    for (String prefix : List.of("data", "/data/../etc", "/data/")) {
      refused(
          operation(
              0,
              o -> definition(o, 0, d -> d.set("allowed_prefixes", array("[\"" + prefix + "\"]")))),
          d0 + ".allowed_prefixes[0]: not an absolute path");
    }
    refused(
        operation(
            3, o -> added(o, "{\"name\":\"unit\",\"value_type\":\"enum\",\"required\":false}")),
        o3d1 + ".allowed_values: missing");
    refused(
        operation(
            3,
            o ->
                added(
                    o,
                    "{\"name\":\"unit\",\"value_type\":\"enum\",\"required\":false,"
                        + "\"allowed_values\":[]}")),
        o3d1 + ".allowed_values: at least one value");
    refused(
        operation(
            3,
            o ->
                added(
                    o,
                    "{\"name\":\"unit\",\"value_type\":\"enum\",\"required\":false,"
                        + "\"allowed_values\":[\"a\",\"a\"]}")),
        o3d1 + ".allowed_values[1]: used more than once");
    refused(
        operation(
            3,
            o ->
                added(
                    o,
                    "{\"name\":\"unit\",\"value_type\":\"enum\",\"required\":false,"
                        + "\"allowed_values\":[5]}")),
        o3d1 + ".allowed_values[0]: not a text");
    refused(
        operation(
            3,
            o ->
                added(
                    o,
                    "{\"name\":\"note\",\"value_type\":\"text\",\"required\":false,"
                        + "\"max_length\":0}")),
        o3d1 + ".max_length: a whole number of at least 1");
    refused(
        operation(
            3,
            o ->
                added(
                    o,
                    "{\"name\":\"wait\",\"value_type\":\"duration\",\"required\":false,"
                        + "\"min\":-1}")),
        o3d1 + ".min: at least 0");
    refused(
        operation(
            3,
            o ->
                added(
                    o,
                    "{\"name\":\"ratio\",\"value_type\":\"decimal\",\"required\":false,"
                        + "\"min\":\"x\"}")),
        o3d1 + ".min: not a number");
    refused(
        operation(
            3,
            o ->
                added(
                    o,
                    "{\"name\":\"flag\",\"value_type\":\"boolean\",\"required\":false,"
                        + "\"max_length\":3}")),
        o3d1 + ".max_length: not allowed for boolean");
    refused(
        operation(0, o -> parser(o, "df")),
        o0 + "output_parser: one of df_row, du_top, count_bytes, single_number, removed_files");
    for (String timeout : List.of("0", "3601", "30s")) {
      refused(operation(0, o -> timeout(o, timeout)), o0 + "timeout_s: 1 to 3600 seconds");
    }
    String g0 = "target_access_grants[0].";
    String g1 = "target_access_grants[1].";
    refused(grant(0, g -> target(g, "lab db")), g0 + "target_key: not a key");
    refused(
        grant(0, g -> instance(g, "linux-nowhere")),
        g0 + "connector_instance: not a connector instance of part 3");
    refused(
        grant(0, g -> instance(g, "zabbix-lab")),
        g0 + "connector_instance: not a linux_server instance");
    refused(grant(1, g -> kind(g, "write")), g1 + "permission_kind: one of diagnostic, action");
    refused(
        grant(1, g -> credential(g, "cred-nowhere")),
        g1 + "credential_ref: not a credential reference of part 3");
    refused(
        grant(1, g -> credential(g, "cred-lab-db-01-diag")),
        g1 + "credential_ref: purpose must be target_action");
    refused(
        grant(0, g -> credential(g, "cred-zabbix-api")),
        g0 + "credential_ref: purpose must be target_diagnostic");
    RunnerPart together = operation(0, o -> name(o, ""));
    List<OperationPart> operations = new ArrayList<>(together.operations());
    operations.set(4, timeout(operations.get(4), "0"));
    List<GrantPart> grants = new ArrayList<>(together.grants());
    grants.set(1, credential(grants.get(1), "cred-lab-db-01-diag"));
    refused(
        new RunnerPart(operations, grants),
        o0 + "name: 1 to 100 characters",
        "operations[4].timeout_s: 1 to 3600 seconds",
        g1 + "credential_ref: purpose must be target_action");
  }

  private static ArrayNode array(String json) {
    return (ArrayNode) RunnerFixture.JSON.readTree(json);
  }
}
