package vigil.api;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vigil.correlation.api.CorrelationService;
import vigil.correlation.api.HypothesisComputationView;
import vigil.engine.api.EngineService;
import vigil.engine.api.RunMode;
import vigil.engine.api.RunView;
import vigil.engine.api.StepExecutionView;
import vigil.engine.api.TimerView;
import vigil.identity.api.IdentityService;
import vigil.identity.api.Permission;
import vigil.incidents.api.AlertCopy;
import vigil.incidents.api.EvidenceKind;
import vigil.incidents.api.EvidenceView;
import vigil.incidents.api.IncidentService;
import vigil.incidents.api.IncidentView;
import vigil.incidents.api.MembershipView;
import vigil.incidents.api.NotifiedTeamView;
import vigil.incidents.api.TimelineEntryType;
import vigil.incidents.api.TimelineView;
import vigil.intake.api.AlertStatus;
import vigil.intake.api.AlertView;
import vigil.intake.api.IntakeService;
import vigil.servicemap.api.ServiceMapService;
import vigil.shared.TenantId;

/**
 * The workspace sections of an incident: AR-I-23 the member alerts (DC-S03-05), AR-I-24 the
 * evidence (DC-S03-08), AR-I-29 the latest live run (DC-S03-06, DC-S03-07, DC-S03-12), AR-I-30 the
 * timeline (DC-S03-13) and AR-I-31 the notified teams (DC-S03-14).
 */
@RestController
@Profile("!migrate")
class WorkspaceController {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String ONLY_ALERT = "only_alert";
  private static final String SAME_UNKNOWN_IDENTITY = "same_unknown_identity";
  private static final List<String> TEAM_PARAMETERS = List.of("eligible_teams", "named_teams");

  private final IncidentService incidents;
  private final IntakeService intake;
  private final CorrelationService correlation;
  private final EngineService engine;
  private final IdentityService identity;
  private final ServiceMapService serviceMap;

  WorkspaceController(
      IncidentService incidents,
      IntakeService intake,
      CorrelationService correlation,
      EngineService engine,
      IdentityService identity,
      ServiceMapService serviceMap) {
    this.incidents = incidents;
    this.intake = intake;
    this.correlation = correlation;
    this.engine = engine;
    this.identity = identity;
    this.serviceMap = serviceMap;
  }

  private Names names(TenantId tenant) {
    return new Names(identity, serviceMap, tenant);
  }

  // AR-I-23

  @GetMapping("/api/v1/incidents/{incident_id}/alerts")
  @RequiresPermission(Permission.VIEW)
  IncidentAlerts alerts(TenantId tenant, @PathVariable("incident_id") String incidentId) {
    IncidentView incident = IncidentGuard.workspace(incidents, tenant, incidentId);
    List<MembershipView> members = incidents.members(tenant, incident.id());
    Map<UUID, JsonNode> reasons = new HashMap<>();
    Set<UUID> alertIds = new LinkedHashSet<>();
    for (MembershipView member : members) {
      alertIds.add(member.alertId());
      JsonNode reason = JSON.readTree(member.finalReasonJson());
      reasons.put(member.id(), reason);
      UUID related = relatedAlertId(reason);
      if (related != null) {
        alertIds.add(related);
      }
    }
    Map<UUID, AlertView> alerts = new HashMap<>();
    if (!alertIds.isEmpty()) {
      intake.alerts(tenant, alertIds).forEach(alert -> alerts.put(alert.id(), alert));
    }
    Names names = names(tenant);
    long firing = 0;
    long resolved = 0;
    Map<String, List<MembershipView>> byType = new HashMap<>();
    for (MembershipView member : members) {
      AlertView alert = alerts.get(member.alertId());
      if (alert != null && alert.status() == AlertStatus.FIRING) {
        firing++;
      } else if (alert != null && alert.status() == AlertStatus.RESOLVED) {
        resolved++;
      }
      byType.computeIfAbsent(member.alert().alertType(), type -> new ArrayList<>()).add(member);
    }
    List<IncidentAlerts.Group> groups = new ArrayList<>();
    for (List<MembershipView> typeMembers : byType.values()) {
      List<MembershipView> ordered =
          typeMembers.stream()
              .sorted(
                  Comparator.comparing((MembershipView m) -> m.alert().firedAt())
                      .thenComparing(m -> m.alert().sourceKind())
                      .thenComparing(m -> m.alert().alertKey()))
              .toList();
      AlertCopy first = ordered.getFirst().alert();
      groups.add(
          new IncidentAlerts.Group(
              first.alertType(),
              first.alertTypeName(),
              ordered.size(),
              ordered.stream()
                  .map(member -> alert(member, reasons.get(member.id()), alerts, names))
                  .toList()));
    }
    groups.sort(
        Comparator.comparing(IncidentAlerts.Group::alertTypeName)
            .thenComparing(IncidentAlerts.Group::alertType));
    Long window =
        correlation
            .settings(tenant)
            .map(settings -> settings.groupingWindow().toSeconds())
            .orElse(null);
    return new IncidentAlerts(firing, resolved, window, List.copyOf(groups));
  }

