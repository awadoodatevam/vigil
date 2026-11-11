package vigil.ticketing;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import vigil.connectors.api.ConnectorInstanceView;
import vigil.connectors.api.TicketLine;
import vigil.connectors.api.TicketOutcome;
import vigil.connectors.api.TicketingConnector;
import vigil.incidents.api.EvidenceKind;
import vigil.incidents.api.IncidentService;
import vigil.incidents.api.IncidentView;
import vigil.incidents.api.MembershipView;
import vigil.intake.api.AlertView;
import vigil.intake.api.IntakeService;
import vigil.investigation.api.IncidentSummaryView;
import vigil.investigation.api.InvestigationService;
import vigil.shared.Completed;
import vigil.shared.Ids;
import vigil.shared.Key;
import vigil.shared.StepAnswer;
import vigil.shared.StepCall;
import vigil.shared.StepCancel;
import vigil.shared.TenantId;
import vigil.shared.Timestamps;
import vigil.shared.TransactionGuard;
import vigil.tenancy.api.TenantService;
import vigil.tenancy.api.TenantSettingsView;
import vigil.ticketing.Ticketing.Request;
import vigil.ticketing.api.SyncOperation;
import vigil.ticketing.api.SyncOutcome;
import vigil.ticketing.api.SyncRecordView;
import vigil.ticketing.api.SyncRequester;
import vigil.ticketing.api.TicketLinkState;
import vigil.ticketing.api.TicketLinkView;
import vigil.ticketing.api.TicketingStepHandler;

/**
 * The Ticket create/update step (DM-S-10, PM-S-10; P-21 spec Section 6.7): {@code start} and {@code
 * evaluate} do the same and always complete with {@code done} or {@code failed}; {@code cancel}
 * does nothing.
 */
@Component
class TicketStep implements TicketingStepHandler {

  static final String BAD_PARAMETERS = "ticket step parameters: operation create or resolve";
  static final String NO_INSTANCE = "no active Jira connector instance";
  static final String NO_LINK_BASE = "the tenant has no public address for links";
  static final String NO_EVIDENCE = "no recovery evidence";
  static final String NO_REOPENING_ALERT = "no reopening alert";
  static final Duration SEARCH_PAUSE = Duration.ofSeconds(5);
  static final int SEARCHES = 4;
  private static final Key DONE = new Key("done");
  private static final Key FAILED = new Key("failed");

  private final Ticketing ticketing;
  private final TicketingRecords records;
  private final JiraInstances instances;
  private final IncidentService incidents;
  private final IntakeService intake;
  private final InvestigationService investigation;
  private final TenantService tenants;
  private final Clock clock;
  private final TransactionTemplate tx;

  TicketStep(
      Ticketing ticketing,
      TicketingRecords records,
      JiraInstances instances,
      IncidentService incidents,
      IntakeService intake,
      InvestigationService investigation,
      TenantService tenants,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.ticketing = ticketing;
    this.records = records;
    this.instances = instances;
    this.incidents = incidents;
    this.intake = intake;
    this.investigation = investigation;
    this.tenants = tenants;
    this.clock = clock;
    this.tx = new TransactionTemplate(transactionManager);
  }

  @Override
  public StepAnswer start(StepCall call) {
    return step(call);
  }

  @Override
  public StepAnswer evaluate(StepCall call) {
    return step(call);
  }

  /** The step leaves nothing open (AR-R-21). */
  @Override
  public void cancel(StepCancel cancel) {
    // Nothing to cancel.
  }

