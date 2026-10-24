package vigil.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import vigil.incidents.api.AffectedServiceCopy;
import vigil.incidents.api.AiStatus;
import vigil.incidents.api.HypothesisCopy;
import vigil.incidents.api.IncidentState;
import vigil.incidents.api.IncidentView;
import vigil.incidents.api.Severity;

class ConditionsTest {

  private static final Instant T = Instant.parse("2026-10-24T10:00:00Z");

  /** The incident fields a test sets; {@link #view()} fills the others. */
  private static final class Incident {
    Severity severity = Severity.P2;
    String ownerTeam = "db-ops";
    List<String> informed = List.of("noc-l1", "vas-ops");
    HypothesisCopy hypothesis =
        new HypothesisCopy(1, "db-subscriber", "Subscriber DB", "database", "critical");
    List<AffectedServiceCopy> services =
        List.of(
            new AffectedServiceCopy("svc-a", "Service A", "high", List.of("db-subscriber")),
            new AffectedServiceCopy("svc-b", "Service B", null, List.of("db-subscriber")),
            new AffectedServiceCopy("svc-c", "Service C", "medium", List.of("db-subscriber")));
    Instant acknowledgedAt = T;
    AiStatus aiStatus = AiStatus.AVAILABLE;

    IncidentView view() {
      return new IncidentView(
          UUID.randomUUID(),
          1,
          1L,
          "Title",
          IncidentState.OPEN,
          T,
          T,
          T,
          T,
          3,
          List.of("db_errors", "disk_full"),
          List.of("zabbix"),
          hypothesis == null ? null : hypothesis.componentKey(),
          hypothesis,
          null,
          services,
          severity,
          ownerTeam,
          informed,
          null,
          true,
          2,
          acknowledgedAt,
          null,
          aiStatus,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          1,
          T,
          T);
    }
  }

  private static JsonNode comparison(String field, String operator, String value) {
    return RunContent.JSON.readTree(
        "{\"field\": \""
            + field
            + "\", \"operator\": \""
            + operator
            + "\", \"value\": "
            + value
            + "}");
  }

  private static boolean compare(IncidentView incident, String field, String op, String value) {
    return Conditions.compare(incident, comparison(field, op, value));
  }

  @Test
  @DisplayName("DM-R-25 each condition field reads its incident value")
  void fields() {
    IncidentView incident = new Incident().view();
    assertThat(compare(incident, "severity", "equals", "\"P2\"")).isTrue();
    assertThat(compare(incident, "owner_team", "equals", "\"db-ops\"")).isTrue();
    assertThat(compare(incident, "informed_teams", "contains", "\"vas-ops\"")).isTrue();
    assertThat(compare(incident, "hypothesis_component", "equals", "\"db-subscriber\"")).isTrue();
    assertThat(compare(incident, "hypothesis_component_kind", "equals", "\"database\"")).isTrue();
    assertThat(compare(incident, "hypothesis_criticality", "equals", "\"critical\"")).isTrue();
    assertThat(compare(incident, "affected_services", "contains", "\"svc-b\"")).isTrue();
    assertThat(compare(incident, "affected_service_count", "equals", "3")).isTrue();
    assertThat(compare(incident, "highest_service_criticality", "equals", "\"high\"")).isTrue();
    Incident lower = new Incident();
    lower.services =
        List.of(
            new AffectedServiceCopy("svc-b", "Service B", null, List.of("db-subscriber")),
            new AffectedServiceCopy("svc-c", "Service C", "medium", List.of("db-subscriber")));
    assertThat(compare(lower.view(), "highest_service_criticality", "equals", "\"medium\""))
        .isTrue();
    assertThat(compare(incident, "alert_count", "equals", "3")).isTrue();
    assertThat(compare(incident, "alert_types", "contains", "\"disk_full\"")).isTrue();
    assertThat(compare(incident, "alert_sources", "contains", "\"zabbix\"")).isTrue();
    assertThat(compare(incident, "acknowledged", "is", "true")).isTrue();
    Incident unacknowledged = new Incident();
    unacknowledged.acknowledgedAt = null;
    assertThat(compare(unacknowledged.view(), "acknowledged", "is", "false")).isTrue();
    assertThat(compare(unacknowledged.view(), "acknowledged", "is", "true")).isFalse();
    assertThat(compare(incident, "escalation_level", "equals", "2")).isTrue();
    assertThat(compare(incident, "ai_investigation_available", "is", "true")).isTrue();
    Incident notRun = new Incident();
    notRun.aiStatus = AiStatus.NOT_RUN;
    assertThat(compare(notRun.view(), "ai_investigation_available", "is", "false")).isTrue();
    assertThat(compare(incident, "reopen_count", "equals", "1")).isTrue();
  }