  private static IncidentAlerts.Alert alert(
      MembershipView member, JsonNode finalReason, Map<UUID, AlertView> alerts, Names names) {
    AlertCopy copy = member.alert();
    AlertView alert = alerts.get(member.alertId());
    return new IncidentAlerts.Alert(
        member.alertId().toString(),
        ApiTimes.format(copy.firedAt()),
        copy.sourceKind(),
        copy.title(),
        copy.componentKey() == null ? null : names.component(copy.componentKey()),
        alert == null ? null : alert.status().key(),
        reason(member, finalReason, alerts, names));
  }

  /** DM-E-34 {@code final_reason} with the related alert, path names and identity pair. */
  private static IncidentAlerts.Reason reason(
      MembershipView member, JsonNode finalReason, Map<UUID, AlertView> alerts, Names names) {
    String relation = finalReason.get("relation").stringValue();
    boolean reopened = isReopened(member.reasonJson());
    if (ONLY_ALERT.equals(relation)) {
      return new IncidentAlerts.Reason(relation, reopened, null, List.of(), null, null);
    }
    UUID relatedId = relatedAlertId(finalReason);
    AlertView related = relatedId == null ? null : alerts.get(relatedId);
    List<ComponentName> path = new ArrayList<>();
    JsonNode keys = finalReason.get("relation_path");
    if (keys != null && keys.isArray()) {
      for (JsonNode key : keys) {
        path.add(new ComponentName(key.stringValue(), names.component(key.stringValue())));
      }
    }
    JsonNode delta = finalReason.get("time_delta");
    IncidentAlerts.Identity pair = null;
    if (SAME_UNKNOWN_IDENTITY.equals(relation) && member.alert().firstIdentityPair() != null) {
      pair =
          new IncidentAlerts.Identity(
              member.alert().firstIdentityPair().name(),
              member.alert().firstIdentityPair().value());
    }
    return new IncidentAlerts.Reason(
        relation,
        reopened,
        related == null
            ? null
            : new IncidentAlerts.Related(related.title(), related.sourceKind().key()),
        List.copyOf(path),
        delta == null || delta.isNull() ? null : delta.longValue(),
        pair);
  }

  private static UUID relatedAlertId(JsonNode reason) {
    JsonNode related = reason.get("related_alert_id");
    return related == null || related.isNull() ? null : UUID.fromString(related.stringValue());
  }

  /** DM-R-21: the membership's reason is the decision {@code reopened}. */
  private static boolean isReopened(String reasonJson) {
    JsonNode decision = JSON.readTree(reasonJson).get("decision");
    return decision != null && "reopened".equals(decision.stringValue());
  }

  // AR-I-24

  @GetMapping("/api/v1/incidents/{incident_id}/evidence")
  @RequiresPermission(Permission.VIEW)
  EvidenceLines evidence(TenantId tenant, @PathVariable("incident_id") String incidentId) {
    IncidentView incident = IncidentGuard.workspace(incidents, tenant, incidentId);
    List<EvidenceLines.EvidenceLine> lines = new ArrayList<>();
    for (EvidenceView entry : incidents.evidence(tenant, incident.id())) {
      lines.add(
          new EvidenceLines.EvidenceLine(
              entry.id().toString(),
              entry.kind().key(),
              entry.summary(),
              entry.sourceSystem(),
              ApiTimes.format(entry.collectedAt()),
              outputValues(entry)));
    }
    return new EvidenceLines(List.copyOf(lines));
  }

  /** The stored {@code values} array of a {@code diagnostic_result} entry (CTX-D-077 (4)). */
  private static JsonNode outputValues(EvidenceView entry) {
    if (entry.kind() != EvidenceKind.DIAGNOSTIC_RESULT || entry.contentJson() == null) {
      return null;
    }
    JsonNode values = JSON.readTree(entry.contentJson()).get("values");
    return values != null && values.isArray() ? values : null;
  }

  // AR-I-29

