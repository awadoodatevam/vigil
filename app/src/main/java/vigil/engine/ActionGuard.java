package vigil.engine;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import vigil.engine.api.StepExecutionState;
import vigil.engine.api.StepExecutionView;
import vigil.shared.StepCall.StepType;

/**
 * The Action step guard (DM-R-31, PM-M12-06): the four conditions in their order; the first that
 * fails gives its refusal reason (DC-TX-11).
 */
final class ActionGuard {

  static final String NO_PROPOSAL = "no action proposal in this run";
  static final String NO_APPROVAL = "no approval after the last execution";
  static final String NOT_THIS_PROPOSAL = "the latest approval is not for this run's proposal";
  static final String NO_RECOVERY_CHECK =
      "an output of this step does not reach a Recovery check step";

  private static final String APPROVED = "approved";
  private static final Comparator<StepExecutionView> BY_START =
      Comparator.comparing(StepExecutionView::startedAt).thenComparing(StepExecutionView::id);
  private static final Comparator<StepExecutionView> BY_FINISH =
      Comparator.comparing(StepExecutionView::finishedAt).thenComparing(StepExecutionView::id);

  private ActionGuard() {}

  /**
   * The refusal reason for the Action step {@code actionStepId}, or empty when it may start.
   *
   * @param executions the run's step executions
   * @param proposal the run's proposal
   * @param lastExecuted the Action step execution of the run's last executed action
   * @param proposalOfRequest the proposal of an approval request
   */
  static Optional<String> refusal(
      RunContent content,
      String actionStepId,
      List<StepExecutionView> executions,
      Optional<UUID> proposal,
      Optional<UUID> lastExecuted,
      Function<UUID, Optional<UUID>> proposalOfRequest) {
    if (proposal.isEmpty()) {
      return Optional.of(NO_PROPOSAL);
    }
    Optional<StepExecutionView> last =
        lastExecuted.flatMap(
            id -> executions.stream().filter(execution -> execution.id().equals(id)).findFirst());
    List<StepExecutionView> approvals =
        executions.stream()
            .filter(execution -> execution.stepType() == StepType.APPROVAL)
            .filter(execution -> execution.state() == StepExecutionState.COMPLETED)
            .toList();
    boolean approvedAfter =
        approvals.stream()
            .filter(execution -> APPROVED.equals(execution.namedOutput()))
            .anyMatch(execution -> last.isEmpty() || BY_START.compare(execution, last.get()) > 0);
    if (!approvedAfter) {
      return Optional.of(NO_APPROVAL);
    }
    StepExecutionView latest = approvals.stream().max(BY_FINISH).orElseThrow();
    if (!APPROVED.equals(latest.namedOutput())
        || latest.resultRef() == null
        || !proposalOfRequest.apply(latest.resultRef().id()).equals(proposal)) {
      return Optional.of(NOT_THIS_PROPOSAL);
    }
    RunContent.Step action = content.step(actionStepId).orElseThrow();
    Set<String> safe = new HashSet<>();
    for (String output : RunContent.outputs(action)) {
      Optional<String> target = content.target(actionStepId, output);
      if (target.isEmpty() || !reaches(content, target.get(), Set.of(actionStepId), safe)) {
        return Optional.of(NO_RECOVERY_CHECK);
      }
    }
    return Optional.empty();
  }

  /**
   * Whether every path from the step reaches a Recovery check step before an output without a
   * connection and before a step already on the path; {@code safe} holds the steps found to do so.
   */
  private static boolean reaches(
      RunContent content, String stepId, Set<String> path, Set<String> safe) {
    if (safe.contains(stepId)) {
      return true;
    }
    if (path.contains(stepId)) {
      return false;
    }
    Optional<RunContent.Step> step = content.step(stepId);
    if (step.isEmpty() || step.get().type() == null) {
      return false;
    }
    if (step.get().type() == StepType.RECOVERY_CHECK) {
      return true;
    }
    Set<String> longer = new HashSet<>(path);
    longer.add(stepId);
    for (String output : RunContent.outputs(step.get())) {
      Optional<String> target = content.target(stepId, output);
      if (target.isEmpty() || !reaches(content, target.get(), longer, safe)) {
        return false;
      }
    }
    safe.add(stepId);
    return true;
  }
}