  private StepAnswer step(StepCall call) {
    TransactionGuard.assertNoTransaction();
    boolean create = create(call);
    TenantId tenantId = call.tenantId();
    IncidentView incident =
        incidents
            .incident(tenantId, call.incidentId())
            .orElseThrow(
                () -> new IllegalStateException("incident " + call.incidentId() + " not found"));
    Optional<SyncRecordView> own = records.recordOfStep(tenantId, call.stepExecutionId());
    if (own.isPresent()) {
      return own.get().outcome() == SyncOutcome.PENDING
          ? restart(call, incident, own.get())
          : answer(own.get().id(), own.get().outcome());
    }
    Optional<TicketLinkView> link = records.link(tenantId, incident.id());
    if (create) {
      if (link.isEmpty()) {
        return newCreate(call, incident);
      }
      return link.get().state() == TicketLinkState.OPEN
          ? new Completed(DONE, null)
          : comment(call, incident, link.get(), SyncOperation.REOPEN, null);
    }
    if (link.isEmpty()) {
      return failAtOnce(call, incident, SyncOperation.RESOLVE, null, Ticketing.NO_TICKET);
    }
    return link.get().state() == TicketLinkState.RESOLVED
        ? new Completed(DONE, null)
        : comment(call, incident, link.get(), SyncOperation.RESOLVE, null);
  }

  /** The content of a create: the instance, its connector, title, lines and label. */
  private record CreateContent(
      ConnectorInstanceView instance,
      String title,
      List<TicketLine> description,
      String label,
      String json) {}

  /** The content of a create, or the reason it fails at once. */
  private record Built<T>(T content, String failure) {}

  private Built<CreateContent> createContent(TenantId tenantId, IncidentView incident) {
    Optional<ConnectorInstanceView> found = instances.firstActive(tenantId);
    if (found.isEmpty()) {
      return new Built<>(null, NO_INSTANCE);
    }
    ConnectorInstanceView instance = found.get();
    String labelPrefix = JiraInstances.setting(instance, "label_prefix");
    Optional<String> base = tenants.publicBaseAddress(tenantId);
    if (base.isEmpty()) {
      return new Built<>(null, NO_LINK_BASE);
    }
    if (incident.number() == null) {
      throw new IllegalStateException("incident " + incident.id() + " has no number");
    }
    IncidentSummaryView summary =
        tx.execute(status -> investigation.summaryForMessage(tenantId, incident.id()));
    TenantSettingsView settings = tenants.settings(tenantId);
    String title = TicketTexts.title(settings.environmentLabel(), incident.title());
    List<TicketLine> description =
        TicketTexts.createLines(
            incident,
            incidents.members(tenantId, incident.id()),
            summary,
            incidents.evidenceSummary(tenantId, incident.id()),
            settings.productDisplayName(),
            base.get() + "/incidents/" + incident.id());
    String label = labelPrefix + incident.id();
    return new Built<>(
        new CreateContent(
            instance,
            title,
            description,
            label,
            TicketTexts.createContent(title, description, label)),
        null);
  }

  /** Section 6.7 step 4, no link: a create, after a label search when one is needed. */
  private StepAnswer newCreate(StepCall call, IncidentView incident) {
    TenantId tenantId = call.tenantId();
    Built<CreateContent> built = createContent(tenantId, incident);
    if (built.failure() != null) {
      return failAtOnce(call, incident, SyncOperation.CREATE, null, built.failure());
    }
    CreateContent content = built.content();
    SyncRecordView record =
        ticketing.storePending(request(call, SyncOperation.CREATE), null, content.json());
    TicketingConnector connector = instances.connector(content.instance());
    boolean search =
        records.records(tenantId, incident.id()).stream()
            .anyMatch(
                other ->
                    !other.id().equals(record.id())
                        && other.operation() == SyncOperation.CREATE
                        && (other.outcome() == SyncOutcome.PENDING
                            || (other.outcome() == SyncOutcome.FAILED
                                && calledJira(other.contentSentJson()))));
    if (search) {
      TicketOutcome found = search(connector, tenantId, content.instance(), content.label());
      if (found instanceof TicketOutcome.Failed failed) {
        return finishFailed(tenantId, record, failed.reason(), null, null);
      }
      if (((TicketOutcome.Done) found).key() != null) {
        return linked(tenantId, incident, record, content.instance(), found, null);
      }
    }
    return created(tenantId, incident, record, connector, content);
  }