  @GetMapping("/api/v1/incidents/{incident_id}/run")
  @RequiresPermission(Permission.VIEW)
  LatestRun run(TenantId tenant, @PathVariable("incident_id") String incidentId) {
    IncidentView incident = IncidentGuard.workspace(incidents, tenant, incidentId);
    List<RunView> live =
        engine.runs(tenant, incident.id()).stream()
            .filter(run -> run.mode() == RunMode.LIVE)
            .sorted(
                Comparator.comparing(RunView::startedAt).thenComparing(run -> run.id().toString()))
            .toList();
    if (live.isEmpty()) {
      return new LatestRun(null);
    }
    RunView run = live.getLast();
    JsonNode content = JSON.readTree(run.contentJson());
    Map<UUID, Instant> timers = new HashMap<>();
    for (TimerView timer : engine.pendingTimers(tenant, run.id())) {
      timers.putIfAbsent(timer.stepExecutionId(), timer.dueAt());
    }
    List<LatestRun.StepExecution> executions = new ArrayList<>();
    for (StepExecutionView execution : engine.stepExecutions(tenant, run.id())) {
      Instant due = timers.get(execution.id());
      executions.add(
          new LatestRun.StepExecution(
              execution.id().toString(),
              execution.stepId(),
              execution.stepType().key(),
              execution.visit(),
              execution.state().key(),
              ApiTimes.format(execution.startedAt()),
              execution.namedOutput(),
              due == null ? null : ApiTimes.format(due)));
    }
    Names names = names(tenant);
    return new LatestRun(
        new LatestRun.LiveRun(
            run.id().toString(),
            run.state().key(),
            live.size(),
            live.size(),
            run.currentStepExecutionId() == null ? null : run.currentStepExecutionId().toString(),
            content,
            teamKeys(content).stream().map(names::team).toList(),
            List.copyOf(executions)));
  }

  /** Every team key of the steps' {@code eligible_teams} and {@code named_teams}, in key order. */
  private static Set<String> teamKeys(JsonNode content) {
    Set<String> keys = new TreeSet<>();
    JsonNode steps = content.get("steps");
    if (steps == null || !steps.isArray()) {
      return keys;
    }
    for (JsonNode step : steps) {
      JsonNode parameters = step.get("parameters");
      if (parameters == null || !parameters.isObject()) {
        continue;
      }
      for (String parameter : TEAM_PARAMETERS) {
        JsonNode teams = parameters.get(parameter);
        if (teams != null && teams.isArray()) {
          teams.forEach(team -> keys.add(team.stringValue()));
        }
      }
    }
    return keys;
  }

  // AR-I-30

  @GetMapping("/api/v1/incidents/{incident_id}/timeline")
  @RequiresPermission(Permission.VIEW)
  IncidentTimeline timeline(TenantId tenant, @PathVariable("incident_id") String incidentId) {
    IncidentView incident = IncidentGuard.workspace(incidents, tenant, incidentId);
    Names names = names(tenant);
    List<TimelineView> timeline = new ArrayList<>(incidents.timeline(tenant, incident.id()));
    IncidentTimeline.StableGrouping stable = null;
    Instant stableAt = incident.stableAt();
    if (stableAt != null) {
      long alertCount =
          incidents.memberships(tenant, incident.id()).stream()
              .filter(membership -> !membership.joinedAt().isAfter(stableAt))
              .count();
      long merges =
          timeline.stream()
              .filter(entry -> entry.entryType() == TimelineEntryType.MERGED_IN)
              .filter(entry -> !entry.at().isAfter(stableAt))
              .count();
      HypothesisComputationView computation =
          correlation.hypothesisComputations(tenant, incident.id()).stream()
              .filter(c -> !c.computedAt().isAfter(stableAt))
              .reduce((first, second) -> second)
              .orElse(null);
      String hypothesis =
          computation == null || computation.resultComponentKey() == null
              ? null
              : names.component(computation.resultComponentKey());
      stable =
          new IncidentTimeline.StableGrouping(
              ApiTimes.format(stableAt), alertCount, merges, hypothesis);
    }
    Collections.reverse(timeline);
    List<IncidentTimeline.Entry> entries =
        timeline.stream()
            .map(
                entry ->
                    new IncidentTimeline.Entry(
                        entry.id().toString(),
                        ApiTimes.format(entry.at()),
                        names.actor(entry.actor()),
                        entry.entryType().key(),
                        entry.text()))
            .toList();
    return new IncidentTimeline(stable, entries);
  }

  // AR-I-31

  @GetMapping("/api/v1/incidents/{incident_id}/notified-teams")
  @RequiresPermission(Permission.VIEW)
  NotifiedTeams notifiedTeams(TenantId tenant, @PathVariable("incident_id") String incidentId) {
    IncidentView incident = IncidentGuard.workspace(incidents, tenant, incidentId);
    Names names = names(tenant);
    Map<NotifiedTeamView, TeamName> teams = new LinkedHashMap<>();
    for (NotifiedTeamView team : incidents.notifiedTeams(tenant, incident.id())) {
      teams.put(team, names.team(team.teamKey()));
    }
    List<NotifiedTeams.Team> answer =
        teams.entrySet().stream()
            .sorted(
                Comparator.comparing(
                        (Map.Entry<NotifiedTeamView, TeamName> e) -> e.getKey().firstNotifiedAt())
                    .thenComparing(e -> e.getValue(), Names.TEAM_ORDER))
            .map(
                e ->
                    new NotifiedTeams.Team(
                        e.getKey().teamKey(),
                        e.getValue().name(),
                        e.getKey().purpose().key(),
                        ApiTimes.format(e.getKey().firstNotifiedAt())))
            .toList();
    return new NotifiedTeams(answer);
  }
}
