package vigil.incidents;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vigil.audit.api.AuditEntry;
import vigil.audit.api.AuditEventType;
import vigil.audit.api.AuditService;
import vigil.identity.api.IdentityService;
import vigil.identity.api.Permission;
import vigil.identity.api.TeamView;
import vigil.identity.api.UserView;
import vigil.incidents.api.AcknowledgementView;
import vigil.incidents.api.AffectedServiceCopy;
import vigil.incidents.api.AiStatus;
import vigil.incidents.api.AiUnavailableReason;
import vigil.incidents.api.AlertCopy;
import vigil.incidents.api.DataCategory;
import vigil.incidents.api.EscalationCause;
import vigil.incidents.api.EscalationView;
import vigil.incidents.api.EvidenceKind;
import vigil.incidents.api.EvidenceView;
import vigil.incidents.api.FinalReason;
import vigil.incidents.api.GroupingReason;
import vigil.incidents.api.HypothesisCopy;
import vigil.incidents.api.IncidentAcknowledged;
import vigil.incidents.api.IncidentChanged;
import vigil.incidents.api.IncidentCreated;
import vigil.incidents.api.IncidentService;
import vigil.incidents.api.IncidentSettingsView;
import vigil.incidents.api.IncidentState;
import vigil.incidents.api.IncidentStateChanged;
import vigil.incidents.api.IncidentView;
import vigil.incidents.api.MembershipView;
import vigil.incidents.api.NotifiedPurpose;
import vigil.incidents.api.NotifiedTeamView;
import vigil.incidents.api.Severity;
import vigil.incidents.api.TimelineEntryType;
import vigil.incidents.api.TimelineView;
import vigil.incidents.api.TransitionView;
import vigil.shared.Actor;
import vigil.shared.Ids;
import vigil.shared.Key;
import vigil.shared.TenantId;
import vigil.shared.Timestamps;
import vigil.shared.VigilError;

/** The requests of other modules to vigil-incidents (PM-M09; P-14 spec Sections 6.6, 6.7). */
@Service
class Incidents implements IncidentService {

  private static final Logger LOG = LoggerFactory.getLogger(Incidents.class);

  static final String INCIDENTS = "vigil-incidents";
  private static final String CORRELATION = "vigil-correlation";
  private static final String ROUTING = "vigil-routing";
  private static final String NOTIFICATIONS = "vigil-notifications";
  private static final String INVESTIGATION = "vigil-investigation";
  private static final String RECOVERY = "vigil-recovery";
  private static final String INCIDENT = "Incident";
  private static final String MEMBERSHIP = "IncidentAlertMembership";
  private static final String ESCALATION = "IncidentEscalation";
  private static final String ACKNOWLEDGEMENT = "Acknowledgement";
  private static final String EVIDENCE = "EvidenceEntry";
  private static final String NOTIFIED_TEAM = "NotifiedTeam";
  private static final int TEXT_MAX = 300;
  private static final int RULE_NAME_MAX = 100;
  private static final int SUMMARY_MAX = 200;
  private static final int SOURCE_SYSTEM_MAX = 200;
  private static final Map<EvidenceKind, String> PRODUCERS =
      Map.of(
          EvidenceKind.DIAGNOSTIC_RESULT,
          "vigil-diagnostics",
          EvidenceKind.AI_INVESTIGATION,
          INVESTIGATION,
          EvidenceKind.SIMILARITY_MATCH,
          INVESTIGATION,
          EvidenceKind.APPROVAL,
          "vigil-actions",
          EvidenceKind.ACTION_EXECUTION,
          "vigil-actions",
          EvidenceKind.RECOVERY_VERIFICATION,
          RECOVERY);

  private final IncidentRecords records;
  private final IdentityService identity;
  private final AuditService audit;
  private final ApplicationEventPublisher publisher;
  private final Clock clock;

  Incidents(
      IncidentRecords records,
      IdentityService identity,
      AuditService audit,
      ApplicationEventPublisher publisher,
      Clock clock) {
    this.records = records;
    this.identity = identity;
    this.audit = audit;
    this.publisher = publisher;
    this.clock = clock;
  }

  /** One request: its tenant, actor and time, and the events it publishes at its end. */
  private final class Change {

    final TenantId tenantId;
    final Actor actor;
    final Instant now;
    final List<Object> events = new ArrayList<>();

    Change(TenantId tenantId, Actor actor) {
      this(tenantId, actor, Timestamps.now(clock));
    }

    Change(TenantId tenantId, Actor actor, Instant now) {
      this.tenantId = tenantId;
      this.actor = actor;
      this.now = now;
    }

    void timeline(UUID incidentId, TimelineEntryType type, String text) {
      timeline(incidentId, type, text, null, null);
    }

    void timeline(UUID incidentId, TimelineEntryType type, String text, String refType, UUID id) {
      records.insertTimeline(
          tenantId, incidentId, now, actor, type, IncidentTexts.cut(text, TEXT_MAX), refType, id);
    }