  /** The create call and its finishing; the content is the one sent. */
  private StepAnswer created(
      TenantId tenantId,
      IncidentView incident,
      SyncRecordView record,
      TicketingConnector connector,
      CreateContent content) {
    TicketOutcome outcome =
        connector.create(
            tenantId, content.instance(), content.title(), content.description(), content.label());
    if (outcome instanceof TicketOutcome.Failed failed) {
      return finishFailed(tenantId, record, failed.reason(), null, content.json());
    }
    return linked(tenantId, incident, record, content.instance(), outcome, content.json());
  }

  /**
   * In one transaction: the link ({@code open}; DM-SM14-T1), the record {@code done} with it, and
   * every other {@code pending} create record of the incident {@code failed} as superseded.
   */
  private StepAnswer linked(
      TenantId tenantId,
      IncidentView incident,
      SyncRecordView record,
      ConnectorInstanceView instance,
      TicketOutcome outcome,
      String contentSent) {
    TicketOutcome.Done done = (TicketOutcome.Done) outcome;
    Instant now = Timestamps.now(clock);
    TicketLinkView link =
        new TicketLinkView(
            Ids.newId(),
            incident.id(),
            instance.id(),
            done.key(),
            done.address(),
            TicketLinkState.OPEN,
            now,
            now);
    List<SyncRecordView> superseded = new ArrayList<>();
    String error = "superseded by " + record.id();
    ticketing.finish(
        tenantId,
        record,
        SyncOutcome.DONE,
        null,
        link,
        contentSent,
        () -> {
          records.insertLink(tenantId, link);
          for (SyncRecordView other : records.records(tenantId, incident.id())) {
            if (!other.id().equals(record.id())
                && other.operation() == SyncOperation.CREATE
                && other.outcome() == SyncOutcome.PENDING) {
              ticketing.finishOne(
                  tenantId, other, SyncOutcome.FAILED, error, null, null, null, now);
              superseded.add(other);
            }
          }
        });
    superseded.forEach(other -> ticketing.warn(tenantId, other, error));
    return answer(record.id(), SyncOutcome.DONE);
  }

  /** The lines of a reopen or resolve comment, or the reason it fails at once. */
  private Built<List<TicketLine>> commentLines(
      TenantId tenantId, IncidentView incident, SyncOperation operation) {
    if (operation == SyncOperation.REOPEN) {
      Optional<TicketLine> line = reopenLine(tenantId, incident);
      return line.isPresent()
          ? new Built<>(List.of(line.get()), null)
          : new Built<>(null, NO_REOPENING_ALERT);
    }
    Optional<List<TicketLine>> lines =
        incidents.evidence(tenantId, incident.id()).stream()
            .filter(
                entry ->
                    entry.kind() == EvidenceKind.RECOVERY_VERIFICATION
                        && incident.resolvedByVerificationId() != null
                        && incident.resolvedByVerificationId().equals(entry.producerRecordId()))
            .findFirst()
            .flatMap(TicketTexts::resolveLines);
    return lines.isPresent() ? new Built<>(lines.get(), null) : new Built<>(null, NO_EVIDENCE);
  }

  /** The reopening alert's line of DC-OUT-04, or empty. */
  private Optional<TicketLine> reopenLine(TenantId tenantId, IncidentView incident) {
    Optional<MembershipView> reopening =
        incidents.memberships(tenantId, incident.id()).stream()
            .filter(member -> reopened(member.reasonJson()))
            .max(
                Comparator.comparing(MembershipView::joinedAt)
                    .thenComparing(member -> member.id().toString()));
    if (reopening.isEmpty()) {
      return Optional.empty();
    }
    Optional<AlertView> alert = intake.alert(tenantId, reopening.get().alertId());
    return alert.map(
        a -> TicketTexts.reopenLine(a.title(), a.sourceKind().key(), a.firstReceivedAt()));
  }

