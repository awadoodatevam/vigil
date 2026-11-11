package vigil.recovery;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import vigil.audit.api.AuditEntry;
import vigil.audit.api.AuditEventType;
import vigil.audit.api.AuditService;
import vigil.incidents.api.DataCategory;
import vigil.incidents.api.EvidenceKind;
import vigil.incidents.api.IncidentService;
import vigil.incidents.api.IncidentState;
import vigil.recovery.RecoveryRecords.Definition;
import vigil.recovery.api.RecoveryStepHandler;
import vigil.recovery.api.RecoveryVerificationView;
import vigil.recovery.api.VerificationState;
import vigil.shared.Actor;
import vigil.shared.Completed;
import vigil.shared.Completed.ResultRef;
import vigil.shared.Ids;
import vigil.shared.Key;
import vigil.shared.StepAnswer;
import vigil.shared.StepCall;
import vigil.shared.StepCancel;
import vigil.shared.TenantId;
import vigil.shared.Timestamps;
import vigil.shared.Waiting;

/**
 * The Recovery check step (DM-S-07, PM-M15-02, PM-M15-03; P-23 spec Section 6.7): one recovery
 * verification per step execution (DM-R-64), evaluated at each call from alert states and source
 * health held by vigil-intake (DM-R-35 to DM-R-37), never from action results. A decision is stored
 * in one transaction with its audit event, its evidence entry and, when recovered and the incident
 * is open, the resolve request (DM-SM05-T4).
 */
@Component
class RecoveryStep implements RecoveryStepHandler {

  static final Actor RECOVERY = Actor.module("vigil-recovery");
  private static final Key RECOVERED = new Key("recovered");
  private static final Key NOT_RECOVERED = new Key("not_recovered");
  private static final String BAD_PARAMETERS = "recovery check step parameters: max_wait";
  private static final long MAX_WAIT_MIN = 60;
  private static final long MAX_WAIT_MAX = 86_400;

  private final RecoveryRecords records;
  private final Evaluation evaluation;
  private final IncidentService incidents;
  private final AuditService audit;
  private final TransactionTemplate tx;
  private final Clock clock;

  RecoveryStep(
      RecoveryRecords records,
      Evaluation evaluation,
      IncidentService incidents,
      AuditService audit,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.records = records;
    this.evaluation = evaluation;
    this.incidents = incidents;
    this.audit = audit;
    this.tx = new TransactionTemplate(transactionManager);
    this.clock = clock;
  }

  @Override
  public StepAnswer start(StepCall call) {
    return check(call);
  }

  @Override
  public StepAnswer evaluate(StepCall call) {
    return check(call);
  }

  /** DM-SM13-T4, in the caller's transaction: an observing verification becomes cancelled. */
  @Override
  public void cancel(StepCancel cancel) {
    Recovery.require(cancel);
    records.cancel(cancel.tenantId(), cancel.stepExecutionId(), Timestamps.now(clock));
  }

  private StepAnswer check(StepCall call) {
    Recovery.require(call);
    long maxWait = maxWait(call);
    Instant now = Timestamps.now(clock);
    return tx.execute(status -> check(call, maxWait, now));
  }

  /** Steps 2 to 6, in one transaction. */
  private StepAnswer check(StepCall call, long maxWait, Instant now) {
    TenantId tenantId = call.tenantId();
    RecoveryVerificationView verification =
        records.lockOfStep(tenantId, call.stepExecutionId()).orElse(null);
    Definition snapshot;
    Evaluation.Result result;
    if (verification == null) {
      Definition definition = definition(call);
      if (maxWait <= definition.observationWindowSeconds()) {
        throw new IllegalArgumentException(
            "recovery check step parameters: max_wait must be greater than the observation window"
                + " of "
                + definition.recoveryCheckKey());
      }
      snapshot = definition;
      result = evaluation.evaluate(tenantId, call.incidentId(), definition.conditions(), now);
      verification =
          new RecoveryVerificationView(
              Ids.newId(),
              call.incidentId(),
              call.runId(),
              call.stepExecutionId(),
              definition.recoveryCheckKey(),
              RecoveryRecords.JSON.writeValueAsString(RecoveryRecords.snapshotJson(definition)),
              VerificationState.OBSERVING,
              now,
              now.plusSeconds(maxWait),
              null,
              RecoveryRecords.JSON.writeValueAsString(result.json()),
              null);
      records.insertVerification(tenantId, verification, now);
    } else {
      switch (verification.state()) {
        case RECOVERED, NOT_RECOVERED -> {
          return answer(verification, null);
        }
        case CANCELLED ->
            throw new IllegalStateException(
                "recovery verification " + verification.id() + " is cancelled");
        case OBSERVING -> {
          // Evaluated again below.
        }
      }
      snapshot = RecoveryRecords.snapshot(verification.definitionSnapshotJson());
      result = evaluation.evaluate(tenantId, verification.incidentId(), snapshot.conditions(), now);
    }
    return decide(tenantId, verification, snapshot, result, now);
  }

