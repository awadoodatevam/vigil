package vigil.investigation;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import vigil.aigateway.api.AiGatewayService;
import vigil.aigateway.api.AiTask;
import vigil.aigateway.api.AnswerFinish;
import vigil.aigateway.api.CompletionRequest;
import vigil.aigateway.api.CompletionResult;
import vigil.aigateway.api.ProviderView;
import vigil.aigateway.api.UnavailableReason;
import vigil.audit.api.AuditEntry;
import vigil.audit.api.AuditEventType;
import vigil.audit.api.AuditService;
import vigil.incidents.api.AiStatus;
import vigil.incidents.api.AiUnavailableReason;
import vigil.incidents.api.DataCategory;
import vigil.incidents.api.EvidenceKind;
import vigil.incidents.api.EvidenceView;
import vigil.incidents.api.IncidentService;
import vigil.incidents.api.IncidentState;
import vigil.incidents.api.IncidentView;
import vigil.incidents.api.MembershipView;
import vigil.investigation.api.IncidentSummaryView;
import vigil.investigation.api.InvestigationResultView;
import vigil.investigation.api.InvestigationService;
import vigil.investigation.api.Origin;
import vigil.investigation.api.SummarySource;
import vigil.investigation.api.Validation;
import vigil.knowledge.api.KnowledgeService;
import vigil.knowledge.api.RunbookView;
import vigil.servicemap.api.ServiceMapService;
import vigil.shared.Actor;
import vigil.shared.Ids;
import vigil.shared.TenantId;
import vigil.shared.Timestamps;
import vigil.shared.VigilError;

/**
 * SL-T-1 attempts with their results, evidence, audit events and AI status; SL-T-2 summaries; the
 * re-run; the reads of PM-M17 (P-20 spec Sections 6.11 and 6.12). Every external call is made with
 * no transaction open (AR-R-09); every write after it in a transaction opened after the call.
 */
@Service
class Investigations implements InvestigationService {

  static final Actor INVESTIGATION = Actor.module("vigil-investigation");
  static final String RESULT_SUBJECT = "AiInvestigationResult";
  static final int RAW_MAX = 20_000;
  private static final Logger LOG = LoggerFactory.getLogger(Investigations.class);
  private static final Set<IncidentState> RERUNNABLE =
      Set.of(IncidentState.OPEN, IncidentState.RESOLVED, IncidentState.CLOSED);

  private final InvestigationRecords records;
  private final IncidentService incidents;
  private final ServiceMapService serviceMap;
  private final KnowledgeService knowledge;
  private final AiGatewayService gateway;
  private final AuditService audit;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final Map<UUID, Thread> reruns = new HashMap<>();

  Investigations(
      InvestigationRecords records,
      IncidentService incidents,
      ServiceMapService serviceMap,
      KnowledgeService knowledge,
      AiGatewayService gateway,
      AuditService audit,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.records = records;
    this.incidents = incidents;
    this.serviceMap = serviceMap;
    this.knowledge = knowledge;
    this.gateway = gateway;
    this.audit = audit;
    this.tx = new TransactionTemplate(transactionManager);
    this.clock = clock;
  }