  @Test
  @DisplayName("DM-R-26 the operators of each value type; a field with no value is false")
  void operators() {
    IncidentView incident = new Incident().view();
    // Ordered enum: severity P2.
    assertThat(compare(incident, "severity", "equals", "\"P2\"")).isTrue();
    assertThat(compare(incident, "severity", "equals", "\"P1\"")).isFalse();
    assertThat(compare(incident, "severity", "not_equals", "\"P1\"")).isTrue();
    assertThat(compare(incident, "severity", "not_equals", "\"P2\"")).isFalse();
    assertThat(compare(incident, "severity", "in", "[\"P1\", \"P2\"]")).isTrue();
    assertThat(compare(incident, "severity", "in", "[\"P3\"]")).isFalse();
    assertThat(compare(incident, "severity", "not_in", "[\"P3\"]")).isTrue();
    assertThat(compare(incident, "severity", "not_in", "[\"P2\"]")).isFalse();
    assertThat(compare(incident, "severity", "at_least", "\"P3\"")).isTrue();
    assertThat(compare(incident, "severity", "at_least", "\"P1\"")).isFalse();
    assertThat(compare(incident, "severity", "at_most", "\"P1\"")).isTrue();
    assertThat(compare(incident, "severity", "at_most", "\"P3\"")).isFalse();
    // Key: owner_team.
    assertThat(compare(incident, "owner_team", "equals", "\"db-ops\"")).isTrue();
    assertThat(compare(incident, "owner_team", "equals", "\"noc-l1\"")).isFalse();
    assertThat(compare(incident, "owner_team", "not_equals", "\"noc-l1\"")).isTrue();
    assertThat(compare(incident, "owner_team", "not_equals", "\"db-ops\"")).isFalse();
    assertThat(compare(incident, "owner_team", "in", "[\"noc-l1\", \"db-ops\"]")).isTrue();
    assertThat(compare(incident, "owner_team", "in", "[\"noc-l1\"]")).isFalse();
    assertThat(compare(incident, "owner_team", "not_in", "[\"noc-l1\"]")).isTrue();
    assertThat(compare(incident, "owner_team", "not_in", "[\"db-ops\"]")).isFalse();
    // Enum: hypothesis_component_kind.
    String kind = "hypothesis_component_kind";
    assertThat(compare(incident, kind, "equals", "\"database\"")).isTrue();
    assertThat(compare(incident, kind, "equals", "\"server\"")).isFalse();
    assertThat(compare(incident, kind, "not_equals", "\"server\"")).isTrue();
    assertThat(compare(incident, kind, "not_equals", "\"database\"")).isFalse();
    assertThat(compare(incident, kind, "in", "[\"queue\", \"database\"]")).isTrue();
    assertThat(compare(incident, kind, "in", "[\"queue\"]")).isFalse();
    assertThat(compare(incident, kind, "not_in", "[\"queue\"]")).isTrue();
    assertThat(compare(incident, kind, "not_in", "[\"database\"]")).isFalse();
    // Set: informed_teams and an empty affected_services.
    assertThat(compare(incident, "informed_teams", "contains", "\"noc-l1\"")).isTrue();
    assertThat(compare(incident, "informed_teams", "contains", "\"db-ops\"")).isFalse();
    assertThat(compare(incident, "informed_teams", "not_contains", "\"db-ops\"")).isTrue();
    assertThat(compare(incident, "informed_teams", "not_contains", "\"noc-l1\"")).isFalse();
    Incident noServices = new Incident();
    noServices.services = List.of();
    IncidentView empty = noServices.view();
    assertThat(compare(empty, "affected_services", "contains", "\"svc-a\"")).isFalse();
    assertThat(compare(empty, "affected_services", "not_contains", "\"svc-a\"")).isTrue();
    // Integer: reopen_count 1.
    assertThat(compare(incident, "reopen_count", "equals", "1")).isTrue();
    assertThat(compare(incident, "reopen_count", "equals", "2")).isFalse();
    assertThat(compare(incident, "reopen_count", "not_equals", "2")).isTrue();
    assertThat(compare(incident, "reopen_count", "not_equals", "1")).isFalse();
    assertThat(compare(incident, "reopen_count", "greater_than", "0")).isTrue();
    assertThat(compare(incident, "reopen_count", "greater_than", "1")).isFalse();
    assertThat(compare(incident, "reopen_count", "greater_or_equal", "1")).isTrue();
    assertThat(compare(incident, "reopen_count", "greater_or_equal", "2")).isFalse();
    assertThat(compare(incident, "reopen_count", "less_than", "2")).isTrue();
    assertThat(compare(incident, "reopen_count", "less_than", "1")).isFalse();
    assertThat(compare(incident, "reopen_count", "less_or_equal", "1")).isTrue();
    assertThat(compare(incident, "reopen_count", "less_or_equal", "0")).isFalse();
    // Boolean: acknowledged.
    assertThat(compare(incident, "acknowledged", "is", "true")).isTrue();
    assertThat(compare(incident, "acknowledged", "is", "false")).isFalse();
    // Fields without a value: every operator false.
    Incident none = new Incident();
    none.severity = null;
    none.ownerTeam = null;
    none.hypothesis = null;
    none.services =
        List.of(new AffectedServiceCopy("svc-b", "Service B", null, List.of("db-subscriber")));
    IncidentView noValues = none.view();
    List<String> fields =
        List.of(
            "severity",
            "owner_team",
            "hypothesis_component",
            "hypothesis_component_kind",
            "hypothesis_criticality",
            "highest_service_criticality");
    List<String[]> operations =
        List.of(
            new String[] {"equals", "\"x\""},
            new String[] {"not_equals", "\"x\""},
            new String[] {"in", "[\"x\"]"},
            new String[] {"not_in", "[\"x\"]"},
            new String[] {"at_least", "\"low\""},
            new String[] {"at_most", "\"critical\""},
            new String[] {"at_least", "\"P4\""},
            new String[] {"at_most", "\"P1\""},
            new String[] {"not_equals", "\"P1\""},
            new String[] {"not_in", "[\"P1\"]"},
            new String[] {"not_equals", "\"low\""},
            new String[] {"not_in", "[\"low\"]"},
            new String[] {"not_equals", "\"server\""},
            new String[] {"not_in", "[\"server\"]"});
    List<String> trueOnes = new ArrayList<>();
    for (String field : fields) {
      for (String[] operation : operations) {
        if (compare(noValues, field, operation[0], operation[1])) {
          trueOnes.add(field + " " + operation[0] + " " + operation[1]);
        }
      }
    }
    assertThat(trueOnes).isEmpty();
  }

