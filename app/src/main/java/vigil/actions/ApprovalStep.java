package vigil.actions;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import vigil.actions.ActionRecords.Proposal;
import vigil.actions.api.ApprovalRequestView;
import vigil.actions.api.PolicyOutcome;
import vigil.actions.api.RefusalReason;
import vigil.actions.api.RequestState;
import vigil.audit.api.AuditEntry;
import vigil.audit.api.AuditEventType;
import vigil.audit.api.AuditService;
import vigil.identity.api.IdentityService;
import vigil.identity.api.Permission;
import vigil.identity.api.TeamView;
import vigil.identity.api.UserView;
import vigil.incidents.api.DataCategory;
import vigil.incidents.api.EvidenceKind;
import vigil.incidents.api.IncidentService;
import vigil.incidents.api.IncidentView;
import vigil.knowledge.api.KnowledgeService;
import vigil.notifications.api.ApprovalRecipient;
import vigil.notifications.api.NotificationsService;
import vigil.notifications.api.SendOutcome;
import vigil.recovery.api.RecoveryCheckView;
import vigil.recovery.api.RecoveryService;
import vigil.servicemap.api.ComponentKind;
import vigil.servicemap.api.ComponentView;
import vigil.servicemap.api.ServiceMapService;
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
import vigil.shared.TransactionGuard;
import vigil.shared.Waiting;

/**
 * The Approval step (DM-S-05, DM-R-29, DM-R-30, DM-SM-11; P-24 spec Section 6.6): one approval
 * request per step execution (DM-R-64), created with the run's proposal, the refusal or the policy
 * check, its snapshot and hash; the messages to every eligible approver; expiry and cancellation.
 */
@Component
class ApprovalStep {

  static final Actor ACTIONS = StateChecks.ACTIONS;
  static final Key APPROVED = new Key("approved");
  static final Key REJECTED = new Key("rejected");
  static final Key EXPIRED = new Key("expired");
  private static final Logger LOG = LoggerFactory.getLogger(ApprovalStep.class);
  private static final String BAD_PARAMETERS =
      "approval step parameters: eligible_teams and expiry";
  private static final long EXPIRY_MIN = 60;
  private static final long EXPIRY_MAX = 86_400;

  /** The checked parameters. */
  private record Parameters(List<String> eligibleTeams, long expiry) {}

  private final ActionRecords records;
  private final Proposals proposals;
  private final IncidentService incidents;
  private final IdentityService identity;
  private final NotificationsService notifications;
  private final KnowledgeService knowledge;
  private final ServiceMapService serviceMap;
  private final RecoveryService recovery;
  private final AuditService audit;
  private final TransactionTemplate tx;
  private final Clock clock;

  ApprovalStep(
      ActionRecords records,
      Proposals proposals,
      IncidentService incidents,
      IdentityService identity,
      NotificationsService notifications,
      KnowledgeService knowledge,
      ServiceMapService serviceMap,
      RecoveryService recovery,
      AuditService audit,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.records = records;
    this.proposals = proposals;
    this.incidents = incidents;
    this.identity = identity;
    this.notifications = notifications;
    this.knowledge = knowledge;
    this.serviceMap = serviceMap;
    this.recovery = recovery;
    this.audit = audit;
    this.tx = new TransactionTemplate(transactionManager);
    this.clock = clock;
  }

  /** {@code start} and {@code evaluate}: steps 1 to 6 of Section 6.6. */
  StepAnswer run(StepCall call) {
    TransactionGuard.assertNoTransaction();
    Parameters parameters = parameters(call);
    TenantId tenantId = call.tenantId();
    ApprovalRequestView request =
        records.requestOfStep(tenantId, call.stepExecutionId()).orElse(null);
    if (request == null) {
      if (records.withoutProposal(tenantId, "step_execution_id", call.stepExecutionId())) {
        return new Completed(REJECTED, null);
      }
      request = tx.execute(status -> first(call, parameters));
      if (request == null) {
        return new Completed(REJECTED, null);
      }
    }
    if (request.state() == RequestState.PENDING
        && Timestamps.now(clock).isBefore(request.expiresAt())) {
      message(tenantId, request);
    }
    UUID id = request.id();
    return tx.execute(status -> answer(tenantId, id));
  }

