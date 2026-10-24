package vigil.engine;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import vigil.actions.api.ActionRuns;
import vigil.actions.api.ActionsStepHandler;
import vigil.diagnostics.api.DiagnosticsStepHandler;
import vigil.investigation.api.InvestigationStepHandler;
import vigil.notifications.api.NotificationsStepHandler;
import vigil.recovery.api.RecoveryStepHandler;
import vigil.routing.api.RoutingStepHandler;
import vigil.shared.Completed;
import vigil.shared.Key;
import vigil.shared.StepAnswer;
import vigil.shared.StepCall;
import vigil.shared.StepCall.StepType;
import vigil.shared.StepCancel;
import vigil.shared.TenantId;
import vigil.ticketing.api.TicketingStepHandler;

/**
 * The step handlers of the step-implementing modules and {@code ActionRuns} (AR-R-21, CTX-D-055
 * (2), (3)), each obtained when used; an absent bean is a module not built yet.
 */
@Component
class StepHandlers {

  /** One handler's three operations. */
  record Handler(
      Function<StepCall, StepAnswer> start,
      Function<StepCall, StepAnswer> evaluate,
      Consumer<StepCancel> cancel) {}

  private final ObjectProvider<DiagnosticsStepHandler> diagnostics;
  private final ObjectProvider<InvestigationStepHandler> investigation;
  private final ObjectProvider<ActionsStepHandler> actions;
  private final ObjectProvider<RecoveryStepHandler> recovery;
  private final ObjectProvider<RoutingStepHandler> routing;
  private final ObjectProvider<NotificationsStepHandler> notifications;
  private final ObjectProvider<TicketingStepHandler> ticketing;
  private final ObjectProvider<ActionRuns> actionRuns;

  StepHandlers(
      ObjectProvider<DiagnosticsStepHandler> diagnostics,
      ObjectProvider<InvestigationStepHandler> investigation,
      ObjectProvider<ActionsStepHandler> actions,
      ObjectProvider<RecoveryStepHandler> recovery,
      ObjectProvider<RoutingStepHandler> routing,
      ObjectProvider<NotificationsStepHandler> notifications,
      ObjectProvider<TicketingStepHandler> ticketing,
      ObjectProvider<ActionRuns> actionRuns) {
    this.diagnostics = diagnostics;
    this.investigation = investigation;
    this.actions = actions;
    this.recovery = recovery;
    this.routing = routing;
    this.notifications = notifications;
    this.ticketing = ticketing;
    this.actionRuns = actionRuns;
  }

  /** Whether the engine answers the step type itself (PM-M12-02). */
  static boolean ownStep(StepType type) {
    return type == StepType.TRIGGER || type == StepType.CONDITION || type == StepType.WAIT;
  }

  /** The handler of the step type; empty for the engine's own steps and for an absent bean. */
  Optional<Handler> handler(StepType type) {
    return switch (type) {
      case DIAGNOSTIC ->
          Optional.ofNullable(diagnostics.getIfAvailable())
              .map(h -> new Handler(h::start, h::evaluate, h::cancel));
      case AI_TASK ->
          Optional.ofNullable(investigation.getIfAvailable())
              .map(h -> new Handler(h::start, h::evaluate, h::cancel));
      case APPROVAL, ACTION ->
          Optional.ofNullable(actions.getIfAvailable())
              .map(h -> new Handler(h::start, h::evaluate, h::cancel));
      case RECOVERY_CHECK ->
          Optional.ofNullable(recovery.getIfAvailable())
              .map(h -> new Handler(h::start, h::evaluate, h::cancel));
      case ESCALATION ->
          Optional.ofNullable(routing.getIfAvailable())
              .map(h -> new Handler(h::start, h::evaluate, h::cancel));
      case NOTIFICATION ->
          Optional.ofNullable(notifications.getIfAvailable())
              .map(h -> new Handler(h::start, h::evaluate, h::cancel));
      case TICKET ->
          Optional.ofNullable(ticketing.getIfAvailable())
              .map(h -> new Handler(h::start, h::evaluate, h::cancel));
      case TRIGGER, CONDITION, WAIT -> Optional.empty();
    };
  }

  /** Calls the handler's {@code cancel} in the caller's transaction, when the bean exists. */
  void cancel(StepCancel cancel) {
    handler(cancel.stepType()).ifPresent(handler -> handler.cancel().accept(cancel));
  }

  // ActionRuns; without the bean a run has no proposal, approvers or recovery check key.

  Optional<UUID> proposal(TenantId tenantId, UUID runId) {
    ActionRuns runs = actionRuns.getIfAvailable();
    return runs == null ? Optional.empty() : runs.proposal(tenantId, runId);
  }

  Optional<UUID> proposalOfRequest(TenantId tenantId, UUID approvalRequestId) {
    ActionRuns runs = actionRuns.getIfAvailable();
    return runs == null ? Optional.empty() : runs.proposalOfRequest(tenantId, approvalRequestId);
  }

  Optional<UUID> lastExecutedStepExecution(TenantId tenantId, UUID runId) {
    ActionRuns runs = actionRuns.getIfAvailable();
    return runs == null ? Optional.empty() : runs.lastExecutedStepExecution(tenantId, runId);
  }

  List<UUID> earlierApprovers(TenantId tenantId, UUID runId) {
    ActionRuns runs = actionRuns.getIfAvailable();
    return runs == null ? List.of() : runs.earlierApprovers(tenantId, runId);
  }

  Key latestRecoveryCheckKey(TenantId tenantId, UUID runId) {
    ActionRuns runs = actionRuns.getIfAvailable();
    return runs == null ? null : runs.latestRecoveryCheckKey(tenantId, runId).orElse(null);
  }

  /** PM-M12-10: the reconciliation; without the bean it is a failed call. */
  Completed reconcile(StepCall call) {
    ActionRuns runs = actionRuns.getIfAvailable();
    if (runs == null) {
      throw new IllegalStateException("no step handler for " + StepType.ACTION.key());
    }
    return runs.reconcile(call);
  }
}