    void audit(
        AuditEventType type,
        String subjectType,
        UUID subjectId,
        UUID incidentId,
        String text,
        String details) {
      Incidents.this.audit.append(
          new AuditEntry(
              tenantId,
              now,
              actor,
              type,
              subjectType,
              subjectId,
              incidentId,
              IncidentTexts.cut(text, TEXT_MAX),
              details));
    }

    /**
     * A state change of the row (DM-E-33): the transition record, the timeline entry, the audit
     * event, {@code IncidentStateChanged}; the caller writes the row.
     */
    void transition(IncidentRow row, IncidentState to, String transitionId, String text) {
      IncidentState from = row.state;
      String reason = IncidentTexts.cut(text, TEXT_MAX);
      records.insertTransition(tenantId, row.id, transitionId, from, to, now, actor, reason);
      row.state = to;
      row.stateChangedAt = now;
      row.updatedAt = now;
      timeline(row.id, TimelineEntryType.STATE_CHANGED, text);
      audit(
          AuditEventType.INCIDENT_STATE_CHANGED,
          INCIDENT,
          row.id,
          row.id,
          IncidentTexts.auditStateChanged(from, to),
          "{\"from\": \"" + from.key() + "\", \"to\": \"" + to.key() + "\"}");
      events.add(new IncidentStateChanged(tenantId, row.id, from, to));
      logTransition(tenantId, row.id, from, to);
    }

    void changed(UUID incidentId) {
      events.add(new IncidentChanged(tenantId, incidentId));
    }

    void publish() {
      events.forEach(publisher::publishEvent);
    }
  }

  // Requests of vigil-correlation.

