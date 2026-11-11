package vigil.recovery;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import vigil.correlation.api.CorrelationService;
import vigil.incidents.api.IncidentService;
import vigil.incidents.api.MembershipView;
import vigil.intake.api.AlertStatus;
import vigil.intake.api.AlertTypeView;
import vigil.intake.api.AlertView;
import vigil.intake.api.Health;
import vigil.intake.api.IntakeService;
import vigil.intake.api.SourceView;
import vigil.recovery.api.ConditionKind;
import vigil.recovery.api.RecoveryCondition;
import vigil.servicemap.api.ComponentView;
import vigil.servicemap.api.ServiceMapService;
import vigil.shared.TenantId;

/**
 * One evaluation of a recovery verification (P-23 spec Section 6.7 steps 3 to 5): the conditions
 * from alert states held by vigil-intake (DM-R-35), the source health guard (DM-R-36) and the
 * observation window (DM-R-37 with DM-E23-R1). It reads only; it joins the caller's transaction.
 */
@Component
class Evaluation {

  private final IncidentService incidents;
  private final IntakeService intake;
  private final CorrelationService correlation;
  private final ServiceMapService serviceMap;

  /** One condition's result: whether it holds, its text and its number of firing alerts. */
  record ConditionResult(
      RecoveryCondition condition, boolean held, String detail, long firingCount) {}

  /** The evaluation at {@code at}; {@code unhealthy} the active sources not healthy now. */
  record Result(
      Instant at,
      List<ConditionResult> conditions,
      List<SourceView> activeSources,
      List<SourceView> unhealthy) {

    Result {
      conditions = List.copyOf(conditions);
      activeSources = List.copyOf(activeSources);
      unhealthy = List.copyOf(unhealthy);
    }

    boolean sourcesHealthy() {
      return unhealthy.isEmpty();
    }

    /** Every condition holds and every active source is healthy now. */
    boolean all() {
      return sourcesHealthy() && conditions.stream().allMatch(ConditionResult::held);
    }

    /** The texts not holding: each condition's, then the guard's (CTX-D-073 (4)). */
    List<String> textsNotHolding() {
      List<String> texts = new ArrayList<>();
      conditions.stream().filter(c -> !c.held()).forEach(c -> texts.add(c.detail()));
      if (!sourcesHealthy()) {
        texts.add(RecoveryTexts.guard(unhealthy.stream().map(SourceView::name).toList()));
      }
      return texts;
    }

    /** The names of the active sources. */
    List<String> activeSourceNames() {
      return activeSources.stream().map(SourceView::name).toList();
    }

    /** The last evaluation (P-23 spec Section 6.5). */
    ObjectNode json() {
      ObjectNode node = RecoveryRecords.JSON.createObjectNode();
      node.put("at", at.toString());
      ArrayNode array = node.putArray("conditions");
      for (ConditionResult result : conditions) {
        ObjectNode entry = array.addObject();
        entry.set("condition", RecoveryRecords.conditionJson(result.condition()));
        entry.put("held", result.held());
        entry.put("detail", result.detail());
        entry.put("firing_count", result.firingCount());
      }
      node.put("sources_healthy", sourcesHealthy());
      ArrayNode ids = node.putArray("unhealthy_source_ids");
      unhealthy.forEach(source -> ids.add(source.id().toString()));
      return node;
    }
  }

  Evaluation(
      IncidentService incidents,
      IntakeService intake,
      CorrelationService correlation,
      ServiceMapService serviceMap) {
    this.incidents = incidents;
    this.intake = intake;
    this.correlation = correlation;
    this.serviceMap = serviceMap;
  }

  /** Steps 3 and 4: the conditions and the guard now. */
  Result evaluate(
      TenantId tenantId, UUID incidentId, List<RecoveryCondition> conditions, Instant now) {
    Map<String, String> typeNames = alertTypeNames(tenantId);
    List<ConditionResult> results = new ArrayList<>();
    for (RecoveryCondition condition : conditions) {
      if (condition.kind() == ConditionKind.INCIDENT_ALERTS_RESOLVED) {
        List<UUID> ids =
            incidents.members(tenantId, incidentId).stream().map(MembershipView::alertId).toList();
        long firing = firing(intake.alerts(tenantId, ids));
        results.add(
            new ConditionResult(
                condition, firing == 0, RecoveryTexts.incidentAlerts(firing == 0, firing), firing));
      } else {
        List<AlertView> alerts =
            intake.alerts(
                tenantId, correlation.alertsOfComponent(tenantId, condition.componentKey()));
        if (!condition.alertTypes().isEmpty()) {
          alerts =
              alerts.stream().filter(a -> condition.alertTypes().contains(a.alertType())).toList();
        }
        long firing = firing(alerts);
        results.add(
            new ConditionResult(
                condition,
                firing == 0,
                RecoveryTexts.componentAlerts(
                    firing == 0,
                    firing,
                    componentName(tenantId, condition.componentKey()),
                    names(typeNames, condition.alertTypes())),
                firing));
      }
    }
    List<SourceView> active = intake.sources(tenantId).stream().filter(SourceView::active).toList();
    List<SourceView> unhealthy = active.stream().filter(s -> s.health() != Health.HEALTHY).toList();
    return new Result(now, results, active, unhealthy);
  }

  /**
   * Step 5's window: cleared when not everything holds; started now when everything holds and there
   * is none, or when an active source was not healthy during the whole of it (CTX-D-073 (2)).
   */
  Instant window(TenantId tenantId, Result result, Instant windowStartedAt) {
    if (!result.all()) {
      return null;
    }
    if (windowStartedAt == null) {
      return result.at();
    }
    for (SourceView source : result.activeSources()) {
      if (!intake.healthyThroughout(tenantId, source.id(), windowStartedAt, result.at())) {
        return result.at();
      }
    }
    return windowStartedAt;
  }

  /** The requirement text of a condition: its holding form (PM-M15-04). */
  String requirementText(TenantId tenantId, RecoveryCondition condition) {
    if (condition.kind() == ConditionKind.INCIDENT_ALERTS_RESOLVED) {
      return RecoveryTexts.incidentAlerts(true, 0);
    }
    return RecoveryTexts.componentAlerts(
        true,
        0,
        componentName(tenantId, condition.componentKey()),
        names(alertTypeNames(tenantId), condition.alertTypes()));
  }

  /** The component's name in the current generation, or its key. */
  private String componentName(TenantId tenantId, String componentKey) {
    return serviceMap
        .component(tenantId, componentKey)
        .map(ComponentView::name)
        .orElse(componentKey);
  }

  private Map<String, String> alertTypeNames(TenantId tenantId) {
    return intake.alertTypes(tenantId).stream()
        .collect(Collectors.toMap(AlertTypeView::alertType, AlertTypeView::displayName));
  }

  private static List<String> names(Map<String, String> typeNames, List<String> alertTypes) {
    return alertTypes.stream().map(type -> typeNames.getOrDefault(type, type)).toList();
  }

  private static long firing(List<AlertView> alerts) {
    return alerts.stream().filter(alert -> alert.status() == AlertStatus.FIRING).count();
  }
}
