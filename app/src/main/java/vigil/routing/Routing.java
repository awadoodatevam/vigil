package vigil.routing;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ObjectNode;
import vigil.audit.api.AuditEntry;
import vigil.audit.api.AuditEventType;
import vigil.audit.api.AuditService;
import vigil.audit.api.ConfigurationArea;
import vigil.identity.api.IdentityService;
import vigil.identity.api.Permission;
import vigil.identity.api.TeamView;
import vigil.incidents.api.AffectedServiceCopy;
import vigil.incidents.api.IncidentService;
import vigil.incidents.api.IncidentState;
import vigil.incidents.api.IncidentView;
import vigil.routing.api.EscalationPolicyView;
import vigil.routing.api.EvaluationView;
import vigil.routing.api.NotAppliedReason;
import vigil.routing.api.OwnerMode;
import vigil.routing.api.RequestedBy;
import vigil.routing.api.RoutingCondition;
import vigil.routing.api.RoutingResult;
import vigil.routing.api.RoutingRule;
import vigil.routing.api.RoutingService;
import vigil.routing.api.RuleSetView;
import vigil.servicemap.api.ComponentView;
import vigil.servicemap.api.ServiceMapService;
import vigil.servicemap.api.ServiceView;
import vigil.shared.Actor;
import vigil.shared.Ids;
import vigil.shared.Key;
import vigil.shared.TenantId;
import vigil.shared.Timestamps;
import vigil.shared.VigilError;

/** The requests and reads of vigil-routing (PM-M10; P-15 spec Sections 6.4, 6.6). */
@Service
class Routing implements RoutingService {

  private static final Logger LOG = LoggerFactory.getLogger(Routing.class);

  private static final String CORRELATION = "vigil-correlation";
  private static final String WORKFLOWS = "vigil-workflows";
  private static final Actor ROUTING = Actor.module("vigil-routing");
  static final int NAME_MAX = 100;

  private final RoutingRecords records;
  private final IncidentService incidents;
  private final ServiceMapService serviceMap;
  private final IdentityService identity;
  private final AuditService audit;
  private final Clock clock;

  Routing(
      RoutingRecords records,
      IncidentService incidents,
      ServiceMapService serviceMap,
      IdentityService identity,
      AuditService audit,
      Clock clock) {
    this.records = records;
    this.incidents = incidents;
    this.serviceMap = serviceMap;
    this.identity = identity;
    this.audit = audit;
    this.clock = clock;
  }

  /** "Active team": a valid key of a team that exists and is active. */
  static Predicate<String> activeTeams(IdentityService identity, TenantId tenantId) {
    return key ->
        RuleSetValidation.isKey(key)
            && identity.team(tenantId, new Key(key)).map(TeamView::active).orElse(false);
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED)
  public EvaluationView evaluate(
      TenantId tenantId, Actor caller, UUID incidentId, RequestedBy requestedBy) {
    required(tenantId, caller, incidentId, requestedBy);
    String module = requestedBy == RequestedBy.CORRELATION ? CORRELATION : WORKFLOWS;
    if (!caller.equals(Actor.module(module))) {
      throw new IllegalArgumentException(
          "evaluate with " + requestedBy.key() + " is accepted only from " + module);
    }
    IncidentView incident =
        incidents
            .incident(tenantId, incidentId)
            .orElseThrow(() -> new VigilError(VigilError.Code.NOT_FOUND));
    RuleSetView ruleSet = records.currentRuleSet(tenantId).orElseThrow(Routing::noRules);
    RoutingInputs inputs = RoutingInputs.of(incident);
    RoutingRule rule =
        RuleMatcher.first(ruleSet.rules(), inputs)
            .orElseThrow(() -> new IllegalStateException("no routing rule matches"));
    RoutingResult result = result(tenantId, rule, incident);
    NotAppliedReason reason;
    if (requestedBy == RequestedBy.TEST_REPLAY) {
      reason = NotAppliedReason.DRY_RUN;
    } else if (incident.state() != IncidentState.FORMING
        && incident.state() != IncidentState.OPEN) {
      reason = NotAppliedReason.INCIDENT_NOT_ACTIVE;
    } else if (incident.state() == IncidentState.OPEN || incident.assignmentFrozen()) {
      reason = NotAppliedReason.ASSIGNMENT_FROZEN;
    } else {
      reason = null;
    }
    EvaluationView evaluation =
        new EvaluationView(
            Ids.newId(),
            incidentId,
            requestedBy,
            ruleSet.revision(),
            inputs.json(),
            rule.ruleKey(),
            rule.name(),
            result,
            reason == null,
            reason,
            Timestamps.now(clock));
    records.insertEvaluation(tenantId, evaluation);
    if (reason == null) {
      incidents.setAssignment(
          tenantId,
          ROUTING,
          incidentId,
          result.severity(),
          new Key(result.ownerTeamKey()),
          result.informedTeamKeys().stream().map(Key::new).collect(Collectors.toSet()),
          evaluation.id(),
          rule.name());
    }
    return evaluation;
  }