  /** DM-SM11-T8: a pending request of the step execution becomes cancelled. */
  void cancel(StepCancel cancel) {
    records.cancelPending(cancel.tenantId(), cancel.stepExecutionId(), Timestamps.now(clock));
  }

  /** Step 3, in one transaction: the request, or {@code null} for "no proposal". */
  private ApprovalRequestView first(StepCall call, Parameters parameters) {
    TenantId tenantId = call.tenantId();
    Instant now = Timestamps.now(clock);
    IncidentView incident =
        incidents
            .incident(tenantId, call.incidentId())
            .orElseThrow(
                () -> new IllegalStateException("incident " + call.incidentId() + " not found"));
    Optional<Proposal> proposal = records.proposalOfRun(tenantId, call.runId());
    if (proposal.isEmpty()
        && records.requestCount(tenantId, call.runId()) == 0
        && !records.withoutProposal(tenantId, "run_id", call.runId())) {
      proposal = proposals.propose(call, incident);
      proposal.ifPresent(p -> records.insertProposal(tenantId, p, now));
    }
    if (proposal.isEmpty()) {
      records.insertWithoutProposal(
          tenantId, call.runId(), call.incidentId(), call.stepExecutionId(), now);
      ObjectNode details = ActionRecords.JSON.createObjectNode();
      details.put("decision", "no_proposal");
      details.put("run_id", call.runId().toString());
      audit.append(
          new AuditEntry(
              tenantId,
              now,
              ACTIONS,
              AuditEventType.APPROVAL_DECIDED,
              "StepExecution",
              call.stepExecutionId(),
              call.incidentId(),
              ActionTexts.NO_PROPOSAL,
              ActionRecords.JSON.writeValueAsString(details)));
      return null;
    }
    Proposal p = proposal.get();
    JsonNode action = ActionRecords.JSON.readTree(p.actionSnapshotJson());
    long executed = records.executedCount(tenantId, p.incidentId(), p.actionKey());
    Instant expiresAt = now.plusSeconds(parameters.expiry());
    ObjectNode snapshot =
        Snapshots.approval(
            action,
            p.targetKey(),
            targetName(tenantId, p.targetKey()),
            executed + 1,
            expiresAt,
            incidents.evidence(tenantId, incident.id()),
            knowledge
                .pastIncident(tenantId, p.pastIncidentKey())
                .orElseThrow(
                    () ->
                        new IllegalStateException(
                            "past incident " + p.pastIncidentKey() + " not found")),
            p.score(),
            p.successCount(),
            recoveryCheck(tenantId, action.get("recovery_check_key").stringValue()));
    RequestState state;
    PolicyOutcome outcome = null;
    RefusalReason reason = null;
    if (executed >= action.get("attempt_limit").longValue()) {
      state = RequestState.REFUSED;
      reason = RefusalReason.ATTEMPT_LIMIT_REACHED;
    } else if (records.anyUnresolved(tenantId, p.incidentId(), p.actionKey())) {
      state = RequestState.REFUSED;
      reason = RefusalReason.UNRESOLVED_EXECUTION;
    } else {
      outcome = proposals.check(tenantId, p.actionKey(), incident);
      state =
          switch (outcome) {
            case BLOCKED -> RequestState.BLOCKED;
            case PREAUTHORIZED -> RequestState.PREAUTHORIZED;
            case APPROVAL_REQUIRED -> RequestState.PENDING;
          };
    }
    List<String> eligible =
        parameters.eligibleTeams().stream().sorted(ActionTexts.CODE_POINTS).toList();
    List<UUID> excluded =
        call.earlierApprovers().stream()
            .distinct()
            .sorted((a, b) -> a.toString().compareTo(b.toString()))
            .toList();
    ApprovalRequestView request =
        new ApprovalRequestView(
            Ids.newId(),
            call.incidentId(),
            call.runId(),
            call.stepExecutionId(),
            p.id(),
            records.requestCount(tenantId, call.runId()) + 1,
            outcome,
            eligible,
            excluded,
            ActionRecords.JSON.writeValueAsString(snapshot),
            Snapshots.hash(snapshot),
            expiresAt,
            state,
            reason,
            state == RequestState.PENDING ? null : now,
            null,
            now);
    records.insertRequest(tenantId, request);
    if (outcome != null) {
      ObjectNode details = ActionRecords.JSON.createObjectNode();
      details.put("outcome", outcome.key());
      append(
          tenantId,
          request,
          ACTIONS,
          AuditEventType.POLICY_CHECK_RESULT,
          ActionTexts.policyCheckAudit(actionName(request), outcome),
          details,
          now);
    }
    if (state == RequestState.PENDING) {
      append(
          tenantId,
          request,
          ACTIONS,
          AuditEventType.APPROVAL_REQUESTED,
          ActionTexts.approvalRequestedAudit(
              request.sequenceInRun(),
              actionName(request),
              targetName(request),
              teamNames(tenantId, eligible)),
          null,
          now);
    } else {
      decided(tenantId, request, null);
    }
    return request;
  }

