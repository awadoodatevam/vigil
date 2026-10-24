package vigil.notifications.api;

import vigil.shared.StepAnswer;
import vigil.shared.StepCall;
import vigil.shared.StepCancel;

/**
 * The step handler of vigil-notifications: Notification steps (AR-R-21), called by vigil-engine.
 */
public interface NotificationsStepHandler {

  /** Starts the step. External call: not inside a transaction. */
  StepAnswer start(StepCall call);

  /** Evaluates the waiting step again. External call: not inside a transaction. */
  StepAnswer evaluate(StepCall call);

  /**
   * Cancels whatever the step has created that is still open; joins the caller's transaction and
   * gives the same result when called twice.
   */
  void cancel(StepCancel cancel);
}