  /**
   * Section 6.7 steps 4 (reopen) and 5 (resolve) for a link: a comment, then the transition; with
   * {@code pending} a record of the step that is finished by the restart rules.
   */
  private StepAnswer comment(
      StepCall call,
      IncidentView incident,
      TicketLinkView link,
      SyncOperation operation,
      SyncRecordView pending) {
    TenantId tenantId = call.tenantId();
    Optional<ConnectorInstanceView> instance = instances.ofLink(tenantId, link);
    if (instance.isEmpty()) {
      return failed(call, incident, operation, link, pending, Ticketing.INSTANCE_NOT_ACTIVE);
    }
    boolean reopen = operation == SyncOperation.REOPEN;
    TicketLinkState target = reopen ? TicketLinkState.OPEN : TicketLinkState.RESOLVED;
    String targetStatus =
        JiraInstances.setting(instance.get(), reopen ? "reopened_status" : "resolved_status");
    Built<List<TicketLine>> lines = commentLines(tenantId, incident, operation);
    if (lines.failure() != null) {
      return failed(call, incident, operation, link, pending, lines.failure());
    }
    String content = TicketTexts.commentContent(lines.content(), targetStatus);
    TicketingConnector connector = instances.connector(instance.get());
    SyncRecordView record;
    if (pending == null) {
      record = ticketing.storePending(request(call, operation), link, content);
    } else {
      record = pending;
      TicketOutcome status = connector.status(tenantId, instance.get(), link.externalKey());
      if (status instanceof TicketOutcome.Failed failed) {
        return finishFailed(tenantId, record, failed.reason(), link, null);
      }
      if (targetStatus.equals(((TicketOutcome.Done) status).status())) {
        return finishDone(tenantId, record, link, target, null);
      }
    }
    TicketOutcome commented =
        connector.addComment(tenantId, instance.get(), link.externalKey(), lines.content());
    if (commented instanceof TicketOutcome.Failed failed) {
      return finishFailed(tenantId, record, failed.reason(), link, content);
    }
    TicketOutcome moved =
        connector.transition(tenantId, instance.get(), link.externalKey(), targetStatus);
    if (moved instanceof TicketOutcome.Failed failed) {
      return finishFailed(tenantId, record, failed.reason(), link, content);
    }
    return finishDone(tenantId, record, link, target, content);
  }

  /** Section 6.7 step 6: the step's own record is {@code pending}. */
  private StepAnswer restart(StepCall call, IncidentView incident, SyncRecordView record) {
    TenantId tenantId = call.tenantId();
    if (record.operation() != SyncOperation.CREATE) {
      TicketLinkView link =
          records
              .link(tenantId, incident.id())
              .orElseThrow(
                  () -> new IllegalStateException("incident " + incident.id() + " has no link"));
      return comment(call, incident, link, record.operation(), record);
    }
    Built<CreateContent> built = createContent(tenantId, incident);
    if (built.failure() != null) {
      return finishFailed(tenantId, record, built.failure(), null, null);
    }
    CreateContent content = built.content();
    TicketingConnector connector = instances.connector(content.instance());
    TicketOutcome found = search(connector, tenantId, content.instance(), content.label());
    if (found instanceof TicketOutcome.Failed failed) {
      return finishFailed(tenantId, record, failed.reason(), null, null);
    }
    if (((TicketOutcome.Done) found).key() != null) {
      return linked(tenantId, incident, record, content.instance(), found, null);
    }
    return created(tenantId, incident, record, connector, content);
  }