  /**
   * One SL-T-1 attempt (the workflow step and the re-run); the validation of its stored result, or
   * {@code null} when no provider answered.
   */
  Validation investigate(
      TenantId tenantId,
      IncidentView incident,
      Origin origin,
      UUID runId,
      Actor requestedBy,
      UUID requestGroupId) {
    tx.executeWithoutResult(
        status ->
            incidents.recordAiRequestGroup(tenantId, INVESTIGATION, incident.id(), requestGroupId));
    List<MembershipView> members = incidents.members(tenantId, incident.id());
    List<EvidenceView> evidence = incidents.evidence(tenantId, incident.id());
    List<RunbookView> runbooks =
        knowledge.applicableRunbooks(
            tenantId,
            incident.alertTypes(),
            incident.hypothesis() == null ? null : incident.hypothesis().kind());
    Requests.SlT1 request =
        Requests.slT1(incident, members, evidence, dependents(tenantId, incident), runbooks);
    CompletionResult answer =
        gateway.complete(
            new CompletionRequest(
                tenantId,
                AiTask.SL_T_1,
                requestGroupId,
                incident.id(),
                Requests.SYSTEM_SL_T_1,
                request.parts(),
                Requests.NAME_SL_T_1,
                Requests.SCHEMA_SL_T_1,
                Requests.MAX_UNITS_SL_T_1));
    if (answer instanceof CompletionResult.Unavailable unavailable) {
      AiUnavailableReason reason =
          unavailable.reason() == UnavailableReason.NO_PROVIDER_ALLOWED
              ? AiUnavailableReason.NO_PROVIDER_ALLOWED
              : AiUnavailableReason.NO_PROVIDER_REACHABLE;
      tx.executeWithoutResult(
          status ->
              incidents.setAiStatus(
                  tenantId,
                  INVESTIGATION,
                  incident.id(),
                  AiStatus.UNAVAILABLE,
                  reason,
                  requestGroupId));
      return null;
    }
    CompletionResult.Answered answered = (CompletionResult.Answered) answer;
    String kindName = kindName(tenantId, answered.providerKey());
    OutputChecks.Checked checked =
        OutputChecks.slT1(
            answered.outputText(),
            answered.finish(),
            request.labels(),
            request.supplied(),
            incident.hypothesis() != null);
    Validation validation = checked.reasons().isEmpty() ? Validation.ACCEPTED : Validation.REJECTED;
    String raw = answered.outputText() == null ? "" : answered.outputText().replace('\u0000', '�');
    boolean truncated = raw.codePointCount(0, raw.length()) > RAW_MAX;
    tx.executeWithoutResult(
        status -> {
          Instant now = Timestamps.now(clock);
          InvestigationResultView result =
              new InvestigationResultView(
                  Ids.newId(),
                  incident.id(),
                  origin,
                  runId,
                  requestedBy,
                  requestGroupId,
                  answered.providerKey(),
                  answered.modelName(),
                  checked.likelyCause(),
                  checked.verdict(),
                  checked.evidenceEntryIds(),
                  checked.uncertainty(),
                  request.supplied(),
                  checked.runbookRefs(),
                  OutputChecks.cut(raw, RAW_MAX),
                  truncated,
                  validation,
                  checked.reasons(),
                  now);
          records.insertResult(tenantId, result);
          boolean accepted = validation == Validation.ACCEPTED;
          audit.append(
              new AuditEntry(
                  tenantId,
                  now,
                  requestedBy,
                  accepted ? AuditEventType.AI_RESULT_ACCEPTED : AuditEventType.AI_RESULT_REJECTED,
                  RESULT_SUBJECT,
                  result.id(),
                  incident.id(),
                  accepted
                      ? Texts.auditAccepted(kindName)
                      : Texts.auditRejected(kindName, checked.reasons()),
                  null));
          if (accepted) {
            incidents.addEvidence(
                tenantId,
                INVESTIGATION,
                incident.id(),
                EvidenceKind.AI_INVESTIGATION,
                result.id(),
                Texts.investigationSource(kindName, result.modelName()),
                now,
                Texts.investigationSummary(
                    kindName,
                    result.verdict(),
                    incident.hypothesis() == null ? null : incident.hypothesis().name(),
                    result.runbookRefs()),
                content(result),
                DataCategory.INCIDENT_RECORD);
          }
          incidents.setAiStatus(
              tenantId,
              INVESTIGATION,
              incident.id(),
              accepted ? AiStatus.AVAILABLE : AiStatus.UNAVAILABLE,
              accepted ? null : AiUnavailableReason.OUTPUT_FAILED_CHECKS,
              requestGroupId);
        });
    return validation;
  }

  /** One SL-T-2 summary (PM-M17-05): the AI summary, or the template summary. */
  IncidentSummaryView summarise(TenantId tenantId, IncidentView incident, UUID runId) {
    List<MembershipView> members = incidents.members(tenantId, incident.id());
    List<EvidenceView> evidence = incidents.evidence(tenantId, incident.id());
    InvestigationResultView accepted = records.latestAccepted(tenantId, incident.id()).orElse(null);
    CompletionResult answer =
        gateway.complete(
            new CompletionRequest(
                tenantId,
                AiTask.SL_T_2,
                Ids.newId(),
                incident.id(),
                Requests.SYSTEM_SL_T_2,
                Requests.slT2(
                    incident, members, dependents(tenantId, incident), accepted, evidence),
                Requests.NAME_SL_T_2,
                Requests.SCHEMA_SL_T_2,
                Requests.MAX_UNITS_SL_T_2));
    String text = null;
    String providerKey = null;
    if (answer instanceof CompletionResult.Answered answered
        && answered.finish() == AnswerFinish.COMPLETE) {
      text = aiSummary(answered.outputText());
      providerKey = text == null ? null : answered.providerKey();
    }
    String summaryText = text == null ? Texts.template(incident, members, evidence.size()) : text;
    String provider = providerKey;
    return tx.execute(
        status -> {
          IncidentSummaryView summary =
              new IncidentSummaryView(
                  Ids.newId(),
                  incident.id(),
                  runId,
                  summaryText,
                  provider == null ? SummarySource.TEMPLATE : SummarySource.AI,
                  provider,
                  Timestamps.now(clock));
          records.insertSummary(tenantId, summary);
          return summary;
        });
  }

  /** The text of an SL-T-2 output with exactly a non-blank {@code summary}; else {@code null}. */
  private static String aiSummary(String outputText) {
    if (outputText == null) {
      return null;
    }
    JsonNode tree;
    try {
      tree = InvestigationRecords.JSON.readTree(outputText);
    } catch (JacksonException e) {
      return null;
    }
    if (tree == null || !tree.isObject() || tree.size() != 1) {
      return null;
    }
    String summary = OutputChecks.string(tree.get("summary"));
    return summary == null || summary.isBlank()
        ? null
        : OutputChecks.cut(summary, Texts.SUMMARY_MAX);
  }