  @Test
  @DisplayName("PM-M12-03 the comparisons of a branch are joined by AND")
  void and() {
    IncidentView incident = new Incident().view();
    JsonNode both =
        RunContent.JSON.readTree(
            "{\"branches\": [{\"label\": \"hit\", \"comparisons\": ["
                + "{\"field\": \"severity\", \"operator\": \"equals\", \"value\": \"P2\"},"
                + "{\"field\": \"alert_count\", \"operator\": \"greater_than\", \"value\": 2}]}]}");
    assertThat(Conditions.output(incident, both)).isEqualTo("hit");
    JsonNode oneFalse =
        RunContent.JSON.readTree(
            "{\"branches\": [{\"label\": \"hit\", \"comparisons\": ["
                + "{\"field\": \"severity\", \"operator\": \"equals\", \"value\": \"P2\"},"
                + "{\"field\": \"alert_count\", \"operator\": \"greater_than\", \"value\": 3}]}]}");
    assertThat(Conditions.output(incident, oneFalse)).isEqualTo("otherwise");
  }

  private static final String BRANCHES =
      "{\"branches\": ["
          + "{\"label\": \"p1\", \"comparisons\": [{\"field\": \"severity\", \"operator\":"
          + " \"equals\", \"value\": \"P1\"}]},"
          + "{\"label\": \"high\", \"comparisons\": [{\"field\": \"severity\", \"operator\":"
          + " \"at_least\", \"value\": \"P2\"}]},"
          + "{\"label\": \"any\", \"comparisons\": [{\"field\": \"alert_count\", \"operator\":"
          + " \"greater_or_equal\", \"value\": 1}]}]}";

  private static String output(Severity severity, long alertCount) {
    Incident incident = new Incident();
    incident.severity = severity;
    IncidentView view = incident.view();
    IncidentView counted =
        new IncidentView(
            view.id(),
            view.sequence(),
            view.number(),
            view.title(),
            view.state(),
            view.stateChangedAt(),
            view.firstAlertFiredAt(),
            view.lastAlertJoinedAt(),
            view.stableAt(),
            alertCount,
            view.alertTypes(),
            view.alertSources(),
            view.hypothesisComponentKey(),
            view.hypothesis(),
            view.hypothesisComputationId(),
            view.affectedServices(),
            view.severity(),
            view.ownerTeamKey(),
            view.informedTeamKeys(),
            view.assignmentEvaluationId(),
            view.assignmentFrozen(),
            view.escalationLevel(),
            view.acknowledgedAt(),
            view.acknowledgedBy(),
            view.aiStatus(),
            view.aiUnavailableReason(),
            view.aiStatusChangedAt(),
            view.aiLastRequestGroupId(),
            view.resolvedAt(),
            view.resolvedByVerificationId(),
            view.closedAt(),
            view.mergedIntoIncidentId(),
            view.reopenCount(),
            view.createdAt(),
            view.updatedAt());
    return Conditions.output(counted, RunContent.JSON.readTree(BRANCHES));
  }

  @Test
  @DisplayName("DM-S-02 branches tested in list order, the first all-true taken, else otherwise")
  void branchOrder() {
    assertThat(output(Severity.P1, 3)).isEqualTo("p1");
    assertThat(output(Severity.P2, 3)).isEqualTo("high");
    assertThat(output(Severity.P3, 3)).isEqualTo("any");
    assertThat(output(Severity.P3, 0)).isEqualTo("otherwise");
  }

  @Test
  @DisplayName("PM-S-02 a Condition step has one output per branch, plus otherwise")
  void outputs() {
    assertThat(output(Severity.P1, 0)).isEqualTo("p1");
    assertThat(output(Severity.P4, 0)).isEqualTo("otherwise");
    RunContent.Step step =
        new RunContent.Step(
            "s-c",
            vigil.shared.StepCall.StepType.CONDITION,
            "Condition",
            RunContent.JSON.readTree(BRANCHES));
    assertThat(RunContent.outputs(step)).containsExactly("p1", "high", "any", "otherwise");
  }
}