  @Override
  @Transactional(propagation = Propagation.REQUIRED)
  public UUID create(
      TenantId tenantId,
      Actor actor,
      UUID groupingDecisionId,
      AlertCopy alert,
      GroupingReason reason) {
    required(tenantId, groupingDecisionId, alert, reason);
    decision(reason, "created_incident");
    requireModule(actor, "create", CORRELATION);
    Change change = new Change(tenantId, actor);
    records.advisoryLock("incidents.sequence", tenantId);
    requireNoMembership(tenantId, alert.alertId());
    IncidentRow row = new IncidentRow();
    row.id = Ids.newId();
    row.sequence = records.lastSequence(tenantId) + 1;
    row.state = IncidentState.FORMING;
    row.stateChangedAt = change.now;
    row.firstAlertFiredAt = alert.firedAt();
    row.lastAlertJoinedAt = change.now;
    row.alertCount = 1;
    row.alertTypes = List.of(alert.alertType());
    row.alertSources = List.of(alert.sourceKind());
    row.title = IncidentTitle.title(List.of(alert), null, 0);
    row.aiStatus = AiStatus.NOT_RUN;
    row.createdAt = change.now;
    row.updatedAt = change.now;
    records.insertIncident(tenantId, row);
    records.insertMembership(
        tenantId,
        row.id,
        membership(alert, groupingDecisionId, IncidentJson.reason(reason), null),
        change.now);
    String text = IncidentTexts.created(alert);
    records.insertTransition(
        tenantId,
        row.id,
        "DM-SM05-T1",
        null,
        IncidentState.FORMING,
        change.now,
        actor,
        IncidentTexts.cut(text, TEXT_MAX));
    logTransition(tenantId, row.id, null, IncidentState.FORMING);
    change.timeline(row.id, TimelineEntryType.CREATED, text);
    change.audit(AuditEventType.INCIDENT_CREATED, INCIDENT, row.id, row.id, text, null);
    change.events.add(new IncidentCreated(tenantId, row.id));
    change.changed(row.id);
    change.publish();
    return row.id;
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED)
  public void addAlert(
      TenantId tenantId,
      Actor actor,
      UUID incidentId,
      UUID groupingDecisionId,
      AlertCopy alert,
      GroupingReason reason) {
    required(tenantId, incidentId, groupingDecisionId, alert, reason);
    decision(reason, "joined");
    requireModule(actor, "addAlert", CORRELATION);
    Change change = new Change(tenantId, actor);
    IncidentRow row = lock(tenantId, incidentId);
    guard(row, "addAlert", IncidentState.FORMING, IncidentState.OPEN);
    requireNoMembership(tenantId, alert.alertId());
    String finalReason = row.state == IncidentState.OPEN ? IncidentJson.finalReason(reason) : null;
    UUID membershipId =
        records.insertMembership(
            tenantId,
            row.id,
            membership(alert, groupingDecisionId, IncidentJson.reason(reason), finalReason),
            change.now);
    row.lastAlertJoinedAt = change.now;
    row.updatedAt = change.now;
    boolean titleChanged = derive(tenantId, row);
    records.updateIncident(tenantId, row);
    change.timeline(
        row.id,
        TimelineEntryType.ALERT_JOINED,
        IncidentTexts.alertJoined(alert),
        MEMBERSHIP,
        membershipId);
    titleEntry(change, row, titleChanged);
    change.changed(row.id);
    change.publish();
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED)
  public void merge(TenantId tenantId, Actor actor, UUID survivorId, List<UUID> absorbedIds) {
    required(tenantId, survivorId, absorbedIds);
    if (absorbedIds.isEmpty()
        || absorbedIds.stream().anyMatch(id -> id == null || id.equals(survivorId))
        || new HashSet<>(absorbedIds).size() != absorbedIds.size()) {
      throw new IllegalArgumentException(
          "absorbedIds: at least one, none equal to the survivor or repeated");
    }
    requireModule(actor, "merge", CORRELATION);
    Change change = new Change(tenantId, actor);
    List<UUID> ids = new ArrayList<>(absorbedIds);
    ids.add(survivorId);
    Map<UUID, IncidentRow> rows =
        records.lockIncidents(tenantId, ids).stream()
            .collect(Collectors.toMap(row -> row.id, row -> row));
    if (rows.size() != ids.size()) {
      throw new VigilError(VigilError.Code.NOT_FOUND);
    }
    for (UUID id : ids) {
      guard(rows.get(id), "merge", IncidentState.FORMING);
    }
    IncidentRow survivor = rows.get(survivorId);
    List<Integer> moved = new ArrayList<>();
    for (UUID absorbedId : absorbedIds) {
      IncidentRow absorbed = rows.get(absorbedId);
      List<MembershipView> members = records.memberships(tenantId, absorbed.id, true);
      for (MembershipView member : members) {
        records.endMembership(tenantId, member.id(), change.now);
        records.insertMembership(
            tenantId,
            survivor.id,
            membership(
                member.alert(),
                member.groupingDecisionId(),
                IncidentJson.movedByMerge(absorbed.id, member.reasonJson()),
                null),
            change.now);
      }
      moved.add(members.size());
      absorbed.mergedIntoIncidentId = survivor.id;
      change.transition(absorbed, IncidentState.MERGED, "DM-SM05-T3", IncidentTexts.MERGED);
      records.updateIncident(tenantId, absorbed);
      change.changed(absorbed.id);
      if (absorbed.lastAlertJoinedAt.isAfter(survivor.lastAlertJoinedAt)) {
        survivor.lastAlertJoinedAt = absorbed.lastAlertJoinedAt;
      }
    }
    survivor.updatedAt = change.now;
    boolean titleChanged = derive(tenantId, survivor);
    records.updateIncident(tenantId, survivor);
    for (int n : moved) {
      change.timeline(survivor.id, TimelineEntryType.MERGED_IN, IncidentTexts.mergedIn(n));
    }
    titleEntry(change, survivor, titleChanged);
    change.changed(survivor.id);
    change.publish();
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED)
  public void setHypothesisAndImpact(
      TenantId tenantId,
      Actor actor,
      UUID incidentId,
      HypothesisCopy hypothesis,
      UUID hypothesisComputationId,
      List<AffectedServiceCopy> affectedServices) {
    required(tenantId, incidentId, affectedServices);
    if (affectedServices.stream().anyMatch(Objects::isNull)) {
      throw new IllegalArgumentException("affectedServices: no null entries");
    }
    if ((hypothesis == null) != (hypothesisComputationId == null)) {
      throw new IllegalArgumentException("hypothesis and hypothesisComputationId: both or neither");
    }
    requireModule(actor, "setHypothesisAndImpact", CORRELATION);
    Change change = new Change(tenantId, actor);
    IncidentRow row = lock(tenantId, incidentId);
    guard(row, "setHypothesisAndImpact", IncidentState.FORMING, IncidentState.OPEN);
    if (row.hypothesis != null && hypothesis == null) {
      throw new IllegalStateException("the hypothesis cannot be removed");
    }
    List<AffectedServiceCopy> services =
        affectedServices.stream()
            .sorted(
                Comparator.comparing(AffectedServiceCopy::serviceKey, IncidentTexts.CODE_POINTS))
            .toList();
    HypothesisCopy old = row.hypothesis;
    boolean componentChanged =
        !Objects.equals(
            old == null ? null : old.componentKey(),
            hypothesis == null ? null : hypothesis.componentKey());
    boolean impactChanged = !services.equals(row.affectedServices);
    if (!componentChanged
        && !impactChanged
        && Objects.equals(old, hypothesis)
        && Objects.equals(row.hypothesisComputationId, hypothesisComputationId)) {
      return;
    }
    row.hypothesis = hypothesis;
    row.hypothesisComputationId = hypothesisComputationId;
    row.affectedServices = services;
    row.updatedAt = change.now;
    boolean titleChanged = derive(tenantId, row);
    records.updateIncident(tenantId, row);
    if (componentChanged) {
      change.timeline(
          row.id,
          TimelineEntryType.HYPOTHESIS_CHANGED,
          IncidentTexts.hypothesisChanged(old == null ? null : old.name(), hypothesis.name()));
    }
    if (impactChanged) {
      change.timeline(
          row.id, TimelineEntryType.IMPACT_CHANGED, IncidentTexts.impactChanged(services));
    }
    titleEntry(change, row, titleChanged);
    change.changed(row.id);
    change.publish();
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED)
  public void open(
      TenantId tenantId, Actor actor, UUID incidentId, Map<UUID, FinalReason> finalReasons) {
    required(tenantId, incidentId, finalReasons);
    if (finalReasons.entrySet().stream()
        .anyMatch(entry -> entry.getKey() == null || entry.getValue() == null)) {
      throw new IllegalArgumentException("finalReasons: no null keys or values");
    }
    requireModule(actor, "open", CORRELATION);
    Change change = new Change(tenantId, actor);
    IncidentRow row = lock(tenantId, incidentId);
    guard(row, "open", IncidentState.FORMING);
    List<MembershipView> members = records.memberships(tenantId, row.id, true);
    Set<UUID> alertIds = members.stream().map(MembershipView::alertId).collect(Collectors.toSet());
    if (!alertIds.equals(finalReasons.keySet())) {
      throw new IllegalArgumentException(
          "finalReasons: exactly the alert ids of the active members");
    }
    records.advisoryLock("incidents.number", tenantId);
    row.number = records.lastNumber(tenantId) + 1;
    row.stableAt = change.now;
    row.assignmentFrozen = true;
    for (MembershipView member : members) {
      records.setFinalReason(
          tenantId,
          member.id(),
          IncidentJson.finalReason(finalReasons.get(member.alertId())),
          change.now);
    }
    change.transition(row, IncidentState.OPEN, "DM-SM05-T2", IncidentTexts.opened(row.number));
    records.updateIncident(tenantId, row);
    change.changed(row.id);
    change.publish();
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED)
  public void reopen(
      TenantId tenantId,
      Actor actor,
      UUID incidentId,
      UUID groupingDecisionId,
      AlertCopy alert,
      GroupingReason reason) {
    required(tenantId, incidentId, groupingDecisionId, alert, reason);
    decision(reason, "reopened");
    requireModule(actor, "reopen", CORRELATION);
    Change change = new Change(tenantId, actor);
    IncidentRow row = lock(tenantId, incidentId);
    guard(row, "reopen", IncidentState.RESOLVED);
    requireNoMembership(tenantId, alert.alertId());
    records.insertMembership(
        tenantId,
        row.id,
        membership(
            alert,
            groupingDecisionId,
            IncidentJson.reason(reason),
            IncidentJson.finalReason(reason)),
        change.now);
    row.reopenCount++;
    row.resolvedAt = null;
    row.resolvedByVerificationId = null;
    row.acknowledgedAt = null;
    row.acknowledgedBy = null;
    row.escalationLevel = 0;
    row.lastAlertJoinedAt = change.now;
    boolean titleChanged = derive(tenantId, row);
    change.transition(row, IncidentState.OPEN, "DM-SM05-T5", IncidentTexts.reopened(alert));
    records.updateIncident(tenantId, row);
    titleEntry(change, row, titleChanged);
    change.changed(row.id);
    change.publish();
  }