  /**
   * The label search (AR-K-14, CTX-D-060 (6)): at once, then while it finds none again 5 s after
   * the previous answer, at most 4 searches; a {@code Failed} search ends it.
   */
  private static TicketOutcome search(
      TicketingConnector connector,
      TenantId tenantId,
      ConnectorInstanceView instance,
      String label) {
    TicketOutcome outcome = null;
    for (int i = 0; i < SEARCHES; i++) {
      if (i > 0) {
        try {
          Thread.sleep(SEARCH_PAUSE);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("label search interrupted", e);
        }
      }
      outcome = connector.findByLabel(tenantId, instance, label);
      if (outcome instanceof TicketOutcome.Failed || ((TicketOutcome.Done) outcome).key() != null) {
        return outcome;
      }
    }
    return outcome;
  }

  /** A failure at once: a new record stored {@code failed}, or the own pending record finished. */
  private StepAnswer failed(
      StepCall call,
      IncidentView incident,
      SyncOperation operation,
      TicketLinkView link,
      SyncRecordView pending,
      String reason) {
    return pending == null
        ? failAtOnce(call, incident, operation, link, reason)
        : finishFailed(call.tenantId(), pending, reason, link, null);
  }

  private StepAnswer failAtOnce(
      StepCall call,
      IncidentView incident,
      SyncOperation operation,
      TicketLinkView link,
      String reason) {
    SyncRecordView record = ticketing.failAtOnce(request(call, operation), link, reason);
    return answer(record.id(), SyncOutcome.FAILED);
  }

  private StepAnswer finishFailed(
      TenantId tenantId,
      SyncRecordView record,
      String reason,
      TicketLinkView link,
      String contentSent) {
    ticketing.finish(tenantId, record, SyncOutcome.FAILED, reason, link, contentSent, null);
    return answer(record.id(), SyncOutcome.FAILED);
  }

  private StepAnswer finishDone(
      TenantId tenantId,
      SyncRecordView record,
      TicketLinkView link,
      TicketLinkState target,
      String contentSent) {
    Instant now = Timestamps.now(clock);
    ticketing.finish(
        tenantId,
        record,
        SyncOutcome.DONE,
        null,
        link,
        contentSent,
        () -> records.setLinkState(tenantId, link.id(), target, now));
    return answer(record.id(), SyncOutcome.DONE);
  }

  private static Request request(StepCall call, SyncOperation operation) {
    return new Request(
        call.tenantId(),
        call.incidentId(),
        operation,
        SyncRequester.WORKFLOW_STEP,
        call.stepExecutionId(),
        null);
  }

  private static StepAnswer answer(UUID recordId, SyncOutcome outcome) {
    return new Completed(
        outcome == SyncOutcome.DONE ? DONE : FAILED,
        new Completed.ResultRef("vigil-ticketing", "TicketSyncRecord", recordId));
  }

  /** Whether a {@code failed} create record's content is not {@code {}}: it called Jira. */
  private static boolean calledJira(String contentSentJson) {
    JsonNode content = TicketTexts.JSON.readTree(contentSentJson);
    return content != null && !content.isEmpty();
  }

  private static boolean reopened(String reasonJson) {
    try {
      JsonNode reason = TicketTexts.JSON.readTree(reasonJson);
      JsonNode decision = reason == null ? null : reason.get("decision");
      return decision != null && decision.isString() && "reopened".equals(decision.stringValue());
    } catch (JacksonException e) {
      return false;
    }
  }

  /** Whether the step's operation is {@code create} ({@code resolve} otherwise). */
  private static boolean create(StepCall call) {
    if (call.stepType() != StepCall.StepType.TICKET) {
      throw new IllegalArgumentException(BAD_PARAMETERS);
    }
    JsonNode tree;
    try {
      tree = TicketTexts.JSON.readTree(call.parameters());
    } catch (JacksonException e) {
      throw new IllegalArgumentException(BAD_PARAMETERS, e);
    }
    JsonNode operation = tree == null || !tree.isObject() ? null : tree.get("operation");
    if (operation == null
        || !operation.isString()
        || !List.of("create", "resolve").contains(operation.stringValue())) {
      throw new IllegalArgumentException(BAD_PARAMETERS);
    }
    return operation.stringValue().equals("create");
  }
}
