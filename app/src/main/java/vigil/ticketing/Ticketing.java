package vigil.ticketing;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vigil.audit.api.AuditEntry;
import vigil.audit.api.AuditEventType;
import vigil.audit.api.AuditService;
import vigil.connectors.api.ConnectorInstanceView;
import vigil.connectors.api.TicketLine;
import vigil.connectors.api.TicketOutcome;
import vigil.incidents.api.EscalationCause;
import vigil.incidents.api.IncidentService;
import vigil.shared.Actor;
import vigil.shared.Ids;
import vigil.shared.TenantId;
import vigil.shared.Timestamps;
import vigil.shared.TransactionGuard;
import vigil.ticketing.api.SyncOperation;
import vigil.ticketing.api.SyncOutcome;
import vigil.ticketing.api.SyncRecordView;
import vigil.ticketing.api.SyncRequester;
import vigil.ticketing.api.TicketLinkView;
import vigil.ticketing.api.TicketingService;

/**
 * The requests of other modules to vigil-ticketing (PM-M19): the ticket link, the sync records and
 * the escalation comment (P-21 spec Section 6.8); and the storing and finishing of sync records
 * with their audit events (Sections 6.7 and 6.9), which {@link TicketStep} shares.
 */
@Service
class Ticketing implements TicketingService {

  static final Actor TICKETING = Actor.module("vigil-ticketing");
  static final String NOTHING_SENT = "{}";
  static final String NO_TICKET = "no ticket";
  static final String INSTANCE_NOT_ACTIVE = "the Jira connector instance is not active";
  private static final Logger LOG = LoggerFactory.getLogger(Ticketing.class);

  private final TicketingRecords records;
  private final IncidentService incidents;
  private final AuditService audit;
  private final JiraInstances instances;
  private final Clock clock;
  private final TransactionTemplate tx;

  Ticketing(
      TicketingRecords records,
      IncidentService incidents,
      AuditService audit,
      JiraInstances instances,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.records = records;
    this.incidents = incidents;
    this.audit = audit;
    this.instances = instances;
    this.clock = clock;
    this.tx = new TransactionTemplate(transactionManager);
  }

  @Override
  public Optional<TicketLinkView> ticketLink(TenantId tenantId, UUID incidentId) {
    required(tenantId, incidentId);
    return records.link(tenantId, incidentId);
  }

  @Override
  public List<SyncRecordView> syncRecords(TenantId tenantId, UUID incidentId) {
    required(tenantId, incidentId);
    return List.copyOf(records.records(tenantId, incidentId));
  }

  /**
   * {@inheritDoc}
   *
   * <p>external call: not inside a transaction
   */
  @Override
  public SyncOutcome recordEscalation(
      TenantId tenantId,
      UUID incidentId,
      UUID escalationId,
      long level,
      List<String> teamNames,
      EscalationCause cause,
      Duration timer) {
    TransactionGuard.assertNoTransaction();
    required(tenantId, incidentId, escalationId, teamNames, cause);
    if (teamNames.stream().anyMatch(name -> name == null)) {
      throw new IllegalArgumentException("a required argument is missing");
    }
    if ((cause == EscalationCause.ACKNOWLEDGEMENT_TIMEOUT) != (timer != null)) {
      throw new IllegalArgumentException("timer: required for acknowledgement_timeout only");
    }
    if (incidents.incident(tenantId, incidentId).isEmpty()) {
      throw new IllegalStateException("incident " + incidentId + " not found");
    }
    Optional<SyncRecordView> existing = records.recordOfEscalation(tenantId, escalationId);
    if (existing.isPresent() && existing.get().outcome() != SyncOutcome.PENDING) {
      return existing.get().outcome();
    }
    Optional<TicketLinkView> link = records.link(tenantId, incidentId);
    Request request =
        new Request(
            tenantId,
            incidentId,
            SyncOperation.RECORD_ESCALATION,
            SyncRequester.ROUTING,
            null,
            escalationId);
    if (link.isEmpty()) {
      return existing.isPresent()
          ? finish(tenantId, existing.get(), SyncOutcome.FAILED, NO_TICKET, null, null, null)
          : failAtOnce(request, null, NO_TICKET).outcome();
    }
    Optional<ConnectorInstanceView> instance = instances.ofLink(tenantId, link.get());
    if (instance.isEmpty()) {
      return existing.isPresent()
          ? finish(
              tenantId,
              existing.get(),
              SyncOutcome.FAILED,
              INSTANCE_NOT_ACTIVE,
              link.get(),
              null,
              null)
          : failAtOnce(request, link.get(), INSTANCE_NOT_ACTIVE).outcome();
    }
    List<TicketLine> lines =
        TicketTexts.escalationLines(
            level, teamNames, cause, timer, incidents.evidenceSummary(tenantId, incidentId));
    String content = TicketTexts.escalationContent(lines);
    SyncRecordView record =
        existing.isPresent() ? existing.get() : storePending(request, link.get(), content);
    TicketOutcome outcome =
        instances
            .connector(instance.get())
            .addComment(tenantId, instance.get(), link.get().externalKey(), lines);
    return outcome instanceof TicketOutcome.Failed failed
        ? finish(tenantId, record, SyncOutcome.FAILED, failed.reason(), link.get(), content, null)
        : finish(tenantId, record, SyncOutcome.DONE, null, link.get(), content, null);
  }

