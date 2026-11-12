package vigil.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vigil.actions.api.ActionExecutionView;
import vigil.actions.api.ActionsService;
import vigil.actions.api.ApprovalRequestView;
import vigil.actions.api.ProposalView;
import vigil.actions.api.RequestState;
import vigil.aigateway.api.AiGatewayService;
import vigil.aigateway.api.ProviderView;
import vigil.engine.api.EngineService;
import vigil.engine.api.RunMode;
import vigil.engine.api.RunView;
import vigil.engine.api.StepExecutionState;
import vigil.engine.api.StepExecutionView;
import vigil.identity.api.IdentityService;
import vigil.identity.api.Permission;
import vigil.incidents.api.EvidenceKind;
import vigil.incidents.api.EvidenceView;
import vigil.incidents.api.IncidentService;
import vigil.incidents.api.IncidentView;
import vigil.intake.api.IntakeService;
import vigil.intake.api.SourceView;
import vigil.investigation.api.InvestigationResultView;
import vigil.investigation.api.InvestigationService;
import vigil.investigation.api.Origin;
import vigil.investigation.api.RunbookRef;
import vigil.knowledge.api.KnowledgeService;
import vigil.knowledge.api.SearchOutcome;
import vigil.knowledge.api.SimilarityResultView;
import vigil.recovery.api.RecoveryService;
import vigil.recovery.api.RecoveryVerificationView;
import vigil.runner.api.RunnerService;
import vigil.servicemap.api.ServiceMapService;
import vigil.shared.Actor;
import vigil.shared.StepCall.StepType;
import vigil.shared.TenantId;

/**
 * The workspace sections of the incident's AI investigation (AR-I-25, DC-S03-09), Response
 * (AR-I-27, DC-S03-10) and Recovery (AR-I-28, DC-S03-11) of its latest live run.
 */
@RestController
@Profile("!migrate")
class SectionsController {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String SIMILARITY_TASK = "SL-T-3";
  private static final String UNAVAILABLE = "unavailable";

  private final IncidentService incidents;
  private final InvestigationService investigation;
  private final AiGatewayService aiGateway;
  private final KnowledgeService knowledge;
  private final ActionsService actions;
  private final EngineService engine;
  private final RunnerService runner;
  private final RecoveryService recovery;
  private final IntakeService intake;
  private final IdentityService identity;
  private final ServiceMapService serviceMap;

  SectionsController(
      IncidentService incidents,
      InvestigationService investigation,
      AiGatewayService aiGateway,
      KnowledgeService knowledge,
      ActionsService actions,
      EngineService engine,
      RunnerService runner,
      RecoveryService recovery,
      IntakeService intake,
      IdentityService identity,
      ServiceMapService serviceMap) {
    this.incidents = incidents;
    this.investigation = investigation;
    this.aiGateway = aiGateway;
    this.knowledge = knowledge;
    this.actions = actions;
    this.engine = engine;
    this.runner = runner;
    this.recovery = recovery;
    this.intake = intake;
    this.identity = identity;
    this.serviceMap = serviceMap;
  }

  // AR-I-25

  @GetMapping("/api/v1/incidents/{incident_id}/investigation")
  @RequiresPermission(Permission.VIEW)
  InvestigationSection investigation(
      TenantId tenant, @PathVariable("incident_id") String incidentId) {
    IncidentView incident = IncidentGuard.workspace(incidents, tenant, incidentId);
    InvestigationSection.Result result =
        investigation
            .latestAccepted(tenant, incident.id())
            .map(accepted -> result(tenant, incident, accepted))
            .orElse(null);
    InvestigationSection.Summary summary =
        investigation
            .latestSummary(tenant, incident.id())
            .map(s -> new InvestigationSection.Summary(s.text(), s.source().key()))
            .orElse(null);
    return new InvestigationSection(
        incident.aiStatus().key(),
        incident.aiUnavailableReason() == null ? null : incident.aiUnavailableReason().key(),
        time(incident.aiStatusChangedAt()),
        incident.aiLastRequestGroupId() == null ? null : incident.aiLastRequestGroupId().toString(),
        result,
        summary);
  }