  // Requests of vigil-routing and vigil-notifications.

  @Override
  @Transactional(propagation = Propagation.REQUIRED)
  public void setAssignment(
      TenantId tenantId,
      Actor actor,
      UUID incidentId,
      Severity severity,
      Key ownerTeamKey,
      Set<Key> informedTeamKeys,
      UUID routingEvaluationId,
      String ruleName) {
    required(tenantId, incidentId, severity, ownerTeamKey);
    keys(informedTeamKeys, "informedTeamKeys");
    required(routingEvaluationId, ruleName);
    if (ruleName.isEmpty() || IncidentTexts.length(ruleName) > RULE_NAME_MAX) {
      throw new IllegalArgumentException("ruleName: 1 to 100 characters");
    }
    requireModule(actor, "setAssignment", ROUTING);
    Change change = new Change(tenantId, actor);
    IncidentRow row = lock(tenantId, incidentId);
    if (row.state != IncidentState.FORMING || row.assignmentFrozen) {
      throw notAllowed("setAssignment", row.state);
    }
    if (routingEvaluationId.equals(row.assignmentEvaluationId)) {
      return;
    }
    row.severity = severity;
    row.ownerTeamKey = ownerTeamKey.value();
    row.informedTeamKeys =
        informedTeamKeys.stream().map(Key::value).sorted(IncidentTexts.CODE_POINTS).toList();
    row.assignmentEvaluationId = routingEvaluationId;
    row.updatedAt = change.now;
    records.updateIncident(tenantId, row);
    String owner = teamName(tenantId, ownerTeamKey.value());
    List<String> informed =
        row.informedTeamKeys.stream().map(key -> teamName(tenantId, key)).toList();
    change.timeline(
        row.id,
        TimelineEntryType.ASSIGNMENT_CHANGED,
        IncidentTexts.assignmentChanged(severity, owner, informed, ruleName));
    change.audit(
        AuditEventType.ASSIGNMENT_CHANGED,
        INCIDENT,
        row.id,
        row.id,
        IncidentTexts.auditAssignmentChanged(severity, owner, informed, ruleName),
        null);
    change.changed(row.id);
    change.publish();
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED)
  public UUID recordEscalation(
      TenantId tenantId,
      Actor actor,
      UUID incidentId,
      long level,
      Set<Key> teamKeys,
      EscalationCause cause,
      Duration timer,
      long policyRevision) {
    required(tenantId, incidentId);
    keys(teamKeys, "teamKeys");
    required(cause);
    if (level < 1) {
      throw new IllegalArgumentException("level: 1 or more");
    }
    if ((cause == EscalationCause.ACKNOWLEDGEMENT_TIMEOUT) != (timer != null)
        || (timer != null && timer.isNegative())) {
      throw new IllegalArgumentException(
          "timer: required for acknowledgement_timeout, not for escalate_now");
    }
    requireModule(actor, "recordEscalation", ROUTING);
    Change change = new Change(tenantId, actor);
    IncidentRow row = lock(tenantId, incidentId);
    guard(row, "recordEscalation", IncidentState.OPEN);
    List<String> keys =
        teamKeys.stream().map(Key::value).sorted(IncidentTexts.CODE_POINTS).toList();
    UUID escalationId =
        records.insertEscalation(
            tenantId, row.id, level, keys, cause, timer, policyRevision, change.now);
    row.escalationLevel = level;
    row.updatedAt = change.now;
    records.updateIncident(tenantId, row);
    String text =
        IncidentTexts.escalated(
            level, keys.stream().map(key -> teamName(tenantId, key)).toList(), cause, timer);
    change.timeline(row.id, TimelineEntryType.ESCALATED, text, ESCALATION, escalationId);
    change.audit(AuditEventType.INCIDENT_ESCALATED, ESCALATION, escalationId, row.id, text, null);
    change.changed(row.id);
    change.publish();
    return escalationId;
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED)
  public void recordNotifiedTeam(
      TenantId tenantId, Actor actor, UUID incidentId, Key teamKey, NotifiedPurpose purpose) {
    required(tenantId, incidentId, teamKey, purpose);
    requireModule(actor, "recordNotifiedTeam", NOTIFICATIONS);
    Change change = new Change(tenantId, actor);
    IncidentRow row = lock(tenantId, incidentId);
    guard(row, "recordNotifiedTeam", IncidentState.OPEN, IncidentState.RESOLVED);
    if (records.notifiedTeams(tenantId, row.id).stream()
        .anyMatch(team -> team.teamKey().equals(teamKey.value()))) {
      return;
    }
    UUID id = records.insertNotifiedTeam(tenantId, row.id, teamKey.value(), purpose, change.now);
    change.timeline(
        row.id,
        TimelineEntryType.NOTIFIED_TEAM_ADDED,
        IncidentTexts.notifiedTeamAdded(teamName(tenantId, teamKey.value()), purpose),
        NOTIFIED_TEAM,
        id);
    change.changed(row.id);
    change.publish();
  }