  /** Who asks for a sync record and for which incident and operation. */
  record Request(
      TenantId tenantId,
      UUID incidentId,
      SyncOperation operation,
      SyncRequester requestedBy,
      UUID stepExecutionId,
      UUID escalationId) {}

  /**
   * A record that fails before any Jira call: stored {@code failed} with content {@code {}} and its
   * audit event in one transaction.
   */
  SyncRecordView failAtOnce(Request request, TicketLinkView link, String error) {
    Instant now = Timestamps.now(clock);
    SyncRecordView record =
        new SyncRecordView(
            Ids.newId(),
            request.incidentId(),
            link == null ? null : link.id(),
            request.operation(),
            request.requestedBy(),
            request.stepExecutionId(),
            request.escalationId(),
            NOTHING_SENT,
            SyncOutcome.FAILED,
            TicketTexts.cut(error, TicketTexts.ERROR_MAX),
            now,
            now);
    tx.executeWithoutResult(
        status -> {
          records.insertRecord(request.tenantId(), record);
          audit(
              request.tenantId(),
              record,
              SyncOutcome.FAILED,
              link == null ? null : link.externalKey(),
              now);
        });
    warn(request.tenantId(), record);
    return record;
  }

  /** A {@code pending} record stored in its own committed transaction before the Jira calls. */
  SyncRecordView storePending(Request request, TicketLinkView link, String contentSent) {
    Instant now = Timestamps.now(clock);
    SyncRecordView record =
        new SyncRecordView(
            Ids.newId(),
            request.incidentId(),
            link == null ? null : link.id(),
            request.operation(),
            request.requestedBy(),
            request.stepExecutionId(),
            request.escalationId(),
            contentSent,
            SyncOutcome.PENDING,
            null,
            now,
            null);
    tx.executeWithoutResult(status -> records.insertRecord(request.tenantId(), record));
    return record;
  }

  /**
   * Finishes a {@code pending} record in one transaction: {@code change} first (the link change),
   * then the outcome, error, link and finish time, the content sent when {@code contentSent} is not
   * {@code null}, and the audit event. Returns the outcome.
   */
  SyncOutcome finish(
      TenantId tenantId,
      SyncRecordView record,
      SyncOutcome outcome,
      String error,
      TicketLinkView link,
      String contentSent,
      Runnable change) {
    Instant now = Timestamps.now(clock);
    String cutError = error == null ? null : TicketTexts.cut(error, TicketTexts.ERROR_MAX);
    tx.executeWithoutResult(
        status -> {
          if (change != null) {
            change.run();
          }
          finishOne(
              tenantId,
              record,
              outcome,
              cutError,
              link == null ? null : link.id(),
              link == null ? null : link.externalKey(),
              contentSent,
              now);
        });
    if (outcome == SyncOutcome.FAILED) {
      warn(tenantId, record, cutError);
    }
    return outcome;
  }

  /**
   * Inside a finishing transaction: one {@code pending} record finished, with its audit event;
   * {@code key} is the link's key, or {@code null} when the record has no link.
   */
  void finishOne(
      TenantId tenantId,
      SyncRecordView record,
      SyncOutcome outcome,
      String error,
      UUID linkId,
      String key,
      String contentSent,
      Instant now) {
    if (records.finish(tenantId, record.id(), outcome, error, linkId, contentSent, now) != 1) {
      throw new IllegalStateException("sync record " + record.id() + " is not pending");
    }
    audit(tenantId, record, outcome, key, now);
  }

  /** The {@code ticket_changed} event of a finished record (DM-E08-R3), in the caller's tx. */
  void audit(
      TenantId tenantId, SyncRecordView record, SyncOutcome outcome, String key, Instant now) {
    audit.append(
        new AuditEntry(
            tenantId,
            now,
            TICKETING,
            AuditEventType.TICKET_CHANGED,
            "TicketSyncRecord",
            record.id(),
            record.incidentId(),
            TicketTexts.auditSummary(key, record.operation(), outcome),
            TicketTexts.auditDetails(record.operation(), outcome)));
  }

  void warn(TenantId tenantId, SyncRecordView record) {
    warn(tenantId, record, record.error());
  }

  void warn(TenantId tenantId, SyncRecordView record, String error) {
    LOG.warn(
        "ticket {} for incident {} of tenant {} failed: {}",
        record.operation().key(),
        record.incidentId(),
        tenantId.value(),
        error);
  }

  private static void required(Object... arguments) {
    for (Object argument : arguments) {
      if (argument == null) {
        throw new IllegalArgumentException("a required argument is missing");
      }
    }
  }
}
