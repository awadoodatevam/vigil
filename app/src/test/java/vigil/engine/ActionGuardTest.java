package vigil.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import vigil.engine.api.StepExecutionState;
import vigil.engine.api.StepExecutionView;
import vigil.shared.Completed.ResultRef;
import vigil.shared.Ids;
import vigil.shared.StepCall.StepType;

class ActionGuardTest {

  private static final Instant T = Instant.parse("2026-10-24T10:00:00Z");
  private static final UUID PROPOSAL = Ids.newId();
  private static final UUID REQUEST = Ids.newId();

  private static StepExecutionView execution(
      StepType type, String stepId, int start, String output, UUID request) {
    return new StepExecutionView(
        Ids.newId(),
        UUID.randomUUID(),
        stepId,
        type,
        1,
        StepExecutionState.COMPLETED,
        T.plusSeconds(start),
        T.plusSeconds(start + 1),
        output,
        request == null ? null : new ResultRef("vigil-actions", "ApprovalRequest", request),
        null,
        null,
        null,
        1,
        null,
        null,
        0,
        null);
  }

  private static StepExecutionView approval(int start, String output) {
    return execution(StepType.APPROVAL, "s-approve", start, output, REQUEST);
  }

  private static Optional<String> refusal(
      String content,
      List<StepExecutionView> executions,
      Optional<UUID> proposal,
      Optional<UUID> lastExecuted,
      Function<UUID, Optional<UUID>> proposalOfRequest) {
    return ActionGuard.refusal(
        RunContent.parse(content),
        "s-action",
        executions,
        proposal,
        lastExecuted,
        proposalOfRequest);
  }

  private static Optional<String> refusal(String content, List<StepExecutionView> executions) {
    return refusal(
        content, executions, Optional.of(PROPOSAL), Optional.empty(), r -> Optional.of(PROPOSAL));
  }

  /** The content without the connection from the step's output, or with it to another step. */
  private static String connect(String content, String from, String output, String to) {
    return TestContents.with(
        content,
        root -> {
          ArrayNode connections = (ArrayNode) root.get("connections");
          for (int i = 0; i < connections.size(); i++) {
            JsonNode connection = connections.get(i);
            if (connection.get("from_step_id").stringValue().equals(from)
                && connection.get("from_output").stringValue().equals(output)) {
              connections.remove(i);
              break;
            }
          }
          if (to != null) {
            ObjectNode added = connections.addObject();
            added.put("from_step_id", from);
            added.put("from_output", output);
            added.put("to_step_id", to);
          }
          return root;
        });
  }

  @Test
  @DisplayName("DM-R-31 the four guard conditions in order and the path rule")
  void guard() {
    String v1 = TestContents.version1();
    List<StepExecutionView> approved = List.of(approval(10, "approved"));
    assertThat(
            refusal(v1, approved, Optional.empty(), Optional.empty(), r -> Optional.of(PROPOSAL)))
        .contains(ActionGuard.NO_PROPOSAL);

    StepExecutionView executed = execution(StepType.ACTION, "s-action", 20, "succeeded", null);
    assertThat(
            refusal(
                v1,
                List.of(approval(10, "approved"), executed),
                Optional.of(PROPOSAL),
                Optional.of(executed.id()),
                r -> Optional.of(PROPOSAL)))
        .contains(ActionGuard.NO_APPROVAL);

    assertThat(refusal(v1, List.of(approval(10, "approved"), approval(20, "rejected"))))
        .contains(ActionGuard.NOT_THIS_PROPOSAL);
    assertThat(
            refusal(
                v1,
                approved,
                Optional.of(PROPOSAL),
                Optional.empty(),
                r -> Optional.of(Ids.newId())))
        .contains(ActionGuard.NOT_THIS_PROPOSAL);

    assertThat(refusal(v1, approved)).isEmpty();
    assertThat(
            refusal(
                v1,
                List.of(approval(10, "approved"), executed, approval(30, "approved")),
                Optional.of(PROPOSAL),
                Optional.of(executed.id()),
                r -> Optional.of(PROPOSAL)))
        .isEmpty();
    assertThat(refusal(connect(v1, "s-recover", "not_recovered", null), approved)).isEmpty();
    assertThat(refusal(connect(v1, "s-action", "failed", null), approved))
        .contains(ActionGuard.NO_RECOVERY_CHECK);
    String cycle =
        connect(
            connect(v1, "s-action", "succeeded", "s-escalate"), "s-escalate", "next", "s-action");
    assertThat(refusal(cycle, approved)).contains(ActionGuard.NO_RECOVERY_CHECK);
  }

  @Test
  @DisplayName("DC-TX-11 the engine guard refusal reasons, exactly")
  void texts() {
    assertThat(ActionGuard.NO_PROPOSAL).isEqualTo("no action proposal in this run");
    assertThat(ActionGuard.NO_APPROVAL).isEqualTo("no approval after the last execution");
    assertThat(ActionGuard.NOT_THIS_PROPOSAL)
        .isEqualTo("the latest approval is not for this run's proposal");
    assertThat(ActionGuard.NO_RECOVERY_CHECK)
        .isEqualTo("an output of this step does not reach a Recovery check step");
  }
}