  // Requests of the evidence producers, vigil-investigation and vigil-recovery.

  @Override
  @Transactional(propagation = Propagation.REQUIRED)
  public UUID addEvidence(
      TenantId tenantId,
      Actor actor,
      UUID incidentId,
      EvidenceKind kind,
      UUID producerRecordId,
      String sourceSystem,
      Instant collectedAt,
      String summary,
      String contentJson,
      DataCategory dataCategory) {
    required(tenantId, incidentId, kind, producerRecordId, sourceSystem);
    required(collectedAt, summary, contentJson, dataCategory);
    if (summary.isEmpty()) {
      throw new IllegalArgumentException("summary: 1 or more characters");
    }
    if (sourceSystem.isEmpty() || IncidentTexts.length(sourceSystem) > SOURCE_SYSTEM_MAX) {
      throw new IllegalArgumentException("sourceSystem: 1 to 200 characters");
    }
    if (!IncidentJson.isObject(contentJson)) {
      throw new IllegalArgumentException("contentJson: a JSON object");
    }
    if (dataCategory != category(kind)) {
      throw new IllegalArgumentException(
          "dataCategory: " + category(kind).key() + " for kind " + kind.key());
    }
    String producer = PRODUCERS.get(kind);
    requireModule(actor, "addEvidence", producer);
    Change change = new Change(tenantId, actor);
    IncidentRow row = lock(tenantId, incidentId);
    if (kind == EvidenceKind.AI_INVESTIGATION) {
      guard(row, "addEvidence", IncidentState.OPEN, IncidentState.RESOLVED, IncidentState.CLOSED);
    } else {
      guard(row, "addEvidence", IncidentState.OPEN, IncidentState.RESOLVED);
    }
    Optional<UUID> existing = records.evidenceOf(tenantId, producer, producerRecordId);
    if (existing.isPresent()) {
      return existing.get();
    }
    String stored =
        IncidentTexts.length(summary) > SUMMARY_MAX
            ? IncidentTexts.cut(summary, SUMMARY_MAX - 1) + "…"
            : summary;
    UUID id =
        records.insertEvidence(
            tenantId,
            new EvidenceView(
                null,
                row.id,
                kind,
                producer,
                producerRecordId,
                sourceSystem,
                collectedAt,
                stored,
                contentJson,
                dataCategory),
            change.now);
    change.timeline(
        row.id,
        TimelineEntryType.EVIDENCE_ADDED,
        IncidentTexts.evidenceAdded(stored),
        EVIDENCE,
        id);
    change.changed(row.id);
    change.publish();
    return id;
  }