  private InvestigationSection.Result result(
      TenantId tenant, IncidentView incident, InvestigationResultView accepted) {
    Map<UUID, String> summaries = new HashMap<>();
    for (EvidenceView entry : incidents.evidence(tenant, incident.id())) {
      summaries.put(entry.id(), entry.summary());
    }
    List<InvestigationSection.EvidenceUsed> used = new ArrayList<>();
    for (UUID id : accepted.evidenceEntryIds()) {
      String summary = summaries.get(id);
      if (summary != null) {
        used.add(new InvestigationSection.EvidenceUsed(id.toString(), summary));
      }
    }
    UserName requestedBy = null;
    Actor requester = accepted.requestedBy();
    if (accepted.origin() == Origin.RERUN
        && requester != null
        && requester.kind() == Actor.Kind.USER) {
      requestedBy = new Names(identity, serviceMap, tenant).user(requester.userId());
    }
    return new InvestigationSection.Result(
        accepted.likelyCause(),
        accepted.verdict() == null ? null : accepted.verdict().key(),
        List.copyOf(used),
        accepted.uncertainty(),
        accepted.runbooksSupplied().stream().map(RunbookRef::runbookKey).toList(),
        accepted.runbookRefs().stream().map(RunbookRef::runbookKey).sorted().toList(),
        aiGateway
            .provider(tenant, accepted.providerKey())
            .map(ProviderView::providerKind)
            .orElse(null),
        accepted.modelName(),
        ApiTimes.format(accepted.createdAt()),
        accepted.origin().key(),
        requestedBy);
  }

  // AR-I-27

  @GetMapping("/api/v1/incidents/{incident_id}/response")
  @RequiresPermission(Permission.VIEW)
  ResponseSection response(TenantId tenant, @PathVariable("incident_id") String incidentId) {
    IncidentView incident = IncidentGuard.workspace(incidents, tenant, incidentId);
    Optional<RunView> latest = latestLiveRun(engine, tenant, incident.id());
    if (latest.isEmpty()) {
      return new ResponseSection(null, null, false, List.of(), List.of());
    }
    RunView run = latest.get();
    Names names = new Names(identity, serviceMap, tenant);
    List<ApprovalRequestView> requests = actions.requests(tenant, run.id());
    ProposalView proposal = actions.proposal(tenant, run.id()).orElse(null);
    JsonNode actionSnapshot =
        proposal == null ? null : JSON.readTree(proposal.actionSnapshotJson());
    return new ResponseSection(
        similar(tenant, incident, run),
        proposal(tenant, proposal, actionSnapshot, requests),
        actions.withoutProposal(tenant, run.id()),
        approvals(requests, names),
        executions(tenant, incident, run, actionSnapshot));
  }

  /** CTX-D-079 (4): shown once the run's first SL-T-3 AI task step execution has completed. */
  private ResponseSection.Similar similar(TenantId tenant, IncidentView incident, RunView run) {
    Set<String> searchSteps = similaritySteps(JSON.readTree(run.contentJson()));
    StepExecutionView first =
        engine.stepExecutions(tenant, run.id()).stream()
            .filter(execution -> execution.stepType() == StepType.AI_TASK)
            .filter(execution -> searchSteps.contains(execution.stepId()))
            .findFirst()
            .orElse(null);
    if (first == null || first.state() != StepExecutionState.COMPLETED) {
      return null;
    }
    if (UNAVAILABLE.equals(first.namedOutput())) {
      return new ResponseSection.Similar(UNAVAILABLE, null, null, null, null, null);
    }
    SimilarityResultView result = knowledge.latestSearchResult(tenant, incident.id()).orElse(null);
    if (result == null) {
      return new ResponseSection.Similar("done", null, null, null, null, null);
    }
    if (result.outcome() == SearchOutcome.MATCH) {
      ResponseSection.PastIncident past =
          result.bestPastIncidentKey() == null
              ? null
              : knowledge
                  .pastIncident(tenant, result.bestPastIncidentKey())
                  .map(
                      p ->
                          new ResponseSection.PastIncident(
                              p.pastIncidentKey(), p.title(), p.recordLabel(), p.fixDescription()))
                  .orElse(null);
      return new ResponseSection.Similar(
          "done",
          result.outcome().key(),
          past,
          decimal(result.score()),
          result.successCount() == null ? 0 : result.successCount(),
          decimal(result.minSimilarity()));
    }
    return new ResponseSection.Similar(
        "done",
        result.outcome().key(),
        null,
        decimal(result.score()),
        null,
        decimal(result.minSimilarity()));
  }

