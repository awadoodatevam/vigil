package vigil.workflows;

import java.util.List;
import java.util.Optional;
import vigil.shared.StepCall.StepType;
import vigil.workflows.api.RequiredWhen;
import vigil.workflows.api.StepOutput;
import vigil.workflows.api.StepParameter;
import vigil.workflows.api.StepParameterType;
import vigil.workflows.api.StepTypeDefinition;

/** The step type catalogue (DM-E-44, DM Section 9, PM-M11-02; P-41 spec Section 6.4). */
final class StepCatalogue {

  private static final long DAY = 86_400;

  private static final List<StepTypeDefinition> ALL =
      List.of(
          type(
              StepType.TRIGGER,
              List.of(enumeration("event", true, null, "incident_created_or_reopened")),
              output("next", "next")),
          type(
              StepType.CONDITION,
              List.of(
                  new StepParameter(
                      "branches",
                      StepParameterType.CONDITION_BRANCHES,
                      true,
                      null,
                      List.of(),
                      null,
                      null,
                      1)),
              output("otherwise", "otherwise")),
          type(
              StepType.DIAGNOSTIC,
              List.of(
                  keys("diagnostics", StepParameterType.KEY_LIST, true, null, 1),
                  enumeration("target", true, null, "hypothesis_component")),
              output("done", "done"),
              output("failed", "failed")),
          type(
              StepType.AI_TASK,
              List.of(enumeration("task", true, null, "SL-T-1", "SL-T-2", "SL-T-3")),
              output("done", "done"),
              output("unavailable", "unavailable")),
          type(
              StepType.APPROVAL,
              List.of(
                  keys("eligible_teams", StepParameterType.KEY_SET, true, null, 1),
                  duration("expiry", true, null, 60)),
              output("approved", "approved"),
              output("rejected", "rejected"),
              output("expired", "expired")),
          type(
              StepType.ACTION,
              List.of(),
              output("succeeded", "succeeded"),
              output("failed", "failed")),
          type(
              StepType.RECOVERY_CHECK,
              List.of(duration("max_wait", true, null, 60)),
              output("recovered", "recovered"),
              output("not_recovered", "not recovered")),
          type(
              StepType.ESCALATION,
              List.of(
                  enumeration("mode", true, null, "wait_for_acknowledgement", "escalate_now"),
                  duration(
                      "timer", false, new RequiredWhen("mode", "wait_for_acknowledgement"), 30)),
              output("next", "next")),
          type(
              StepType.NOTIFICATION,
              List.of(
                  new StepParameter(
                      "recipients",
                      StepParameterType.ENUM_SET,
                      true,
                      null,
                      List.of("owner_team", "informed_parties", "named_teams"),
                      null,
                      null,
                      1),
                  keys(
                      "named_teams",
                      StepParameterType.KEY_SET,
                      false,
                      new RequiredWhen("recipients", "named_teams"),
                      0)),
              output("sent", "sent"),
              output("failed", "failed")),
          type(
              StepType.TICKET,
              List.of(enumeration("operation", true, null, "create", "resolve")),
              output("done", "done"),
              output("failed", "failed")),
          type(
              StepType.WAIT,
              List.of(
                  enumeration("mode", true, null, "duration", "until_event"),
                  duration("duration", false, new RequiredWhen("mode", "duration"), 1),
                  enumeration(
                      "event",
                      false,
                      new RequiredWhen("mode", "until_event"),
                      "incident_acknowledged"),
                  duration("timeout", false, new RequiredWhen("mode", "until_event"), 1)),
              output("event_received", "event received"),
              output("timed_out", "timed out")));

  private StepCatalogue() {}

  static List<StepTypeDefinition> all() {
    return ALL;
  }

  /** The step type with this key ({@code trigger}, …); empty for any other value. */
  static Optional<StepTypeDefinition> ofKey(String key) {
    return ALL.stream().filter(definition -> definition.type().key().equals(key)).findFirst();
  }

  private static StepTypeDefinition type(
      StepType type, List<StepParameter> parameters, StepOutput... outputs) {
    return new StepTypeDefinition(type, parameters, List.of(outputs));
  }

  private static StepOutput output(String key, String displayText) {
    return new StepOutput(key, displayText);
  }

  private static StepParameter enumeration(
      String name, boolean required, RequiredWhen requiredWhen, String... allowed) {
    return new StepParameter(
        name, StepParameterType.ENUM, required, requiredWhen, List.of(allowed), null, null, 0);
  }

  private static StepParameter keys(
      String name,
      StepParameterType type,
      boolean required,
      RequiredWhen requiredWhen,
      int minItems) {
    return new StepParameter(name, type, required, requiredWhen, List.of(), null, null, minItems);
  }

  private static StepParameter duration(
      String name, boolean required, RequiredWhen requiredWhen, long min) {
    return new StepParameter(
        name, StepParameterType.DURATION, required, requiredWhen, List.of(), min, DAY, 0);
  }
}