  /** The data category of each evidence kind (DM-R-44). */
  static DataCategory category(EvidenceKind kind) {
    return switch (kind) {
      case DIAGNOSTIC_RESULT -> DataCategory.DIAGNOSTIC_OUTPUT;
      case SIMILARITY_MATCH -> DataCategory.INCIDENT_HISTORY;
      default -> DataCategory.INCIDENT_RECORD;
    };
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED)
  public void setAiStatus(
      TenantId tenantId,
      Actor actor,
      UUID incidentId,
      AiStatus status,
      AiUnavailableReason reason,
      UUID requestGroupId) {
    required(tenantId, incidentId, status, requestGroupId);
    if (status == AiStatus.NOT_RUN || (status == AiStatus.UNAVAILABLE) != (reason != null)) {
      throw new IllegalArgumentException(
          "status: available or unavailable, with a reason only for unavailable");
    }
    requireModule(actor, "setAiStatus", INVESTIGATION);
    Change change = new Change(tenantId, actor);
    IncidentRow row = lock(tenantId, incidentId);
    guard(row, "setAiStatus", IncidentState.OPEN, IncidentState.RESOLVED, IncidentState.CLOSED);
    boolean statusChanged = row.aiStatus != status || row.aiUnavailableReason != reason;
    if (!statusChanged && requestGroupId.equals(row.aiLastRequestGroupId)) {
      return;
    }
    row.aiLastRequestGroupId = requestGroupId;
    row.updatedAt = change.now;
    if (statusChanged) {
      row.aiStatus = status;
      row.aiUnavailableReason = reason;
      row.aiStatusChangedAt = change.now;
    }
    records.updateIncident(tenantId, row);
    if (statusChanged) {
      change.timeline(
          row.id,
          TimelineEntryType.AI_STATUS_CHANGED,
          IncidentTexts.aiStatusChanged(status, reason));
    }
    change.changed(row.id);
    change.publish();
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED)
  public void resolve(TenantId tenantId, Actor actor, UUID incidentId, UUID verificationId) {
    required(tenantId, incidentId, verificationId);
    requireModule(actor, "resolve", RECOVERY);
    Change change = new Change(tenantId, actor);
    IncidentRow row = lock(tenantId, incidentId);
    guard(row, "resolve", IncidentState.OPEN);
    boolean evidence =
        records.evidence(tenantId, row.id).stream()
            .anyMatch(
                entry ->
                    entry.kind() == EvidenceKind.RECOVERY_VERIFICATION
                        && entry.producerModule().equals(RECOVERY)
                        && entry.producerRecordId().equals(verificationId));
    if (!evidence) {
      throw new IllegalStateException(
          "verification " + verificationId + " is not evidence of this incident");
    }
    row.resolvedAt = change.now;
    row.resolvedByVerificationId = verificationId;
    change.transition(row, IncidentState.RESOLVED, "DM-SM05-T4", IncidentTexts.RESOLVED);
    records.updateIncident(tenantId, row);
    change.changed(row.id);
    change.publish();
  }

  /**
   * DM-SM05-T6 (DM-R-23), in the caller's transaction: closes the incident when it is still {@code
   * resolved} and its reopen window ended before {@code now}; returns whether it did.
   */
  boolean close(TenantId tenantId, UUID incidentId, Duration reopenWindow, Instant now) {
    Change change = new Change(tenantId, Actor.module(INCIDENTS), now);
    Optional<IncidentRow> locked = records.lockIncident(tenantId, incidentId);
    if (locked.isEmpty()
        || locked.get().state != IncidentState.RESOLVED
        || !locked.get().resolvedAt.plus(reopenWindow).isBefore(change.now)) {
      return false;
    }
    IncidentRow row = locked.get();
    row.closedAt = change.now;
    change.transition(row, IncidentState.CLOSED, "DM-SM05-T6", IncidentTexts.CLOSED);
    records.updateIncident(tenantId, row);
    change.changed(row.id);
    change.publish();
    return true;
  }

  // The acknowledgement (DM-E35-R1, DM-R-63, AR-R-12).