  @Override
  public UUID requestRerun(TenantId tenantId, UUID userId, UUID incidentId) {
    required(tenantId, userId, incidentId);
    IncidentView incident =
        incidents
            .incident(tenantId, incidentId)
            .orElseThrow(() -> new VigilError(VigilError.Code.NOT_FOUND));
    if (!RERUNNABLE.contains(incident.state())) {
      String state = incident.state().key();
      throw new VigilError(
          VigilError.Code.REFUSED,
          "This incident is "
              + state
              + ". The AI investigation can be re-run only for an open, resolved or closed"
              + " incident.",
          Map.of("refusal", "not_rerunnable", "state", state));
    }
    UUID group = Ids.newId();
    Thread thread =
        Thread.ofVirtual()
            .name("ai-rerun-" + group)
            .unstarted(
                () -> {
                  try {
                    IncidentView current =
                        incidents
                            .incident(tenantId, incidentId)
                            .orElseThrow(
                                () ->
                                    new IllegalStateException(
                                        "incident " + incidentId + " not found"));
                    investigate(tenantId, current, Origin.RERUN, null, Actor.user(userId), group);
                  } catch (RuntimeException e) {
                    LOG.warn(
                        "AI re-run {} of incident {} for tenant {} failed ({})",
                        group,
                        incidentId,
                        tenantId.value(),
                        e.getClass().getSimpleName());
                  }
                });
    synchronized (reruns) {
      reruns.put(group, thread);
    }
    thread.start();
    return group;
  }

  /** The thread of a re-run, kept after it ended (for tests). */
  Optional<Thread> rerunThread(UUID requestGroupId) {
    synchronized (reruns) {
      return Optional.ofNullable(reruns.get(requestGroupId));
    }
  }

  @Override
  public List<InvestigationResultView> results(TenantId tenantId, UUID incidentId) {
    required(tenantId, incidentId);
    return records.results(tenantId, incidentId);
  }

  @Override
  public Optional<InvestigationResultView> latestAccepted(TenantId tenantId, UUID incidentId) {
    required(tenantId, incidentId);
    return records.latestAccepted(tenantId, incidentId);
  }

  @Override
  public Optional<IncidentSummaryView> latestSummary(TenantId tenantId, UUID incidentId) {
    required(tenantId, incidentId);
    return records.latestSummary(tenantId, incidentId);
  }

  /** Joins the caller's transaction (propagation REQUIRED). */
  @Override
  public IncidentSummaryView summaryForMessage(TenantId tenantId, UUID incidentId) {
    required(tenantId, incidentId);
    return tx.execute(
        status -> {
          IncidentView incident =
              incidents
                  .incident(tenantId, incidentId)
                  .orElseThrow(
                      () -> new IllegalStateException("incident " + incidentId + " not found"));
          Optional<IncidentSummaryView> latest = records.latestSummary(tenantId, incidentId);
          if (latest.isPresent()) {
            return latest.get();
          }
          IncidentSummaryView summary =
              new IncidentSummaryView(
                  Ids.newId(),
                  incidentId,
                  null,
                  Texts.template(
                      incident,
                      incidents.members(tenantId, incidentId),
                      incidents.evidence(tenantId, incidentId).size()),
                  SummarySource.TEMPLATE,
                  null,
                  Timestamps.now(clock));
          records.insertSummary(tenantId, summary);
          return summary;
        });
  }

  /** DC-TX-01 "Provider kind name" of the provider; the key when the read finds nothing. */
  String kindName(TenantId tenantId, String providerKey) {
    return gateway
        .provider(tenantId, providerKey)
        .map(ProviderView::providerKind)
        .map(Texts::kindName)
        .orElse(providerKey);
  }

  private List<String> dependents(TenantId tenantId, IncidentView incident) {
    return incident.hypothesisComponentKey() == null
        ? List.of()
        : serviceMap.dependents(tenantId, incident.hypothesisComponentKey());
  }

  private static String content(InvestigationResultView result) {
    ObjectNode content = InvestigationRecords.JSON.createObjectNode();
    content.put("result_id", result.id().toString());
    content.put("origin", result.origin().key());
    content.put("provider_key", result.providerKey());
    content.put("model_name", result.modelName());
    content.put("likely_cause", result.likelyCause());
    content.put("verdict", result.verdict() == null ? null : result.verdict().key());
    content
        .putArray("evidence_entry_ids")
        .addAll(
            result.evidenceEntryIds().stream()
                .map(
                    id ->
                        (JsonNode)
                            InvestigationRecords.JSON.getNodeFactory().stringNode(id.toString()))
                .toList());
    content.put("uncertainty", result.uncertainty());
    content.set("runbook_refs", InvestigationRecords.refsJson(result.runbookRefs()));
    return InvestigationRecords.JSON.writeValueAsString(content);
  }

  private static void required(Object... values) {
    for (Object value : values) {
      if (value == null) {
        throw new IllegalArgumentException("a required argument is missing");
      }
    }
  }
}