  /** The step ids of the content's AI task steps with task SL-T-3. */
  private static Set<String> similaritySteps(JsonNode content) {
    Set<String> ids = new HashSet<>();
    JsonNode steps = content.get("steps");
    if (steps == null || !steps.isArray()) {
      return ids;
    }
    for (JsonNode step : steps) {
      JsonNode type = step.get("type");
      JsonNode parameters = step.get("parameters");
      JsonNode task = parameters == null ? null : parameters.get("task");
      if (type != null
          && StepType.AI_TASK.key().equals(type.stringValue())
          && task != null
          && SIMILARITY_TASK.equals(task.stringValue())
          && step.get("step_id") != null) {
        ids.add(step.get("step_id").stringValue());
      }
    }
    return ids;
  }

  private ResponseSection.Proposal proposal(
      TenantId tenant,
      ProposalView proposal,
      JsonNode actionSnapshot,
      List<ApprovalRequestView> requests) {
    if (proposal == null) {
      return null;
    }
    String targetName =
        requests.isEmpty()
            ? null
            : text(JSON.readTree(requests.getFirst().snapshotJson()).get("target_name"));
    String policy =
        requests.stream()
            .filter(request -> request.policyOutcome() != null)
            .reduce((first, second) -> second)
            .map(request -> request.policyOutcome().key())
            .orElse(null);
    return new ResponseSection.Proposal(
        text(actionSnapshot.get("name")),
        ActionParameters.ordered(
            runner,
            tenant,
            text(actionSnapshot.get("operation_key")),
            actionSnapshot.get("parameter_values")),
        new Target(proposal.targetKey(), targetName),
        policy);
  }

  private static List<ResponseSection.Approval> approvals(
      List<ApprovalRequestView> requests, Names names) {
    List<ResponseSection.Approval> approvals = new ArrayList<>();
    for (ApprovalRequestView request : requests) {
      approvals.add(
          new ResponseSection.Approval(
              request.id().toString(),
              request.sequenceInRun(),
              names.teams(request.eligibleTeamKeys()),
              request.state().key(),
              request.refusalReason() == null ? null : request.refusalReason().key(),
              decided(request, names),
              ApiTimes.format(request.expiresAt())));
    }
    return List.copyOf(approvals);
  }

  /** The person's decision of an {@code approved} or {@code rejected} request (DC-S05-05). */
  static IncidentPage.Acknowledged decided(ApprovalRequestView request, Names names) {
    if ((request.state() != RequestState.APPROVED && request.state() != RequestState.REJECTED)
        || request.decidedBy() == null) {
      return null;
    }
    return new IncidentPage.Acknowledged(
        names.user(request.decidedBy()), ApiTimes.format(request.decidedAt()));
  }

  private List<ResponseSection.Execution> executions(
      TenantId tenant, IncidentView incident, RunView run, JsonNode actionSnapshot) {
    List<ActionExecutionView> executions = actions.executions(tenant, run.id());
    if (executions.isEmpty()) {
      return List.of();
    }
    Map<UUID, String> summaries = new HashMap<>();
    for (EvidenceView entry : incidents.evidence(tenant, incident.id())) {
      if (entry.kind() == EvidenceKind.ACTION_EXECUTION && entry.producerRecordId() != null) {
        summaries.putIfAbsent(entry.producerRecordId(), entry.summary());
      }
    }
    JsonNode limit = actionSnapshot == null ? null : actionSnapshot.get("attempt_limit");
    Long attemptLimit = limit == null || !limit.isNumber() ? null : limit.longValue();
    List<ResponseSection.Execution> answer = new ArrayList<>();
    for (ActionExecutionView execution : executions) {
      answer.add(
          new ResponseSection.Execution(
              execution.id().toString(),
              execution.attemptNumber(),
              attemptLimit,
              execution.state().key(),
              rechecks(execution.recheckJson()),
              summaries.get(execution.id()),
              ApiTimes.format(execution.startedAt()),
              time(execution.finishedAt())));
    }
    return List.copyOf(answer);
  }