  @Override
  @Transactional(propagation = Propagation.REQUIRED)
  public AcknowledgementView acknowledge(TenantId tenantId, UUID incidentId, UUID userId) {
    required(tenantId, incidentId, userId);
    Change change = new Change(tenantId, Actor.user(userId));
    IncidentRow row = lock(tenantId, incidentId);
    if (!identity.hasPermission(tenantId, userId, Permission.ACKNOWLEDGE)) {
      throw new VigilError(VigilError.Code.FORBIDDEN);
    }
    if (row.state != IncidentState.OPEN) {
      throw new VigilError(
          VigilError.Code.REFUSED,
          "This incident is " + row.state.key() + ". It does not need acknowledgement.",
          Map.of("refusal", "not_open", "state", row.state.key()));
    }
    Optional<AcknowledgementView> existing = current(tenantId, row);
    if (existing.isPresent()) {
      AcknowledgementView first = existing.get();
      throw new VigilError(
          VigilError.Code.REFUSED,
          "Already acknowledged by "
              + userName(tenantId, first.userId())
              + " at "
              + IncidentTexts.outbound(first.at())
              + ".",
          Map.of(
              "refusal",
              "already_acknowledged",
              "user_id",
              first.userId().toString(),
              "at",
              IncidentJson.time(first.at())));
    }
    Optional<String> team =
        records.notifiedTeams(tenantId, row.id).stream()
            .map(NotifiedTeamView::teamKey)
            .sorted(IncidentTexts.CODE_POINTS)
            .filter(key -> identity.isMember(tenantId, userId, new Key(key)))
            .findFirst();
    if (team.isEmpty()) {
      throw new VigilError(
          VigilError.Code.REFUSED,
          "You are not in a team that was notified about this incident.",
          Map.of("refusal", "not_notified"));
    }
    UUID id =
        records.insertAcknowledgement(
            tenantId, row.id, userId, team.get(), row.episode(), change.now);
    row.acknowledgedAt = change.now;
    row.acknowledgedBy = userId;
    row.updatedAt = change.now;
    records.updateIncident(tenantId, row);
    String text = IncidentTexts.acknowledged(userName(tenantId, userId));
    change.timeline(row.id, TimelineEntryType.ACKNOWLEDGED, text, ACKNOWLEDGEMENT, id);
    change.audit(AuditEventType.INCIDENT_ACKNOWLEDGED, ACKNOWLEDGEMENT, id, row.id, text, null);
    change.events.add(new IncidentAcknowledged(tenantId, row.id, row.episode()));
    change.changed(row.id);
    change.publish();
    return new AcknowledgementView(id, row.id, userId, team.get(), row.episode(), change.now);
  }

  // Reads.

  @Override
  public Optional<IncidentView> incident(TenantId tenantId, UUID incidentId) {
    required(tenantId, incidentId);
    return records.incident(tenantId, incidentId).map(IncidentRow::view);
  }

  @Override
  public List<IncidentView> incidents(TenantId tenantId, Set<IncidentState> states) {
    required(tenantId, states);
    if (states.isEmpty() || states.stream().anyMatch(Objects::isNull)) {
      throw new IllegalArgumentException("states: at least one");
    }
    return records.incidents(tenantId, states).stream().map(IncidentRow::view).toList();
  }

  @Override
  public Optional<UUID> incidentOfAlert(TenantId tenantId, UUID alertId) {
    required(tenantId, alertId);
    return records.activeMembership(tenantId, alertId).map(IncidentRecords.Membership::incidentId);
  }

  @Override
  public List<MembershipView> members(TenantId tenantId, UUID incidentId) {
    required(tenantId, incidentId);
    return records.memberships(tenantId, incidentId, true);
  }

  @Override
  public List<MembershipView> memberships(TenantId tenantId, UUID incidentId) {
    required(tenantId, incidentId);
    return records.memberships(tenantId, incidentId, false);
  }

  @Override
  public List<TransitionView> transitions(TenantId tenantId, UUID incidentId) {
    required(tenantId, incidentId);
    return List.copyOf(records.transitions(tenantId, incidentId));
  }

  @Override
  public Optional<AcknowledgementView> acknowledgement(TenantId tenantId, UUID incidentId) {
    required(tenantId, incidentId);
    return records.incident(tenantId, incidentId).flatMap(row -> current(tenantId, row));
  }

  private Optional<AcknowledgementView> current(TenantId tenantId, IncidentRow row) {
    return records.acknowledgements(tenantId, row.id).stream()
        .filter(ack -> ack.episode() == row.episode())
        .findFirst();
  }

  @Override
  public List<AcknowledgementView> acknowledgements(TenantId tenantId, UUID incidentId) {
    required(tenantId, incidentId);
    return List.copyOf(records.acknowledgements(tenantId, incidentId));
  }

  @Override
  public List<EscalationView> escalations(TenantId tenantId, UUID incidentId) {
    required(tenantId, incidentId);
    return List.copyOf(records.escalations(tenantId, incidentId));
  }