  /** DM-V-09 with DM-E-41, DM-E41-R2 and BP-A-28. */
  private RoutingResult result(TenantId tenantId, RoutingRule rule, IncidentView incident) {
    Predicate<String> active = activeTeams(identity, tenantId);
    String owner = rule.ownerTeamKey();
    if (rule.ownerMode() == OwnerMode.HYPOTHESIS_COMPONENT_OWNER
        && incident.hypothesisComponentKey() != null) {
      owner =
          serviceMap
              .component(tenantId, incident.hypothesisComponentKey())
              .map(ComponentView::ownerTeamKey)
              .filter(active)
              .orElse(owner);
    }
    Set<String> informed = new TreeSet<>(RoutingJson.CODE_POINTS);
    informed.addAll(rule.informedTeamKeys());
    if (rule.informAffectedServiceOwners()) {
      for (AffectedServiceCopy service : incident.affectedServices()) {
        serviceMap
            .service(tenantId, service.serviceKey())
            .map(ServiceView::ownerTeamKey)
            .filter(active)
            .ifPresent(informed::add);
      }
    }
    informed.remove(owner);
    return new RoutingResult(rule.severity(), owner, List.copyOf(informed));
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED)
  public RuleSetView saveRuleSet(
      TenantId tenantId, UUID userId, long baseRevision, List<RoutingRule> rules) {
    checkRequest(tenantId, userId, rules);
    if (!identity.hasPermission(tenantId, userId, Permission.MANAGE_ROUTING)) {
      throw new VigilError(VigilError.Code.FORBIDDEN);
    }
    records.lock(tenantId);
    RuleSetView current = records.currentRuleSet(tenantId).orElseThrow(Routing::noRules);
    if (baseRevision != current.revision()) {
      throw new VigilError(VigilError.Code.CONFLICT);
    }
    List<RuleSetValidation.Problem> problems =
        RuleSetValidation.problems(rules, activeTeams(identity, tenantId));
    if (!problems.isEmpty()) {
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("problems", problems.stream().map(RuleSetValidation.Problem::text).toList());
      details.put("places", problems.stream().map(RuleSetValidation.Problem::place).toList());
      throw new VigilError(VigilError.Code.INVALID, null, details);
    }
    List<RoutingRule> stored = withKeys(rules, current.rules());
    String summary = ChangeSummary.of(current.rules(), stored);
    long revision = current.revision() + 1;
    Instant now = Timestamps.now(clock);
    UUID id = Ids.newId();
    Actor user = Actor.user(userId);
    records.insertRuleSet(
        tenantId, id, revision, RoutingJson.write(RoutingJson.rules(stored)), user, now, summary);
    ObjectNode details = RoutingJson.JSON.createObjectNode();
    details.put("area", ConfigurationArea.ROUTING_RULES.key());
    details.set("before", revisionJson(current.revision(), current.rules()));
    details.set("after", revisionJson(revision, stored));
    audit.append(
        new AuditEntry(
            tenantId,
            now,
            user,
            AuditEventType.CONFIGURATION_CHANGED,
            "RoutingRuleSetRevision",
            id,
            null,
            ChangeSummary.audit(revision, summary),
            RoutingJson.write(details)));
    LOG.info("routing rules of tenant {} saved as revision {}", tenantId.value(), revision);
    return new RuleSetView(id, revision, stored, user, now, summary);
  }

