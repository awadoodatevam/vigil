package vigil.api;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vigil.actions.api.ActionsService;
import vigil.actions.api.ApprovalRequestView;
import vigil.actions.api.Decision;
import vigil.identity.api.IdentityService;
import vigil.identity.api.Permission;
import vigil.incidents.api.IncidentService;
import vigil.runner.api.RunnerService;
import vigil.servicemap.api.ServiceMapService;
import vigil.shared.TenantId;
import vigil.shared.VigilError;
import vigil.shared.VigilError.Code;

/** AR-I-34 the Approval page (DC-S05) and AR-I-35 the decision (DC-S05-04). */
@RestController
@Profile("!migrate")
class ApprovalController {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** The refusals that depend on the user (CTX-D-079 (2)). */
  private static final Set<String> PERSONAL = Set.of("not_eligible", "excluded");

  private static final Set<String> BODY_MEMBERS = Set.of("decision", "snapshot_hash_shown");
  private static final int HASH_MAX = 128;

  private final ActionsService actions;
  private final IncidentService incidents;
  private final RunnerService runner;
  private final IdentityService identity;
  private final ServiceMapService serviceMap;

  ApprovalController(
      ActionsService actions,
      IncidentService incidents,
      RunnerService runner,
      IdentityService identity,
      ServiceMapService serviceMap) {
    this.actions = actions;
    this.incidents = incidents;
    this.runner = runner;
    this.identity = identity;
    this.serviceMap = serviceMap;
  }

  @GetMapping("/api/v1/approval-requests/{request_id}")
  @RequiresPermission({Permission.APPROVE_ACTION, Permission.VIEW})
  ApprovalPage page(ApiCaller caller, @PathVariable("request_id") String requestId) {
    TenantId tenant = caller.tenantId();
    ApprovalRequestView request = request(tenant, requestId);
    Names names = new Names(identity, serviceMap, tenant);
    boolean mayAct = identity.hasPermission(tenant, caller.user().id(), Permission.APPROVE_ACTION);
    Refusal refusal =
        actions
            .decisionRefusal(tenant, request.id(), caller.user().id())
            .filter(r -> mayAct || !PERSONAL.contains(r.key()))
            .map(r -> new Refusal(r.key(), r.message()))
            .orElse(null);
    ApprovalPage.Incident incident =
        incidents
            .incident(tenant, request.incidentId())
            .map(i -> new ApprovalPage.Incident(i.id().toString(), i.number(), i.title()))
            .orElse(new ApprovalPage.Incident(request.incidentId().toString(), null, null));
    String operationKey =
        actions
            .proposal(tenant, request.runId())
            .map(p -> JSON.readTree(p.actionSnapshotJson()).get("operation_key"))
            .filter(JsonNode::isString)
            .map(JsonNode::stringValue)
            .orElse(null);
    JsonNode snapshot = JSON.readTree(request.snapshotJson());
    JsonNode basedOn = snapshot.get("based_on");
    JsonNode checks = snapshot.get("recovery_checks");
    return new ApprovalPage(
        request.id().toString(),
        request.sequenceInRun(),
        incident,
        request.state().key(),
        request.refusalReason() == null ? null : request.refusalReason().key(),
        request.snapshotHash(),
        snapshot.get("action_name").stringValue(),
        ActionParameters.ordered(runner, tenant, operationKey, snapshot.get("parameters")),
        new Target(
            snapshot.get("target_key").stringValue(), snapshot.get("target_name").stringValue()),
        texts(snapshot.get("limits")),
        snapshot.get("attempt_number").longValue(),
        snapshot.get("attempt_limit").longValue(),
        instant(snapshot.get("expires_at")),
        new ApprovalPage.BasedOn(
            basedOn.get("past_incident_key").stringValue(),
            basedOn.get("past_incident_title").stringValue(),
            basedOn.get("record_label").stringValue(),
            basedOn.get("score").stringValue(),
            basedOn.get("success_count").longValue()),
        evidence(snapshot.get("evidence")),
        new ApprovalPage.RecoveryChecks(
            checks.get("name").stringValue(),
            texts(checks.get("conditions")),
            checks.get("observation_window").longValue()),
        SectionsController.decided(request, names),
        mayAct,
        refusal);
  }

  @PostMapping("/api/v1/approval-requests/{request_id}/decisions")
  @RequiresPermission(Permission.APPROVE_ACTION)
  ResponseEntity<NewDecision> decide(
      ApiCaller caller, @PathVariable("request_id") String requestId, @RequestBody JsonNode body) {
    if (!isDecisionBody(body)) {
      throw new VigilError(Code.BAD_REQUEST);
    }
    TenantId tenant = caller.tenantId();
    ApprovalRequestView request = request(tenant, requestId);
    Decision decision = Decision.fromKey(body.get("decision").stringValue()).orElseThrow();
    ApprovalRequestView decided =
        actions.decide(
            tenant,
            request.id(),
            caller.user().id(),
            decision,
            body.get("snapshot_hash_shown").stringValue());
    Names names = new Names(identity, serviceMap, tenant);
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            new NewDecision(
                decided.state().key(),
                names.user(decided.decidedBy()),
                ApiTimes.format(decided.decidedAt())));
  }

  /** Exactly {@code decision} (approve or reject) and a hash of 1 to 128 code points. */
  private static boolean isDecisionBody(JsonNode body) {
    if (body == null
        || !body.isObject()
        || body.size() != BODY_MEMBERS.size()
        || !BODY_MEMBERS.containsAll(body.propertyNames())) {
      return false;
    }
    JsonNode decision = body.get("decision");
    JsonNode hash = body.get("snapshot_hash_shown");
    if (!decision.isString() || Decision.fromKey(decision.stringValue()).isEmpty()) {
      return false;
    }
    if (!hash.isString()) {
      return false;
    }
    int length = hash.stringValue().codePointCount(0, hash.stringValue().length());
    return length >= 1 && length <= HASH_MAX;
  }

  /** The request guard: a UUID in the 8-4-4-4-12 form of a request of the tenant. */
  private ApprovalRequestView request(TenantId tenant, String requestId) {
    if (requestId == null || !IncidentGuard.UUID_TEXT.matcher(requestId).matches()) {
      throw new VigilError(Code.NOT_FOUND);
    }
    return actions
        .request(tenant, UUID.fromString(requestId))
        .orElseThrow(() -> new VigilError(Code.NOT_FOUND));
  }

  private static List<String> texts(JsonNode array) {
    List<String> texts = new ArrayList<>();
    if (array != null && array.isArray()) {
      array.forEach(node -> texts.add(node.stringValue()));
    }
    return List.copyOf(texts);
  }

  private static List<ApprovalPage.Evidence> evidence(JsonNode entries) {
    List<ApprovalPage.Evidence> evidence = new ArrayList<>();
    if (entries != null && entries.isArray()) {
      for (JsonNode entry : entries) {
        evidence.add(
            new ApprovalPage.Evidence(
                entry.get("evidence_entry_id").stringValue(),
                entry.get("kind").stringValue(),
                entry.get("summary").stringValue(),
                instant(entry.get("collected_at"))));
      }
    }
    return List.copyOf(evidence);
  }

  /** A snapshot instant, stored with {@code Instant.toString()}, in the outbound format. */
  private static String instant(JsonNode node) {
    return ApiTimes.format(Instant.parse(node.stringValue()));
  }
}