  /**
   * The audit event {@code approval_decided} and the evidence entry of a request that reached a
   * final state other than {@code cancelled}; {@code user} for a person's decision, else {@code
   * null}. In the caller's transaction.
   */
  void decided(TenantId tenantId, ApprovalRequestView request, UUID user) {
    String userName = user == null ? null : userName(tenantId, user);
    ObjectNode details = ActionRecords.JSON.createObjectNode();
    details.put("decision", request.state().key());
    if (user != null) {
      details.put("user_id", user.toString());
    }
    append(
        tenantId,
        request,
        user == null ? ACTIONS : Actor.user(user),
        AuditEventType.APPROVAL_DECIDED,
        ActionTexts.approvalDecidedAudit(
            request.sequenceInRun(),
            request.state(),
            request.refusalReason(),
            userName,
            actionName(request),
            targetName(request)),
        details,
        request.decidedAt());
    ObjectNode content = ActionRecords.JSON.createObjectNode();
    content.put("approval_request_id", request.id().toString());
    content.put("state", request.state().key());
    content.put("sequence_in_run", request.sequenceInRun());
    content.put(
        "policy_outcome", request.policyOutcome() == null ? null : request.policyOutcome().key());
    content.put("decided_by", request.decidedBy() == null ? null : request.decidedBy().toString());
    content.put("decided_at", request.decidedAt().toString());
    JsonNode snapshot = ActionRecords.JSON.readTree(request.snapshotJson());
    content.set("action_key", snapshot.get("action_key"));
    content.set("target_key", snapshot.get("target_key"));
    content.put("snapshot_hash", request.snapshotHash());
    incidents.addEvidence(
        tenantId,
        ACTIONS,
        request.incidentId(),
        EvidenceKind.APPROVAL,
        request.id(),
        ActionTexts.approvalSource(request.sequenceInRun()),
        request.decidedAt(),
        ActionTexts.approvalSummary(
            request.state(),
            request.refusalReason(),
            userName,
            actionName(request),
            targetName(request)),
        ActionRecords.JSON.writeValueAsString(content),
        DataCategory.INCIDENT_RECORD);
  }

  /** Step 4: the messages, with no transaction open. */
  private void message(TenantId tenantId, ApprovalRequestView request) {
    Map<String, String> viaTeam = new LinkedHashMap<>();
    Set<UUID> excluded = new HashSet<>(request.excludedUserIds());
    for (String team : request.eligibleTeamKeys()) {
      for (UserView user : identity.teamMembers(tenantId, new Key(team))) {
        if (user.active()
            && user.permissions().contains(Permission.APPROVE_ACTION)
            && !excluded.contains(user.id())) {
          viaTeam.putIfAbsent(user.username().value(), team);
        }
      }
    }
    if (viaTeam.isEmpty()) {
      return;
    }
    List<ApprovalRecipient> recipients = new ArrayList<>();
    viaTeam.keySet().stream()
        .sorted(ActionTexts.CODE_POINTS)
        .forEach(
            username ->
                recipients.add(
                    new ApprovalRecipient(new Key(username), new Key(viaTeam.get(username)))));
    SendOutcome outcome =
        notifications.notifyApprovalRequest(
            tenantId,
            request.incidentId(),
            request.id(),
            List.copyOf(recipients),
            actionName(request),
            targetName(request),
            request.expiresAt());
    if (outcome == SendOutcome.FAILED) {
      LOG.warn(
          "approval request {} of incident {} of tenant {}: the messages failed",
          request.id(),
          request.incidentId(),
          tenantId.value());
    }
  }

