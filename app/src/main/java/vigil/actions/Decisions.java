package vigil.actions;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vigil.actions.api.ApprovalDecided;
import vigil.actions.api.ApprovalRequestView;
import vigil.actions.api.Decision;
import vigil.actions.api.DecisionRefusal;
import vigil.actions.api.RequestState;
import vigil.identity.api.IdentityService;
import vigil.identity.api.Permission;
import vigil.shared.Key;
import vigil.shared.TenantId;
import vigil.shared.Timestamps;
import vigil.shared.VigilError;

/**
 * The decision on an approval request (DM-E-59, DM-E59-R1, DM-R-63, AR-E-09; P-24 spec Section
 * 6.7): one transaction that locks the request, checks in the order of DC-S05-06 and, when
 * accepted, stores the decision, decides the request and publishes {@link ApprovalDecided}.
 */
@Component
class Decisions {

  private final ActionRecords records;
  private final ApprovalStep approvals;
  private final IdentityService identity;
  private final ApplicationEventPublisher publisher;
  private final TransactionTemplate tx;
  private final Clock clock;

  Decisions(
      ActionRecords records,
      ApprovalStep approvals,
      IdentityService identity,
      ApplicationEventPublisher publisher,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.records = records;
    this.approvals = approvals;
    this.identity = identity;
    this.publisher = publisher;
    this.tx = new TransactionTemplate(transactionManager);
    this.clock = clock;
  }

  ApprovalRequestView decide(
      TenantId tenantId, UUID requestId, UUID userId, Decision decision, String hashShown) {
    return tx.execute(status -> decideLocked(tenantId, requestId, userId, decision, hashShown));
  }

  /**
   * DM-E59-R1, CTX-D-079 (3): the refusal of checks 1 to 6 that a decision of the user would give
   * now, read without a lock; empty when none applies or the request does not exist.
   */
  Optional<DecisionRefusal> refusal(TenantId tenantId, UUID requestId, UUID userId) {
    return records
        .request(tenantId, requestId)
        .flatMap(request -> check(tenantId, request, userId, Timestamps.now(clock)));
  }

  private ApprovalRequestView decideLocked(
      TenantId tenantId, UUID requestId, UUID userId, Decision decision, String hashShown) {
    if (!identity.hasPermission(tenantId, userId, Permission.APPROVE_ACTION)) {
      throw new VigilError(VigilError.Code.FORBIDDEN);
    }
    ApprovalRequestView request =
        records
            .lockRequest(tenantId, requestId)
            .orElseThrow(() -> new VigilError(VigilError.Code.NOT_FOUND));
    Instant now = Timestamps.now(clock);
    Optional<DecisionRefusal> refusal = check(tenantId, request, userId, now);
    if (refusal.isPresent()) {
      throw new VigilError(
          VigilError.Code.REFUSED, refusal.get().message(), Map.copyOf(refusal.get().details()));
    }
    if (!request.snapshotHash().equals(hashShown)) {
      throw new VigilError(
          VigilError.Code.REFUSED,
          "This request changed after you opened it. Reload the page to see the current request.",
          Map.of("refusal", "snapshot_changed"));
    }
    String team = eligibleTeam(tenantId, request, userId).orElseThrow();
    records.insertDecision(tenantId, requestId, userId, team, decision.key(), hashShown, now);
    RequestState state =
        decision == Decision.APPROVE ? RequestState.APPROVED : RequestState.REJECTED;
    records.decideRequest(tenantId, requestId, state, now, userId);
    ApprovalRequestView decided = records.request(tenantId, requestId).orElseThrow();
    approvals.decided(tenantId, decided, userId);
    publisher.publishEvent(
        new ApprovalDecided(tenantId, requestId, decided.runId(), decided.stepExecutionId()));
    return decided;
  }

  /** Checks 1 to 6 of DC-S05-06 in their order; the first that applies, or empty. */
  private Optional<DecisionRefusal> check(
      TenantId tenantId, ApprovalRequestView request, UUID userId, Instant now) {
    switch (request.state()) {
      case CANCELLED -> {
        return refusal(
            "cancelled",
            "This request was cancelled because the incident's workflow run ended.",
            Map.of());
      }
      case PREAUTHORIZED, BLOCKED, REFUSED -> {
        return refusal(
            "not_pending",
            ActionTexts.requestState(request.state(), request.refusalReason()),
            Map.of("state", request.state().key()));
      }
      case APPROVED, REJECTED -> {
        return refusal(
            "already_decided",
            "Already "
                + (request.state() == RequestState.APPROVED ? "approved" : "rejected")
                + " by "
                + approvals.userName(tenantId, request.decidedBy())
                + " at "
                + ActionTexts.outbound(request.decidedAt())
                + ".",
            Map.of(
                "state",
                request.state().key(),
                "user_id",
                request.decidedBy().toString(),
                "at",
                ActionTexts.jsonTime(request.decidedAt())));
      }
      default -> {
        // pending or expired: checked below.
      }
    }
    if (request.state() == RequestState.EXPIRED || !now.isBefore(request.expiresAt())) {
      return refusal(
          "expired",
          "This request expired at " + ActionTexts.outbound(request.expiresAt()) + ".",
          Map.of("at", ActionTexts.jsonTime(request.expiresAt())));
    }
    if (eligibleTeam(tenantId, request, userId).isEmpty()) {
      return refusal(
          "not_eligible",
          "You are not in a team that can approve this action ("
              + ActionTexts.list(approvals.teamNames(tenantId, request.eligibleTeamKeys()))
              + ").",
          Map.of());
    }
    if (request.excludedUserIds().contains(userId)) {
      return refusal(
          "excluded",
          "You approved an earlier step of this run. A different approver is needed.",
          Map.of());
    }
    return Optional.empty();
  }

  /** The first eligible team, in code point order, of which the user is an active member. */
  private Optional<String> eligibleTeam(
      TenantId tenantId, ApprovalRequestView request, UUID userId) {
    return request.eligibleTeamKeys().stream()
        .sorted(ActionTexts.CODE_POINTS)
        .filter(key -> identity.isMember(tenantId, userId, new Key(key)))
        .findFirst();
  }

  /** The refusal with its key, message and details, the key also as {@code refusal}. */
  private static Optional<DecisionRefusal> refusal(
      String key, String message, Map<String, String> details) {
    Map<String, String> all = new HashMap<>(details);
    all.put("refusal", key);
    return Optional.of(new DecisionRefusal(key, message, all));
  }
}
