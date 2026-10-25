package vigil.workflows;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vigil.shared.StepCall.StepType;
import vigil.workflows.api.RequiredWhen;
import vigil.workflows.api.StepOutput;
import vigil.workflows.api.StepParameter;
import vigil.workflows.api.StepParameterType;
import vigil.workflows.api.StepTypeDefinition;

class StepCatalogueTest {

  /** The table of P-41 spec Section 6.4, written out. */
  private static final List<StepTypeDefinition> EXPECTED =
      List.of(
          new StepTypeDefinition(
              StepType.TRIGGER,
              List.of(
                  parameter(
                      "event",
                      StepParameterType.ENUM,
                      true,
                      null,
                      List.of("incident_created_or_reopened"),
                      null,
                      null,
                      0)),
              List.of(new StepOutput("next", "next"))),
          new StepTypeDefinition(
              StepType.CONDITION,
              List.of(
                  parameter(
                      "branches",
                      StepParameterType.CONDITION_BRANCHES,
                      true,
                      null,
                      List.of(),
                      null,
                      null,
                      1)),
              List.of(new StepOutput("otherwise", "otherwise"))),
          new StepTypeDefinition(
              StepType.DIAGNOSTIC,
              List.of(
                  parameter(
                      "diagnostics",
                      StepParameterType.KEY_LIST,
                      true,
                      null,
                      List.of(),
                      null,
                      null,
                      1),
                  parameter(
                      "target",
                      StepParameterType.ENUM,
                      true,
                      null,
                      List.of("hypothesis_component"),
                      null,
                      null,
                      0)),
              List.of(new StepOutput("done", "done"), new StepOutput("failed", "failed"))),
          new StepTypeDefinition(
              StepType.AI_TASK,
              List.of(
                  parameter(
                      "task",
                      StepParameterType.ENUM,
                      true,
                      null,
                      List.of("SL-T-1", "SL-T-2", "SL-T-3"),
                      null,
                      null,
                      0)),
              List.of(
                  new StepOutput("done", "done"), new StepOutput("unavailable", "unavailable"))),
          new StepTypeDefinition(
              StepType.APPROVAL,
              List.of(
                  parameter(
                      "eligible_teams",
                      StepParameterType.KEY_SET,
                      true,
                      null,
                      List.of(),
                      null,
                      null,
                      1),
                  parameter(
                      "expiry",
                      StepParameterType.DURATION,
                      true,
                      null,
                      List.of(),
                      60L,
                      86_400L,
                      0)),
              List.of(
                  new StepOutput("approved", "approved"),
                  new StepOutput("rejected", "rejected"),
                  new StepOutput("expired", "expired"))),
          new StepTypeDefinition(
              StepType.ACTION,
              List.of(),
              List.of(
                  new StepOutput("succeeded", "succeeded"), new StepOutput("failed", "failed"))),
          new StepTypeDefinition(
              StepType.RECOVERY_CHECK,
              List.of(
                  parameter(
                      "max_wait",
                      StepParameterType.DURATION,
                      true,
                      null,
                      List.of(),
                      60L,
                      86_400L,
                      0)),
              List.of(
                  new StepOutput("recovered", "recovered"),
                  new StepOutput("not_recovered", "not recovered"))),
          new StepTypeDefinition(
              StepType.ESCALATION,
              List.of(
                  parameter(
                      "mode",
                      StepParameterType.ENUM,
                      true,
                      null,
                      List.of("wait_for_acknowledgement", "escalate_now"),
                      null,
                      null,
                      0),
                  parameter(
                      "timer",
                      StepParameterType.DURATION,
                      false,
                      new RequiredWhen("mode", "wait_for_acknowledgement"),
                      List.of(),
                      30L,
                      86_400L,
                      0)),
              List.of(new StepOutput("next", "next"))),
          new StepTypeDefinition(
              StepType.NOTIFICATION,
              List.of(
                  parameter(
                      "recipients",
                      StepParameterType.ENUM_SET,
                      true,
                      null,
                      List.of("owner_team", "informed_parties", "named_teams"),
                      null,
                      null,
                      1),
                  parameter(
                      "named_teams",
                      StepParameterType.KEY_SET,
                      false,
                      new RequiredWhen("recipients", "named_teams"),
                      List.of(),
                      null,
                      null,
                      0)),
              List.of(new StepOutput("sent", "sent"), new StepOutput("failed", "failed"))),
          new StepTypeDefinition(
              StepType.TICKET,
              List.of(
                  parameter(
                      "operation",
                      StepParameterType.ENUM,
                      true,
                      null,
                      List.of("create", "resolve"),
                      null,
                      null,
                      0)),
              List.of(new StepOutput("done", "done"), new StepOutput("failed", "failed"))),
          new StepTypeDefinition(
              StepType.WAIT,
              List.of(
                  parameter(
                      "mode",
                      StepParameterType.ENUM,
                      true,
                      null,
                      List.of("duration", "until_event"),
                      null,
                      null,
                      0),
                  parameter(
                      "duration",
                      StepParameterType.DURATION,
                      false,
                      new RequiredWhen("mode", "duration"),
                      List.of(),
                      1L,
                      86_400L,
                      0),
                  parameter(
                      "event",
                      StepParameterType.ENUM,
                      false,
                      new RequiredWhen("mode", "until_event"),
                      List.of("incident_acknowledged"),
                      null,
                      null,
                      0),
                  parameter(
                      "timeout",
                      StepParameterType.DURATION,
                      false,
                      new RequiredWhen("mode", "until_event"),
                      List.of(),
                      1L,
                      86_400L,
                      0)),
              List.of(
                  new StepOutput("event_received", "event received"),
                  new StepOutput("timed_out", "timed out"))));

  private static StepParameter parameter(
      String name,
      StepParameterType type,
      boolean required,
      RequiredWhen requiredWhen,
      List<String> allowedValues,
      Long min,
      Long max,
      int minItems) {
    return new StepParameter(name, type, required, requiredWhen, allowedValues, min, max, minItems);
  }

  @Test
  @DisplayName("DM-E-44 the step type catalogue is the table of DM Section 9")
  void catalogue() {
    assertThat(StepCatalogue.all()).isEqualTo(EXPECTED);
  }

  @Test
  @DisplayName("PM-M11-02 the step types in order, with their parameters and named outputs")
  void stepTypes() {
    List<StepTypeDefinition> all = StepCatalogue.all();
    assertThat(all.stream().map(StepTypeDefinition::type).toList())
        .containsExactly(StepType.values());
    for (int i = 0; i < EXPECTED.size(); i++) {
      StepTypeDefinition expected = EXPECTED.get(i);
      StepTypeDefinition actual = all.get(i);
      assertThat(actual.parameters()).as(expected.type().key()).isEqualTo(expected.parameters());
      assertThat(actual.outputs()).as(expected.type().key()).isEqualTo(expected.outputs());
    }
    assertThat(new Workflows(null).stepTypes()).isSameAs(all);
  }
}