  private static List<ResponseSection.Recheck> rechecks(String recheckJson) {
    if (recheckJson == null) {
      return List.of();
    }
    List<ResponseSection.Recheck> rechecks = new ArrayList<>();
    for (JsonNode recheck : JSON.readTree(recheckJson)) {
      rechecks.add(
          new ResponseSection.Recheck(
              text(recheck.get("check")),
              recheck.get("passed").booleanValue(),
              text(recheck.get("detail"))));
    }
    return List.copyOf(rechecks);
  }

  // AR-I-28

  @GetMapping("/api/v1/incidents/{incident_id}/recovery")
  @RequiresPermission(Permission.VIEW)
  RecoverySection recovery(TenantId tenant, @PathVariable("incident_id") String incidentId) {
    IncidentView incident = IncidentGuard.workspace(incidents, tenant, incidentId);
    Optional<RunView> run = latestLiveRun(engine, tenant, incident.id());
    if (run.isEmpty()) {
      return new RecoverySection(List.of());
    }
    List<RecoveryVerificationView> verifications =
        recovery.verifications(tenant, incident.id()).stream()
            .filter(verification -> run.get().id().equals(verification.runId()))
            .sorted(
                Comparator.comparing(RecoveryVerificationView::startedAt)
                    .thenComparing(verification -> verification.id().toString()))
            .toList();
    if (verifications.isEmpty()) {
      return new RecoverySection(List.of());
    }
    Map<String, String> sourceNames = new HashMap<>();
    for (SourceView source : intake.sources(tenant)) {
      sourceNames.put(source.id().toString(), source.name());
    }
    return new RecoverySection(
        verifications.stream()
            .map(verification -> verification(verification, sourceNames))
            .toList());
  }

  private static RecoverySection.Verification verification(
      RecoveryVerificationView verification, Map<String, String> sourceNames) {
    JsonNode snapshot = JSON.readTree(verification.definitionSnapshotJson());
    List<RecoverySection.Condition> conditions = new ArrayList<>();
    Boolean sourcesHealthy = null;
    List<String> unhealthy = new ArrayList<>();
    if (verification.lastEvaluationJson() != null) {
      JsonNode evaluation = JSON.readTree(verification.lastEvaluationJson());
      JsonNode evaluated = evaluation.get("conditions");
      if (evaluated != null && evaluated.isArray()) {
        for (JsonNode condition : evaluated) {
          conditions.add(
              new RecoverySection.Condition(
                  text(condition.get("detail")), condition.get("held").booleanValue()));
        }
      }
      JsonNode healthy = evaluation.get("sources_healthy");
      sourcesHealthy = healthy == null || healthy.isNull() ? null : healthy.booleanValue();
      JsonNode ids = evaluation.get("unhealthy_source_ids");
      if (ids != null && ids.isArray()) {
        for (JsonNode id : ids) {
          unhealthy.add(sourceNames.getOrDefault(id.stringValue(), id.stringValue()));
        }
      }
    }
    unhealthy.sort(Comparator.naturalOrder());
    return new RecoverySection.Verification(
        verification.id().toString(),
        text(snapshot.get("name")),
        verification.state().key(),
        snapshot.get("observation_window_seconds").longValue(),
        ApiTimes.format(verification.startedAt()),
        ApiTimes.format(verification.maxWaitUntil()),
        time(verification.windowStartedAt()),
        time(verification.decidedAt()),
        List.copyOf(conditions),
        sourcesHealthy,
        List.copyOf(unhealthy));
  }

  // Shared.

  /** The incident's latest live run, by {@code started_at}, then id, as AR-I-29 finds it. */
  static Optional<RunView> latestLiveRun(EngineService engine, TenantId tenant, UUID incidentId) {
    return engine.runs(tenant, incidentId).stream()
        .filter(run -> run.mode() == RunMode.LIVE)
        .max(Comparator.comparing(RunView::startedAt).thenComparing(run -> run.id().toString()));
  }

  /** A decimal as text with two decimal places, or {@code null}. */
  static String decimal(BigDecimal value) {
    return value == null ? null : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
  }

  private static String time(java.time.Instant instant) {
    return instant == null ? null : ApiTimes.format(instant);
  }

  private static String text(JsonNode node) {
    return node == null || node.isNull() ? null : node.stringValue();
  }
}