  private static ObjectNode revisionJson(long revision, List<RoutingRule> rules) {
    ObjectNode node = RoutingJson.JSON.createObjectNode();
    node.put("revision", revision);
    node.set("rules", RoutingJson.rules(rules));
    return node;
  }

  /** Step (1) of the save: a request the screen cannot send (P-38: 400 {@code bad_request}). */
  private static void checkRequest(TenantId tenantId, UUID userId, List<RoutingRule> rules) {
    required(tenantId, userId, rules);
    Set<String> keys = new HashSet<>();
    for (RoutingRule rule : rules) {
      required(rule);
      required(rule.conditions(), rule.informedTeamKeys(), rule.severity(), rule.ownerMode());
      rule.conditions().forEach(Routing::required);
      rule.informedTeamKeys().forEach(Routing::required);
      if (rule.ruleKey() != null
          && (!RuleSetValidation.isKey(rule.ruleKey()) || !keys.add(rule.ruleKey()))) {
        throw new IllegalArgumentException("ruleKey: a key used by one rule only");
      }
      if (rule.name() != null && rule.name().codePointCount(0, rule.name().length()) > NAME_MAX) {
        throw new IllegalArgumentException("name: at most 100 characters");
      }
      for (RoutingCondition condition : rule.conditions()) {
        if (RuleSetValidation.complete(condition)
            && (!condition.field().operators().contains(condition.operator())
                || (!RoutingJson.isList(condition.operator()) && condition.values().size() > 1))) {
          throw new IllegalArgumentException(
              "condition: an operator of its field with one value, or values for in and not_in");
        }
      }
    }
  }

  /**
   * The rules as stored, rules without a key given {@code rule-{n}}, n the lowest whole number not
   * used by a rule of the new list or of the current revision, in list order.
   */
  private static List<RoutingRule> withKeys(List<RoutingRule> rules, List<RoutingRule> current) {
    Set<String> used = new HashSet<>();
    rules.stream().map(RoutingRule::ruleKey).forEach(used::add);
    current.stream().map(RoutingRule::ruleKey).forEach(used::add);
    List<RoutingRule> keyed = new ArrayList<>();
    int n = 1;
    for (RoutingRule rule : rules) {
      String key = rule.ruleKey();
      if (key == null) {
        while (used.contains("rule-" + n)) {
          n++;
        }
        key = "rule-" + n;
        used.add(key);
      }
      keyed.add(
          RoutingJson.stored(
              new RoutingRule(
                  key,
                  rule.name(),
                  rule.conditions(),
                  rule.severity(),
                  rule.ownerMode(),
                  rule.ownerTeamKey(),
                  rule.informedTeamKeys(),
                  rule.informAffectedServiceOwners())));
    }
    return List.copyOf(keyed);
  }

  @Override
  public Optional<RuleSetView> ruleSet(TenantId tenantId) {
    required(tenantId);
    return records.currentRuleSet(tenantId);
  }

  @Override
  public Optional<RuleSetView> ruleSetRevision(TenantId tenantId, long revision) {
    required(tenantId);
    return records.ruleSet(tenantId, revision);
  }

  @Override
  public Optional<EscalationPolicyView> escalationPolicy(TenantId tenantId) {
    required(tenantId);
    return records.currentPolicy(tenantId);
  }

  @Override
  public List<EvaluationView> evaluations(TenantId tenantId, UUID incidentId) {
    required(tenantId, incidentId);
    return List.copyOf(records.evaluations(tenantId, incidentId));
  }

  @Override
  public Optional<EvaluationView> evaluation(TenantId tenantId, UUID evaluationId) {
    required(tenantId, evaluationId);
    return records.evaluation(tenantId, evaluationId);
  }

  static IllegalStateException noRules() {
    return new IllegalStateException("the tenant has no routing rules");
  }

  private static void required(Object... values) {
    for (Object value : values) {
      if (value == null) {
        throw new IllegalArgumentException("a required argument is missing");
      }
    }
  }
}