  @Override
  public List<NotifiedTeamView> notifiedTeams(TenantId tenantId, UUID incidentId) {
    required(tenantId, incidentId);
    return List.copyOf(records.notifiedTeams(tenantId, incidentId));
  }

  @Override
  public List<EvidenceView> evidence(TenantId tenantId, UUID incidentId) {
    required(tenantId, incidentId);
    return List.copyOf(records.evidence(tenantId, incidentId));
  }

  @Override
  public List<String> evidenceSummary(TenantId tenantId, UUID incidentId) {
    return evidence(tenantId, incidentId).stream().map(EvidenceView::summary).toList();
  }

  @Override
  public List<TimelineView> timeline(TenantId tenantId, UUID incidentId) {
    required(tenantId, incidentId);
    return List.copyOf(records.timeline(tenantId, incidentId));
  }

  @Override
  public Optional<IncidentSettingsView> settings(TenantId tenantId) {
    required(tenantId);
    return records.settings(tenantId);
  }

  // Helpers.

  /**
   * Recomputes the derived fields over the memberships (P-14 spec Section 6.6); returns whether the
   * title changed.
   */
  private boolean derive(TenantId tenantId, IncidentRow row) {
    List<MembershipView> all = records.memberships(tenantId, row.id, false);
    List<AlertCopy> active =
        all.stream().filter(m -> m.endedAt() == null).map(MembershipView::alert).toList();
    row.alertCount = active.size();
    row.alertTypes = sortedDistinct(active.stream().map(AlertCopy::alertType).toList());
    row.alertSources = sortedDistinct(active.stream().map(AlertCopy::sourceKind).toList());
    row.firstAlertFiredAt =
        all.stream().map(m -> m.alert().firedAt()).min(Comparator.naturalOrder()).orElseThrow();
    String title = IncidentTitle.title(active, row.hypothesis, row.affectedServices.size());
    boolean changed = !title.equals(row.title);
    row.title = title;
    return changed;
  }

  private static List<String> sortedDistinct(List<String> values) {
    return values.stream().distinct().sorted(IncidentTexts.CODE_POINTS).toList();
  }

  private static void titleEntry(Change change, IncidentRow row, boolean titleChanged) {
    if (titleChanged) {
      change.timeline(
          row.id, TimelineEntryType.TITLE_CHANGED, IncidentTexts.titleChanged(row.title));
    }
  }

  private static MembershipView membership(
      AlertCopy alert, UUID groupingDecisionId, String reason, String finalReason) {
    return new MembershipView(
        null, alert.alertId(), alert, reason, finalReason, groupingDecisionId, null, null);
  }

  private IncidentRow lock(TenantId tenantId, UUID incidentId) {
    return records
        .lockIncident(tenantId, incidentId)
        .orElseThrow(() -> new VigilError(VigilError.Code.NOT_FOUND));
  }

  private void requireNoMembership(TenantId tenantId, UUID alertId) {
    if (records.activeMembership(tenantId, alertId).isPresent()) {
      throw new IllegalStateException("alert " + alertId + " is already a member of an incident");
    }
  }

  private static void guard(IncidentRow row, String request, IncidentState... allowed) {
    if (!List.of(allowed).contains(row.state)) {
      throw notAllowed(request, row.state);
    }
  }

  private static IllegalStateException notAllowed(String request, IncidentState state) {
    return new IllegalStateException(request + " is not allowed in state " + state.key());
  }

  private static void requireModule(Actor actor, String request, String module) {
    required(actor);
    if (actor.kind() != Actor.Kind.MODULE || !module.equals(actor.module())) {
      throw new IllegalArgumentException(request + " is accepted only from " + module);
    }
  }

  private static void decision(GroupingReason reason, String decision) {
    if (!reason.decision().equals(decision)) {
      throw new IllegalArgumentException("reason: decision " + decision);
    }
  }

  private static void keys(Set<Key> keys, String name) {
    required(keys);
    if (keys.stream().anyMatch(Objects::isNull)) {
      throw new IllegalArgumentException(name + ": no null entries");
    }
  }

  private String teamName(TenantId tenantId, String teamKey) {
    return identity.team(tenantId, new Key(teamKey)).map(TeamView::name).orElse(teamKey);
  }

  private String userName(TenantId tenantId, UUID userId) {
    return identity.user(tenantId, userId).map(UserView::displayName).orElse(userId.toString());
  }

  private static void logTransition(
      TenantId tenantId, UUID incidentId, IncidentState from, IncidentState to) {
    LOG.info(
        "incident {} of tenant {}: {} to {}",
        incidentId,
        tenantId.value(),
        from == null ? "none" : from.key(),
        to.key());
  }

  private static void required(Object... values) {
    for (Object value : values) {
      if (value == null) {
        throw new IllegalArgumentException("a required argument is missing");
      }
    }
  }
}
