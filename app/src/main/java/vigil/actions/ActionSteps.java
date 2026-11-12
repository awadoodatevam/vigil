package vigil.actions;

import org.springframework.stereotype.Component;
import vigil.actions.api.ActionsStepHandler;
import vigil.shared.StepAnswer;
import vigil.shared.StepCall;
import vigil.shared.StepCancel;
import vigil.shared.TransactionGuard;

/**
 * The step handler of vigil-actions (AR-R-21): Approval steps to {@link ApprovalStep}, Action steps
 * to {@link ActionStep}. No other bean injects it (P-24 spec Section 6.1).
 */
@Component
class ActionSteps implements ActionsStepHandler {

  private static final String EXPECTED = "approval or action step expected";

  private final ApprovalStep approval;
  private final ActionStep action;

  ActionSteps(ApprovalStep approval, ActionStep action) {
    this.approval = approval;
    this.action = action;
  }

  @Override
  public StepAnswer start(StepCall call) {
    return dispatch(call);
  }

  @Override
  public StepAnswer evaluate(StepCall call) {
    return dispatch(call);
  }

  /** An Approval step's pending request becomes cancelled; an Action step leaves nothing open. */
  @Override
  public void cancel(StepCancel cancel) {
    Actions.require(cancel);
    switch (cancel.stepType()) {
      case APPROVAL -> approval.cancel(cancel);
      case ACTION -> {
        // Nothing to cancel.
      }
      default -> throw new IllegalArgumentException(EXPECTED);
    }
  }

  private StepAnswer dispatch(StepCall call) {
    TransactionGuard.assertNoTransaction();
    Actions.require(call);
    return switch (call.stepType()) {
      case APPROVAL -> approval.run(call);
      case ACTION -> action.run(call);
      default -> throw new IllegalArgumentException(EXPECTED);
    };
  }
}
