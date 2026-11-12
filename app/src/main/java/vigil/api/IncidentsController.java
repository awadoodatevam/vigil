package vigil.api;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vigil.correlation.api.CorrelationService;
import vigil.correlation.api.HypothesisComputationView;
import vigil.engine.api.EngineService;
import vigil.engine.api.Stage;
import vigil.identity.api.IdentityService;
import vigil.identity.api.Permission;
import vigil.incidents.api.AffectedServiceCopy;
import vigil.incidents.api.EscalationView;
import vigil.incidents.api.HypothesisCopy;
import vigil.incidents.api.IncidentService;
import vigil.incidents.api.IncidentState;
import vigil.incidents.api.IncidentView;
import vigil.routing.api.EvaluationView;
import vigil.routing.api.RoutingService;
import vigil.servicemap.api.ServiceMapService;
import vigil.shared.TenantId;
import vigil.ticketing.api.TicketingService;

/** AR-I-21 the incident list (DC-S02-04) and AR-I-22 the workspace header (DC-S03-02 to 04). */
@RestController
@Profile("!migrate")
class IncidentsController {

  private static final Set<IncidentState> LISTED =
      EnumSet.of(IncidentState.OPEN, IncidentState.RESOLVED, IncidentState.CLOSED);

  private final IncidentService incidents;
  private final IdentityService identity;
  private final ServiceMapService serviceMap;
  private final TicketingService ticketing;
  private final EngineService engine;
  private final RoutingService routing;
  private final CorrelationService correlation;

  IncidentsController(
      IncidentService incidents,
      IdentityService identity,
      ServiceMapService serviceMap,
      TicketingService ticketing,
      EngineService engine,
      RoutingService routing,
      CorrelationService correlation) {
    this.incidents = incidents;
    this.identity = identity;
    this.serviceMap = serviceMap;
    this.ticketing = ticketing;
    this.engine = engine;
    this.routing = routing;
    this.correlation = correlation;
  }

  @GetMapping("/api/v1/incidents")
  @RequiresPermission(Permission.VIEW)
  IncidentPage list(
      TenantId tenant,
      @RequestParam(name = "limit", required = false) String limit,
      @RequestParam(name = "after", required = false) String after) {
    int size = IncidentCursor.limit(limit);
    Long before = IncidentCursor.after(after);
    List<IncidentView> read = incidents.incidentPage(tenant, LISTED, before, size + 1);
    List<IncidentView> rows = read.size() > size ? read.subList(0, size) : read;
    String next = read.size() > size ? IncidentCursor.cursor(rows.getLast().number()) : null;
    List<UUID> open =
        rows.stream()
            .filter(incident -> incident.state() == IncidentState.OPEN)
            .map(IncidentView::id)
            .toList();
    Map<UUID, Stage> stages = engine.stages(tenant, open);
    Names names = names(tenant);
    List<IncidentPage.Row> items = new ArrayList<>();
    for (IncidentView incident : rows) {
      items.add(
          new IncidentPage.Row(
              incident.id().toString(),
              incident.number(),
              incident.title(),
              severity(incident),
              incident.state().key(),
              stage(incident, stages),
              owner(incident, names),
              acknowledged(incident, names),
              incident.alertCount(),
              ApiTimes.format(incident.firstAlertFiredAt()),
              ticket(tenant, incident.id())));
    }
    return new IncidentPage(List.copyOf(items), next);
  }

  @GetMapping("/api/v1/incidents/{incident_id}")
  @RequiresPermission(Permission.VIEW)
  Object header(TenantId tenant, @PathVariable("incident_id") String incidentId) {
    IncidentView incident = IncidentGuard.incident(incidents, tenant, incidentId);
    if (incident.state() == IncidentState.MERGED) {
      return new MergedIncident(incident.mergedIntoIncidentId().toString());
    }
    Names names = names(tenant);
    Map<UUID, Stage> stages =
        incident.state() == IncidentState.OPEN
            ? engine.stages(tenant, List.of(incident.id()))
            : Map.of();
    return new IncidentHeader(
        incident.id().toString(),
        incident.number(),
        incident.title(),
        incident.state().key(),
        stage(incident, stages),
        severity(incident),
        owner(incident, names),
        names.teams(incident.informedTeamKeys()),
        ruleName(tenant, incident),
        acknowledged(incident, names),
        escalation(tenant, incident, names),
        ApiTimes.format(incident.firstAlertFiredAt()),
        incident.resolvedAt() == null ? null : ApiTimes.format(incident.resolvedAt()),
        ticket(tenant, incident.id()),
        incident.reopenCount(),
        incident.alertCount(),
        hypothesis(tenant, incident),
        affectedServices(incident, names));
  }