  /** Step 6, in one transaction: the request locked, expired when due, and the answer. */
  private StepAnswer answer(TenantId tenantId, UUID id) {
    ApprovalRequestView request = records.lockRequest(tenantId, id).orElseThrow();
    Instant now = Timestamps.now(clock);
    ResultRef ref = new ResultRef("vigil-actions", "ApprovalRequest", id);
    return switch (request.state()) {
      case PENDING -> {
        if (now.isBefore(request.expiresAt())) {
          yield new Waiting(Set.of(Waiting.WakeEvent.APPROVAL_DECIDED), request.expiresAt(), null);
        }
        records.decideRequest(tenantId, id, RequestState.EXPIRED, now, null);
        decided(tenantId, records.request(tenantId, id).orElseThrow(), null);
        yield new Completed(EXPIRED, ref);
      }
      case APPROVED, PREAUTHORIZED -> new Completed(APPROVED, ref);
      case REJECTED, BLOCKED, REFUSED -> new Completed(REJECTED, ref);
      case EXPIRED -> new Completed(EXPIRED, ref);
      case CANCELLED -> throw new IllegalStateException("approval request " + id + " is cancelled");
    };
  }

  private void append(
      TenantId tenantId,
      ApprovalRequestView request,
      Actor actor,
      AuditEventType type,
      String summary,
      ObjectNode details,
      Instant at) {
    audit.append(
        new AuditEntry(
            tenantId,
            at,
            actor,
            type,
            "ApprovalRequest",
            request.id(),
            request.incidentId(),
            summary,
            details == null ? null : ActionRecords.JSON.writeValueAsString(details)));
  }

  /** The name of the {@code server} component whose target is the key; else the key. */
  String targetName(TenantId tenantId, String targetKey) {
    return serviceMap.components(tenantId).stream()
        .filter(c -> c.kind() == ComponentKind.SERVER && targetKey.equals(c.targetKey()))
        .map(ComponentView::name)
        .findFirst()
        .orElse(targetKey);
  }

  private RecoveryCheckView recoveryCheck(TenantId tenantId, String key) {
    return recovery
        .definition(tenantId, key)
        .orElseThrow(() -> new IllegalStateException("recovery check " + key + " not found"));
  }

  String userName(TenantId tenantId, UUID user) {
    return identity.user(tenantId, user).map(UserView::displayName).orElse(user.toString());
  }

  List<String> teamNames(TenantId tenantId, List<String> teamKeys) {
    return teamKeys.stream()
        .map(key -> identity.team(tenantId, new Key(key)).map(TeamView::name).orElse(key))
        .toList();
  }

  static String actionName(ApprovalRequestView request) {
    return ActionRecords.JSON.readTree(request.snapshotJson()).get("action_name").stringValue();
  }

  static String targetName(ApprovalRequestView request) {
    return ActionRecords.JSON.readTree(request.snapshotJson()).get("target_name").stringValue();
  }

  /** Step 1: the step type and the parameters. */
  private static Parameters parameters(StepCall call) {
    if (call.stepType() != StepCall.StepType.APPROVAL) {
      throw new IllegalArgumentException(BAD_PARAMETERS);
    }
    JsonNode tree;
    try {
      tree = ActionRecords.JSON.readTree(call.parameters());
    } catch (JacksonException e) {
      throw new IllegalArgumentException(BAD_PARAMETERS, e);
    }
    JsonNode teams = tree == null || !tree.isObject() ? null : tree.get("eligible_teams");
    JsonNode expiry = tree == null || !tree.isObject() ? null : tree.get("expiry");
    if (teams == null
        || !teams.isArray()
        || teams.isEmpty()
        || expiry == null
        || !expiry.isIntegralNumber()
        || !expiry.canConvertToLong()
        || expiry.longValue() < EXPIRY_MIN
        || expiry.longValue() > EXPIRY_MAX) {
      throw new IllegalArgumentException(BAD_PARAMETERS);
    }
    List<String> keys = new ArrayList<>();
    for (JsonNode team : teams) {
      if (!team.isString()
          || !ActionsImporter.KEY.matcher(team.stringValue()).matches()
          || keys.contains(team.stringValue())) {
        throw new IllegalArgumentException(BAD_PARAMETERS);
      }
      keys.add(team.stringValue());
    }
    return new Parameters(List.copyOf(keys), expiry.longValue());
  }
}
