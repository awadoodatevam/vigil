package vigil.workflows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
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
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import vigil.engine.TestContents;
import vigil.shared.Actor;
import vigil.shared.TenantId;
import vigil.shared.VigilError;
import vigil.tenancy.api.TenantService;
import vigil.testsupport.VigilDatabase;
import vigil.testsupport.VigilTestApp;
import vigil.workflows.api.VersionOrigin;
import vigil.workflows.api.WorkflowImport;
import vigil.workflows.api.WorkflowPart;
import vigil.workflows.api.WorkflowService;
import vigil.workflows.api.WorkflowVersionView;
import vigil.workflows.api.WorkflowView;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WorkflowImportIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String S = "workflow.version_1.steps";
  private static final String C = "workflow.version_1.connections";
  private static final String BRANCH = "workflow.version_1.steps[1].parameters.branches[0].";
  private static final String P1_OUTPUT =
      "workflow.version_1.connections[1].from_output: not an output of s-severity";

  private VigilTestApp app;
  private JdbcClient postgres;
  private WorkflowImport workflowImport;
  private WorkflowService workflows;
  private TenantService tenants;
  private TenantId tenant;
  private int count;

  @BeforeAll
  void start() {
    VigilDatabase database = VigilDatabase.fresh();
    app = VigilTestApp.start(database);
    app.awaitStarted();
    postgres = database.postgres();
    workflowImport = app.context().getBean(WorkflowImport.class);
    workflows = app.context().getBean(WorkflowService.class);
    tenants = app.context().getBean(TenantService.class);
  }

  @AfterAll
  void stop() {
    app.close();
  }

  private TenantId newTenant() {
    count++;
    return tenants.createTenant("Workflow import " + count, Actor.module("vigil-setup"));
  }

  private void importPart(TenantId into, WorkflowPart part) {
    workflowImport.importPart(into, part, Instant.now(), Map.of());
  }

  private long rows(String table) {
    return postgres.sql("SELECT count(*) FROM workflows." + table).query(Long.class).single();
  }

  private static JsonNode tree(String json) {
    return JSON.readTree(json);
  }

  /** The part is refused with exactly {@code problems}, and nothing is stored. */
  private void refused(TenantId into, WorkflowPart part, String... problems) {
    long workflowRows = rows("workflow");
    long versionRows = rows("workflow_version");
    VigilError error = catchThrowableOfType(VigilError.class, () -> importPart(into, part));
    assertThat(error).isNotNull();
    assertThat(error.code()).isEqualTo(VigilError.Code.INVALID);
    assertThat(error.message()).isEqualTo("Part workflows refused.");
    assertThat(error.details()).isEqualTo(Map.of("problems", List.of(problems)));
    assertThat(rows("workflow")).isEqualTo(workflowRows);
    assertThat(rows("workflow_version")).isEqualTo(versionRows);
  }

  /** The demo part with one change, refused on a new tenant with exactly {@code problems}. */
  private void changed(Consumer<ObjectNode> change, String... problems) {
    ObjectNode workflow = (ObjectNode) tree(WorkflowFixture.demoPart().workflowJson());
    change.accept(workflow);
    refused(newTenant(), new WorkflowPart(JSON.writeValueAsString(workflow)), problems);
  }

  private static ObjectNode version(ObjectNode workflow) {
    return (ObjectNode) workflow.get("version_1");
  }

  private static ArrayNode steps(ObjectNode workflow) {
    return (ArrayNode) version(workflow).get("steps");
  }

  private static ArrayNode connections(ObjectNode workflow) {
    return (ArrayNode) version(workflow).get("connections");
  }

  private static ObjectNode step(ObjectNode workflow, String stepId) {
    for (JsonNode step : steps(workflow)) {
      if (step.get("step_id").stringValue().equals(stepId)) {
        return (ObjectNode) step;
      }
    }
    throw new IllegalArgumentException(stepId);
  }

  private static ObjectNode parameters(ObjectNode workflow, String stepId) {
    return (ObjectNode) step(workflow, stepId).get("parameters");
  }

  private static ArrayNode branches(ObjectNode workflow) {
    return (ArrayNode) parameters(workflow, "s-severity").get("branches");
  }

  private static ObjectNode branch(ObjectNode workflow) {
    return (ObjectNode) branches(workflow).get(0);
  }

  private static ArrayNode comparisons(ObjectNode workflow) {
    return (ArrayNode) branch(workflow).get("comparisons");
  }

  private static ObjectNode comparison(ObjectNode workflow) {
    return (ObjectNode) comparisons(workflow).get(0);
  }

  private static ObjectNode connection(String from, String output, String to) {
    ObjectNode connection = JSON.createObjectNode();
    connection.put("from_step_id", from);
    connection.put("from_output", output);
    connection.put("to_step_id", to);
    return connection;
  }

  private static JsonNode stepParameters(String content, String stepId) {
    for (JsonNode step : tree(content).get("steps")) {
      if (step.get("step_id").stringValue().equals(stepId)) {
        return step.get("parameters");
      }
    }
    throw new IllegalArgumentException(stepId);
  }

  private WorkflowVersionView version1;

  @Test
  @Order(1)
  @DisplayName("DM-E-45 the workflow Incident handling with version 1 as its active version")
  void workflow() {
    tenant = newTenant();
    assertThat(
            workflowImport.importPart(tenant, WorkflowFixture.demoPart(), Instant.now(), Map.of()))
        .isEmpty();
    WorkflowView workflow = workflows.workflow(tenant).orElseThrow();
    assertThat(workflow.name()).isEqualTo("Incident handling");
    version1 = workflows.version(tenant, workflow.activeVersionId()).orElseThrow();
    assertThat(version1.number()).isEqualTo(1);
    assertThat(version1.workflowId()).isEqualTo(workflow.id());
    assertThat(workflows.activeVersion(tenant)).contains(version1);
    assertThat(workflows.workflow(newTenant())).isEmpty();
  }

  @Test
  @Order(2)
  @DisplayName("DM-E-47 version 1: number 1, origin seed, published by vigil-setup, no changes")
  void version1() {
    assertThat(version1.number()).isEqualTo(1);
    assertThat(version1.origin()).isEqualTo(VersionOrigin.SEED);
    assertThat(version1.originRef()).isNull();
    assertThat(version1.publishedBy()).isEqualTo(Actor.module("vigil-setup"));
    assertThat(version1.publishedAt()).isNotNull();
    assertThat(tree(version1.changeListJson())).isEqualTo(tree("[]"));
  }

  @Test
  @Order(3)
  @DisplayName("PM-M11-01 version 1 stored as data: steps, connections and parameters")
  void content() {
    String content = version1.contentJson();
    assertThat(tree(content)).isEqualTo(tree(TestContents.version1()));
    assertThat(tree(content).get("steps")).hasSize(19);
    assertThat(tree(content).get("connections")).hasSize(31);
    assertThat(stepParameters(content, "s-approve"))
        .isEqualTo(tree("{\"eligible_teams\":[\"db-ops\"],\"expiry\":900}"));
    assertThat(stepParameters(content, "s-wait-ack"))
        .isEqualTo(tree("{\"mode\":\"wait_for_acknowledgement\",\"timer\":600}"));
    assertThat(stepParameters(content, "s-notify"))
        .isEqualTo(
            tree(
                "{\"recipients\":[\"owner_team\",\"informed_parties\",\"named_teams\"],"
                    + "\"named_teams\":[\"noc-l1\"]}"));
    assertThat(stepParameters(content, "s-severity").get("branches").get(0))
        .isEqualTo(
            tree(
                "{\"label\":\"p1\",\"comparisons\":"
                    + "[{\"field\":\"severity\",\"operator\":\"equals\",\"value\":\"P1\"}]}"));
  }

  @Test
  @Order(4)
  @DisplayName("DM-E45-R1 a second workflow for the tenant is refused")
  void exactlyOne() {
    refused(tenant, WorkflowFixture.demoPart(), "workflow already exists");
    assertThat(workflows.activeVersion(tenant)).contains(version1);
  }

  @Test
  @Order(5)
  @DisplayName("DM-R-57 part 16 is checked and refused as a whole")
  void refusals() {
    changed(w -> w.remove("name"), "workflow.name: 1 to 100 characters");
    changed(w -> version(w).put("origin", "draft"), "workflow.version_1.origin: seed");
    changed(w -> version(w).putArray("steps"), "workflow.version_1.steps: at least one step");
    changed(
        w -> step(w, "s-ticket-2").put("step_id", "bad key"),
        S + "[15].step_id: not a key",
        C + "[23].to_step_id: not a step",
        C + "[24].to_step_id: not a step",
        C + "[25].from_step_id: not a step",
        C + "[26].from_step_id: not a step");
    changed(w -> step(w, "s-trigger").put("type", "script"), S + "[0].type: not a step type");
    changed(
        w -> step(w, "s-trigger").put("label", "x".repeat(61)),
        S + "[0].label: 1 to 60 characters");
    changed(
        w -> step(w, "s-trigger").putObject("position").put("x", "a").put("y", 1),
        S + "[0].position: x and y must be integers");
    changed(
        w -> parameters(w, "s-approve").put("expiry_s", 59),
        S + "[9].parameters.expiry_s: 60 to 86400 seconds");
    changed(
        w -> {
          ObjectNode parameters = parameters(w, "s-approve");
          parameters.set("expiry", parameters.remove("expiry_s"));
        },
        S + "[9].parameters.expiry: not a parameter of approval",
        S + "[9].parameters.expiry_s: missing");
    changed(
        w -> parameters(w, "s-wait-ack").remove("timer_s"), S + "[7].parameters.timer_s: missing");
    changed(
        w -> parameters(w, "s-notify").putArray("recipients").add("owner_team").add("owner_team"),
        S + "[6].parameters.recipients[1]: used more than once");
    changed(
        w -> parameters(w, "s-notify").remove("named_teams"),
        S + "[6].parameters.named_teams: missing");
    changed(
        w -> parameters(w, "s-investigate").put("task", "SL-T-4"),
        S + "[3].parameters.task: one of SL-T-1, SL-T-2, SL-T-3");
    changed(
        w -> branch(w).put("label", "otherwise"),
        BRANCH + "label: otherwise is reserved",
        P1_OUTPUT);
    changed(
        w -> comparison(w).put("field", "title"),
        BRANCH + "comparisons[0].field: not a condition field");
    changed(
        w ->
            comparison(w)
                .put("field", "owner_team")
                .put("operator", "at_least")
                .put("value", "noc-l1"),
        BRANCH + "comparisons[0].operator: not an operator of owner_team");
    changed(
        w -> {
          comparison(w).put("operator", "in");
          comparison(w).putArray("value");
        },
        BRANCH + "comparisons[0].value: at least one value");
    changed(
        w -> comparison(w).putArray("value").add("P1").add("P2"),
        BRANCH + "comparisons[0].value: one value");
    changed(
        w -> comparison(w).put("value", "P5"),
        BRANCH + "comparisons[0].value: not a value of severity");
    changed(
        w -> connections(w).add(connection("s-action", "approved", "s-recover")),
        C + "[31].from_output: not an output of s-action");
    changed(
        w -> connections(w).add(connection("s-trigger", "next", "s-severity")),
        C + "[31]: a second connection from s-trigger next");
    changed(
        w -> {
          step(w, "s-escalate").put("type", "trigger");
          step(w, "s-escalate")
              .putObject("parameters")
              .put("event", "incident_created_or_reopened");
        },
        "workflow.version_1: exactly one trigger step");
    refused(newTenant(), new WorkflowPart(null), "workflow: missing");
    changed(w -> w.remove("version_1"), "workflow.version_1: missing");
    changed(
        w -> steps(w).set(0, JSON.getNodeFactory().numberNode(5)),
        S + "[0]: not a mapping",
        C + "[0].from_step_id: not a step");
    changed(
        w -> step(w, "s-escalate").put("step_id", "s-resolve"),
        S + "[13].step_id: used more than once",
        C + "[17].to_step_id: not a step",
        C + "[18].to_step_id: not a step",
        C + "[22].to_step_id: not a step",
        C + "[30].to_step_id: not a step");
    changed(w -> step(w, "s-action").putArray("parameters"), S + "[10].parameters: not a mapping");
    changed(w -> version(w).putObject("connections"), "workflow.version_1.connections: not a list");
    changed(w -> connections(w).add(5), C + "[31]: not a mapping");
    changed(
        w -> parameters(w, "s-approve").put("eligible_teams", "db-ops"),
        S + "[9].parameters.eligible_teams: not a list");
    changed(
        w -> parameters(w, "s-approve").putArray("eligible_teams").add("db ops"),
        S + "[9].parameters.eligible_teams[0]: not a key");
    changed(
        w -> parameters(w, "s-approve").putArray("eligible_teams"),
        S + "[9].parameters.eligible_teams: at least 1");
    changed(
        w -> parameters(w, "s-notify").putArray("recipients").add("owner_team").add("everyone"),
        S + "[6].parameters.recipients[1]: one of owner_team, informed_parties, named_teams");
    changed(
        w -> parameters(w, "s-recover").put("max_wait_s", "5m"),
        S + "[11].parameters.max_wait_s: not a whole number of seconds");
    changed(
        w -> parameters(w, "s-severity").put("branches", "p1"),
        S + "[1].parameters.branches: not a list",
        P1_OUTPUT);
    changed(
        w -> parameters(w, "s-severity").putArray("branches"),
        S + "[1].parameters.branches: at least 1",
        P1_OUTPUT);
    changed(
        w -> branches(w).set(0, JSON.getNodeFactory().numberNode(5)),
        S + "[1].parameters.branches[0]: not a mapping",
        P1_OUTPUT);
    changed(w -> branch(w).put("label", "p 1"), BRANCH + "label: not a key", P1_OUTPUT);
    changed(
        w -> branches(w).add(branch(w).deepCopy()),
        S + "[1].parameters.branches[1].label: used more than once");
    changed(
        w -> branch(w).putArray("comparisons"), BRANCH + "comparisons: at least one comparison");
    changed(
        w -> comparisons(w).set(0, JSON.getNodeFactory().numberNode(5)),
        BRANCH + "comparisons[0]: not a mapping");
    changed(
        w ->
            comparison(w)
                .put("field", "owner_team")
                .put("operator", "equals")
                .put("value", "noc l1"),
        BRANCH + "comparisons[0].value: not a key");
    changed(
        w ->
            comparison(w)
                .put("field", "reopen_count")
                .put("operator", "equals")
                .put("value", "one"),
        BRANCH + "comparisons[0].value: not an integer");
    changed(
        w -> comparison(w).put("field", "acknowledged").put("operator", "is").put("value", "yes"),
        BRANCH + "comparisons[0].value: true or false");
    changed(
        w -> {
          version(w).put("origin", "draft");
          parameters(w, "s-approve").put("expiry_s", 59);
        },
        "workflow.version_1.origin: seed",
        S + "[9].parameters.expiry_s: 60 to 86400 seconds");
  }
}