  private Names names(TenantId tenant) {
    return new Names(identity, serviceMap, tenant);
  }

  private static String severity(IncidentView incident) {
    return incident.severity() == null ? null : incident.severity().key();
  }

  /** DC-TX-01 "Stage": only for an {@code open} incident. */
  private static String stage(IncidentView incident, Map<UUID, Stage> stages) {
    Stage stage = incident.state() == IncidentState.OPEN ? stages.get(incident.id()) : null;
    return stage == null ? null : stage.key();
  }

  private static TeamName owner(IncidentView incident, Names names) {
    return incident.ownerTeamKey() == null ? null : names.team(incident.ownerTeamKey());
  }

  private static IncidentPage.Acknowledged acknowledged(IncidentView incident, Names names) {
    if (incident.acknowledgedBy() == null) {
      return null;
    }
    return new IncidentPage.Acknowledged(
        names.user(incident.acknowledgedBy()), ApiTimes.format(incident.acknowledgedAt()));
  }

  private IncidentPage.Ticket ticket(TenantId tenant, UUID incidentId) {
    return ticketing
        .ticketLink(tenant, incidentId)
        .map(link -> new IncidentPage.Ticket(link.externalKey(), link.url()))
        .orElse(null);
  }

  private String ruleName(TenantId tenant, IncidentView incident) {
    if (incident.assignmentEvaluationId() == null) {
      return null;
    }
    return routing
        .evaluation(tenant, incident.assignmentEvaluationId())
        .map(EvaluationView::matchedRuleName)
        .orElse(null);
  }

  /** The latest escalation record of the incident's level, by {@code at}, then id. */
  private IncidentHeader.Escalation escalation(
      TenantId tenant, IncidentView incident, Names names) {
    if (incident.escalationLevel() <= 0) {
      return null;
    }
    List<TeamName> teams =
        incidents.escalations(tenant, incident.id()).stream()
            .filter(escalation -> escalation.level() == incident.escalationLevel())
            .max(
                Comparator.comparing(EscalationView::at)
                    .thenComparing(escalation -> escalation.id().toString()))
            .map(escalation -> names.teams(escalation.teamKeys()))
            .orElse(List.of());
    return new IncidentHeader.Escalation(incident.escalationLevel(), teams);
  }

  private IncidentHeader.Hypothesis hypothesis(TenantId tenant, IncidentView incident) {
    HypothesisCopy copy = incident.hypothesis();
    if (copy == null) {
      return null;
    }
    long others = 0;
    long dependents = 0;
    String tieBreak = "none";
    HypothesisComputationView computation =
        correlation.latestHypothesisComputation(tenant, incident.id()).orElse(null);
    if (computation != null) {
      String result = computation.resultComponentKey();
      others = computation.affectedComponentKeys().stream().filter(k -> !k.equals(result)).count();
      Long count = result == null ? null : computation.dependentCounts().get(result);
      dependents = count == null ? 0 : count;
      tieBreak = computation.tieBreak() == null ? tieBreak : computation.tieBreak().key();
    }
    return new IncidentHeader.Hypothesis(
        copy.componentKey(),
        copy.name(),
        copy.kind(),
        copy.criticality(),
        others,
        dependents,
        tieBreak);
  }

  private static List<IncidentHeader.AffectedService> affectedServices(
      IncidentView incident, Names names) {
    return incident.affectedServices().stream()
        .sorted(Comparator.comparing(AffectedServiceCopy::serviceKey))
        .map(
            service ->
                new IncidentHeader.AffectedService(
                    service.serviceKey(),
                    service.name(),
                    service.criticality(),
                    service.viaComponentKeys().stream()
                        .distinct()
                        .map(key -> new ComponentName(key, names.component(key)))
                        .sorted(
                            Comparator.comparing(ComponentName::name)
                                .thenComparing(ComponentName::componentKey))
                        .toList()))
        .toList();
  }
}