  /** Step 5: the window, the decision and the row; step 6: the answer. */
  private StepAnswer decide(
      TenantId tenantId,
      RecoveryVerificationView verification,
      Definition snapshot,
      Evaluation.Result result,
      Instant now) {
    Duration window = Duration.ofSeconds(snapshot.observationWindowSeconds());
    Instant windowStartedAt = evaluation.window(tenantId, result, verification.windowStartedAt());
    VerificationState state = VerificationState.OBSERVING;
    if (windowStartedAt != null && !now.isBefore(windowStartedAt.plus(window))) {
      state = VerificationState.RECOVERED;
    } else if (!now.isBefore(verification.maxWaitUntil())) {
      state = VerificationState.NOT_RECOVERED;
    }
    Instant decidedAt = state == VerificationState.OBSERVING ? null : now;
    String lastEvaluation = RecoveryRecords.JSON.writeValueAsString(result.json());
    records.updateVerification(
        tenantId, verification.id(), lastEvaluation, windowStartedAt, state, decidedAt, now);
    RecoveryVerificationView updated =
        new RecoveryVerificationView(
            verification.id(),
            verification.incidentId(),
            verification.runId(),
            verification.stepExecutionId(),
            verification.recoveryCheckKey(),
            verification.definitionSnapshotJson(),
            state,
            verification.startedAt(),
            verification.maxWaitUntil(),
            windowStartedAt,
            lastEvaluation,
            decidedAt);
    if (state != VerificationState.OBSERVING) {
      record(tenantId, updated, snapshot, result);
    }
    return answer(updated, window);
  }

  /** The decision's audit event, evidence entry and, when recovered, the resolve request. */
  private void record(
      TenantId tenantId,
      RecoveryVerificationView verification,
      Definition snapshot,
      Evaluation.Result result) {
    boolean recovered = verification.state() == VerificationState.RECOVERED;
    UUID incidentId = verification.incidentId();
    Duration window = Duration.ofSeconds(snapshot.observationWindowSeconds());
    ObjectNode details = RecoveryRecords.JSON.createObjectNode();
    details.put("outcome", verification.state().key());
    audit.append(
        new AuditEntry(
            tenantId,
            verification.decidedAt(),
            RECOVERY,
            AuditEventType.RECOVERY_VERIFIED,
            "RecoveryVerification",
            verification.id(),
            incidentId,
            RecoveryTexts.auditSummary(recovered, snapshot.name()),
            RecoveryRecords.JSON.writeValueAsString(details)));
    String summary =
        recovered
            ? RecoveryTexts.recovered(snapshot.name(), window)
            : RecoveryTexts.notRecovered(
                Duration.between(verification.startedAt(), verification.maxWaitUntil()),
                result.textsNotHolding(),
                window);
    ObjectNode content = RecoveryRecords.JSON.createObjectNode();
    content.set("definition_snapshot", RecoveryRecords.snapshotJson(snapshot));
    if (verification.windowStartedAt() == null) {
      content.putNull("window_started_at");
    } else {
      content.put("window_started_at", verification.windowStartedAt().toString());
    }
    content.put("decided_at", verification.decidedAt().toString());
    content.set("last_evaluation", result.json());
    incidents.addEvidence(
        tenantId,
        RECOVERY,
        incidentId,
        EvidenceKind.RECOVERY_VERIFICATION,
        verification.id(),
        RecoveryTexts.sourceSystem(result.activeSourceNames()),
        verification.decidedAt(),
        summary,
        RecoveryRecords.JSON.writeValueAsString(content),
        DataCategory.INCIDENT_RECORD);
    if (recovered
        && incidents.incident(tenantId, incidentId).orElseThrow().state() == IncidentState.OPEN) {
      incidents.resolve(tenantId, RECOVERY, incidentId, verification.id());
    }
  }

  /** Step 6; {@code window} is needed only while observing. */
  private StepAnswer answer(RecoveryVerificationView verification, Duration window) {
    ResultRef ref = new ResultRef("vigil-recovery", "RecoveryVerification", verification.id());
    return switch (verification.state()) {
      case RECOVERED -> new Completed(RECOVERED, ref);
      case NOT_RECOVERED -> new Completed(NOT_RECOVERED, ref);
      default -> {
        Instant due = verification.maxWaitUntil();
        if (verification.windowStartedAt() != null) {
          Instant end = verification.windowStartedAt().plus(window);
          due = end.isBefore(due) ? end : due;
        }
        yield new Waiting(
            Set.of(Waiting.WakeEvent.ALERT_STATE_CHANGED, Waiting.WakeEvent.SOURCE_HEALTH_CHANGED),
            due,
            null);
      }
    };
  }

  /** DM-R-34: the active definition with the call's key, else the active default. */
  private Definition definition(StepCall call) {
    TenantId tenantId = call.tenantId();
    if (call.recoveryCheckKey() != null) {
      String key = call.recoveryCheckKey().value();
      return records
          .definition(tenantId, key)
          .filter(Definition::active)
          .orElseThrow(() -> new IllegalStateException("recovery check " + key + " not found"));
    }
    return records
        .activeDefault(tenantId)
        .orElseThrow(() -> new IllegalStateException("no default recovery check"));
  }

  /** Step 1: the step type and {@code max_wait} in seconds, 60 to 86,400. */
  private static long maxWait(StepCall call) {
    if (call.stepType() != StepCall.StepType.RECOVERY_CHECK) {
      throw new IllegalArgumentException(BAD_PARAMETERS);
    }
    JsonNode tree;
    try {
      tree = RecoveryRecords.JSON.readTree(call.parameters());
    } catch (JacksonException e) {
      throw new IllegalArgumentException(BAD_PARAMETERS, e);
    }
    JsonNode maxWait = tree == null || !tree.isObject() ? null : tree.get("max_wait");
    if (maxWait == null
        || !maxWait.isIntegralNumber()
        || !maxWait.canConvertToLong()
        || maxWait.longValue() < MAX_WAIT_MIN
        || maxWait.longValue() > MAX_WAIT_MAX) {
      throw new IllegalArgumentException(BAD_PARAMETERS);
    }
    return maxWait.longValue();
  }
}
