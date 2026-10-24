package vigil.correlation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vigil.audit.api.AuditEntry;
import vigil.audit.api.AuditEventType;
import vigil.audit.api.AuditService;
import vigil.correlation.GroupingRules.Reason;
import vigil.correlation.GroupingRules.Relations;
import vigil.correlation.api.CorrelationSettingsView;
import vigil.correlation.api.Decision;
import vigil.correlation.api.GroupingDecisionView;
import vigil.correlation.api.HypothesisComputationView;
import vigil.incidents.api.AffectedServiceCopy;
import vigil.incidents.api.AlertCopy;
import vigil.incidents.api.FinalReason;
import vigil.incidents.api.GroupingReason;
import vigil.incidents.api.HypothesisCopy;
import vigil.incidents.api.IdentityPairCopy;
import vigil.incidents.api.IncidentService;
import vigil.incidents.api.IncidentSettingsView;
import vigil.incidents.api.IncidentState;
import vigil.incidents.api.IncidentView;
import vigil.incidents.api.MembershipView;
import vigil.intake.api.AlertTypeView;
import vigil.intake.api.AlertView;
import vigil.intake.api.IdentityPair;
import vigil.intake.api.IntakeService;
import vigil.routing.api.RequestedBy;
import vigil.routing.api.RoutingService;
import vigil.servicemap.api.AffectedService;
import vigil.servicemap.api.ComponentView;
import vigil.servicemap.api.GenerationView;
import vigil.servicemap.api.IdentityKey;
import vigil.servicemap.api.Match;
import vigil.servicemap.api.ServiceMapService;
import vigil.servicemap.api.ServiceView;
import vigil.servicemap.api.SourceKind;
import vigil.shared.Actor;
import vigil.shared.Ids;
import vigil.shared.TenantId;
import vigil.shared.Timestamps;

/**
 * The decision of an alert (P-16 spec Section 6.6 steps 1 to 12) and the stability transition of a
 * forming incident (DM-R-15, DM-R-58), each in one transaction in which the requests to the other
 * modules take part (AR-R-11). Called only by the tenant's worker (AR-R-18).
 */
@Component
class CorrelationDecisions {

  static final Actor ACTOR = Actor.module("vigil-correlation");

  private static final Logger LOG = LoggerFactory.getLogger(CorrelationDecisions.class);
  static final int BATCH = 100;

  private final CorrelationRecords records;
  private final IntakeService intake;
  private final ServiceMapService serviceMap;
  private final IncidentService incidents;
  private final RoutingService routing;
  private final AuditService audit;
  private final TransactionTemplate tx;
  private final Clock clock;

  CorrelationDecisions(
      CorrelationRecords records,
      IntakeService intake,
      ServiceMapService serviceMap,
      IncidentService incidents,
      RoutingService routing,
      AuditService audit,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.records = records;
    this.intake = intake;
    this.serviceMap = serviceMap;
    this.incidents = incidents;
    this.routing = routing;
    this.audit = audit;
    this.tx = new TransactionTemplate(transactionManager);
    this.clock = clock;
  }

  /** The next alerts to decide: at most 100 after the stored cursor. */
  List<AlertView> nextAlerts(TenantId tenantId) {
    return intake.alertsAfter(tenantId, records.cursor(tenantId), BATCH);
  }

  /** The tenant's correlation settings; the worker runs only for a tenant that has them. */
  CorrelationSettingsView settings(TenantId tenantId) {
    return records
        .settings(tenantId)
        .orElseThrow(() -> new IllegalStateException("the tenant has no correlation settings"));
  }

  /** Decides the alert in one transaction; any exception rolls it back. */
  void decide(TenantId tenantId, AlertView alert) {
    tx.executeWithoutResult(status -> decideIn(tenantId, alert));
  }

  /** A candidate incident (DM-R-10) with the reason of the alert's relation to its members. */
  private record Candidate(IncidentView incident, Reason reason) {}

  private void decideIn(TenantId tenantId, AlertView x) {
    if (records.hasDecision(tenantId, x.id())) {
      records.setCursor(tenantId, x.sequence());
      return;
    }
    Instant now = Timestamps.now(clock);
    CorrelationSettingsView settings = settings(tenantId);
    Optional<IncidentSettingsView> incidentSettings = incidents.settings(tenantId);
    SourceKind kind = SourceKind.fromKey(x.sourceKind().key()).orElseThrow();
    Match match =
        serviceMap.match(
            tenantId,
            kind,
            x.identityPairs().stream()
                .map(pair -> new IdentityKey(pair.name(), pair.value()))
                .toList());
    AlertCopy alert = alertCopy(tenantId, x, match.componentKey());
    Relations relations = GroupingRules.remembered((a, b) -> serviceMap.relation(tenantId, a, b));

    List<Candidate> candidates = new ArrayList<>();
    for (IncidentView incident :
        incidents.incidents(tenantId, Set.of(IncidentState.FORMING, IncidentState.OPEN))) {
      GroupingRules.relatedAlert(
              alert, copies(tenantId, incident.id()), relations, settings.groupingWindow())
          .ifPresent(reason -> candidates.add(new Candidate(incident, reason)));
    }

    UUID decisionId = Ids.newId();
    Decision decision;
    UUID incidentId;
    Reason reason = null;
    List<UUID> merged = List.of();
    Optional<Candidate> open =
        candidates.stream()
            .filter(candidate -> candidate.incident().state() == IncidentState.OPEN)
            .findFirst();
    if (candidates.isEmpty()) {
      Optional<Candidate> reopened =
          reopenCandidate(tenantId, x, alert, incidentSettings, relations);
      if (reopened.isPresent()) {
        decision = Decision.REOPENED;
        incidentId = reopened.get().incident().id();
        reason = reopened.get().reason();
        incidents.reopen(tenantId, ACTOR, incidentId, decisionId, alert, reason.grouping(decision));
      } else {
        decision = Decision.CREATED_INCIDENT;
        incidentId =
            incidents.create(
                tenantId,
                ACTOR,
                decisionId,
                alert,
                new GroupingReason(decision.key(), null, null, null, null));
      }
    } else if (open.isPresent()) {
      decision = Decision.JOINED;
      incidentId = open.get().incident().id();
      reason = open.get().reason();
      incidents.addAlert(tenantId, ACTOR, incidentId, decisionId, alert, reason.grouping(decision));
    } else {
      decision = Decision.JOINED;
      Candidate survivor = candidates.getFirst();
      incidentId = survivor.incident().id();
      reason = survivor.reason();
      incidents.addAlert(tenantId, ACTOR, incidentId, decisionId, alert, reason.grouping(decision));
      merged = candidates.stream().skip(1).map(candidate -> candidate.incident().id()).toList();
      if (!merged.isEmpty()) {
        incidents.merge(tenantId, ACTOR, incidentId, merged);
      }
    }
    records.insertDecision(
        tenantId,
        new GroupingDecisionView(
            decisionId,
            x.id(),
            x.sequence(),
            match.componentKey(),
            match.generation(),
            decision,
            incidentId,
            merged,
            reason == null ? null : reason.related().alertId(),
            reason == null ? null : reason.relation(),
            reason == null ? null : reason.path(),
            reason == null ? null : reason.timeDelta(),
            now));

    Changes changes = hypothesisAndImpact(tenantId, incidentId, now);
    if (decision == Decision.CREATED_INCIDENT || changes.any() || !merged.isEmpty()) {
      routing.evaluate(tenantId, ACTOR, incidentId, RequestedBy.CORRELATION);
    }
    audit.append(
        new AuditEntry(
            tenantId,
            now,
            ACTOR,
            AuditEventType.ALERT_GROUPED,
            "GroupingDecision",
            decisionId,
            incidentId,
            CorrelationTexts.alertGrouped(
                alert.title(), alert.sourceKind(), decision, merged.size()),
            null));
    if (changes.hypothesis()) {
      audit.append(
          new AuditEntry(
              tenantId,
              now,
              ACTOR,
              AuditEventType.HYPOTHESIS_CHANGED,
              "HypothesisComputation",
              changes.computationId(),
              incidentId,
              CorrelationTexts.hypothesisChanged(changes.oldName(), changes.newName()),
              null));
    }
    records.setCursor(tenantId, x.sequence());
  }

  /** Step 4: the alert copy for vigil-incidents, with the alert type's display name. */
  private AlertCopy alertCopy(TenantId tenantId, AlertView x, String componentKey) {
    String typeName =
        intake.alertTypes(tenantId).stream()
            .filter(type -> type.alertType().equals(x.alertType()))
            .map(AlertTypeView::displayName)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("the alert type is not defined"));
    IdentityPair first = x.identityPairs().isEmpty() ? null : x.identityPairs().getFirst();
    return new AlertCopy(
        x.id(),
        x.sourceKind().key(),
        x.alertKey(),
        x.alertType(),
        typeName,
        x.title(),
        componentKey,
        first == null ? null : new IdentityPairCopy(first.name(), first.value()),
        x.firedAt());
  }

  /** The alert copies of the incident's active members. */
  private List<AlertCopy> copies(TenantId tenantId, UUID incidentId) {
    return incidents.members(tenantId, incidentId).stream().map(MembershipView::alert).toList();
  }

  /**
   * DM-R-21: the {@code resolved} incident with the lowest {@code sequence} whose reopen window
   * holds the alert's {@code first_received_at} and that has a member whose component is related to
   * the alert's (the time relation does not apply).
   */
  private Optional<Candidate> reopenCandidate(
      TenantId tenantId,
      AlertView x,
      AlertCopy alert,
      Optional<IncidentSettingsView> incidentSettings,
      Relations relations) {
    if (incidentSettings.isEmpty()) {
      return Optional.empty();
    }
    Duration window = incidentSettings.get().reopenWindow();
    Instant received = x.firstReceivedAt();
    for (IncidentView resolved : incidents.incidents(tenantId, Set.of(IncidentState.RESOLVED))) {
      Instant resolvedAt = resolved.resolvedAt();
      if (received.isBefore(resolvedAt) || received.isAfter(resolvedAt.plus(window))) {
        continue;
      }
      Optional<Reason> reason =
          GroupingRules.relatedAlert(alert, copies(tenantId, resolved.id()), relations, null);
      if (reason.isPresent()) {
        return Optional.of(new Candidate(resolved, reason.get()));
      }
    }
    return Optional.empty();
  }

  /**
   * What step 9 changed: the affected components, the hypothesis component (with the new
   * computation and the names before and after) and the affected services.
   */
  private record Changes(
      boolean components,
      boolean hypothesis,
      boolean services,
      UUID computationId,
      String oldName,
      String newName) {

    static final Changes NONE = new Changes(false, false, false, null, null, null);

    boolean any() {
      return components || hypothesis || services;
    }
  }

  /** Step 9: the hypothesis (DM-R-18, DM-E-30) and the customer impact (DM-R-19). */
  private Changes hypothesisAndImpact(TenantId tenantId, UUID incidentId, Instant now) {
    IncidentView incident = incidents.incident(tenantId, incidentId).orElseThrow();
    TreeSet<String> set = new TreeSet<>(GroupingRules.CODE_POINTS);
    for (AlertCopy member : copies(tenantId, incidentId)) {
      if (member.componentKey() != null) {
        set.add(member.componentKey());
      }
    }
    List<String> affected = List.copyOf(set);
    long generation =
        serviceMap.currentGeneration(tenantId).map(GenerationView::generation).orElse(0L);
    Optional<HypothesisComputationView> latest = records.latestComputation(tenantId, incidentId);
    if (latest.isPresent()
        && latest.get().affectedComponentKeys().equals(affected)
        && latest.get().generation() == generation) {
      return Changes.NONE;
    }
    Hypothesis.Result result =
        Hypothesis.compute(
            affected,
            key -> serviceMap.dependents(tenantId, key),
            key ->
                serviceMap.component(tenantId, key).map(ComponentView::criticality).orElse(null));
    UUID computationId = Ids.newId();
    records.insertComputation(
        tenantId,
        new HypothesisComputationView(
            computationId,
            incidentId,
            generation,
            result.affected(),
            result.dependentCounts(),
            result.component(),
            result.tieBreak(),
            incident.hypothesisComponentKey(),
            now));

    List<AffectedServiceCopy> impact = new ArrayList<>();
    for (AffectedService service : serviceMap.affectedServices(tenantId, affected)) {
      ServiceView view = serviceMap.service(tenantId, service.serviceKey()).orElseThrow();
      impact.add(
          new AffectedServiceCopy(
              view.serviceKey(),
              view.name(),
              view.criticality() == null ? null : view.criticality().key(),
              service.viaComponentKeys()));
    }

    HypothesisCopy hypothesis = null;
    UUID hypothesisComputationId = null;
    Optional<ComponentView> component =
        result.component() == null
            ? Optional.empty()
            : serviceMap.component(tenantId, result.component());
    if (component.isPresent()) {
      ComponentView view = component.get();
      hypothesis =
          new HypothesisCopy(
              generation,
              view.componentKey(),
              view.name(),
              view.kind().key(),
              view.criticality() == null ? null : view.criticality().key());
      hypothesisComputationId = computationId;
    } else if (result.component() != null) {
      hypothesis = incident.hypothesis();
      hypothesisComputationId =
          incident.hypothesis() == null ? null : incident.hypothesisComputationId();
    }
    incidents.setHypothesisAndImpact(
        tenantId, ACTOR, incidentId, hypothesis, hypothesisComputationId, impact);

    String oldKey = incident.hypothesisComponentKey();
    String newKey = hypothesis == null ? null : hypothesis.componentKey();
    boolean hypothesisChanged = !Objects.equals(oldKey, newKey);
    boolean componentsChanged =
        latest.map(view -> !view.affectedComponentKeys().equals(affected)).orElse(true);
    boolean servicesChanged =
        !impact.stream()
            .map(AffectedServiceCopy::serviceKey)
            .sorted(GroupingRules.CODE_POINTS)
            .toList()
            .equals(
                incident.affectedServices().stream()
                    .map(AffectedServiceCopy::serviceKey)
                    .sorted(GroupingRules.CODE_POINTS)
                    .toList());
    return new Changes(
        componentsChanged,
        hypothesisChanged,
        servicesChanged,
        computationId,
        incident.hypothesis() == null ? null : incident.hypothesis().name(),
        hypothesis == null ? null : hypothesis.name());
  }

  /**
   * The stability check of the tenant's forming incidents in ascending {@code sequence} (DM-R-15);
   * each stable incident is moved to {@code open} in its own transaction. A failed transaction is
   * logged and tried again at the next check. No further transaction starts once {@code stopped}.
   */
  void checkStability(TenantId tenantId, BooleanSupplier stopped) {
    CorrelationSettingsView settings = settings(tenantId);
    for (IncidentView incident : incidents.incidents(tenantId, Set.of(IncidentState.FORMING))) {
      if (stopped.getAsBoolean()) {
        return;
      }
      try {
        tx.executeWithoutResult(status -> stabilize(tenantId, incident.id(), settings));
      } catch (RuntimeException e) {
        LOG.error(
            "correlation stability check of incident {} for tenant {} failed",
            incident.id(),
            tenantId.value(),
            e);
      }
    }
  }

  private void stabilize(TenantId tenantId, UUID incidentId, CorrelationSettingsView settings) {
    Instant now = Timestamps.now(clock);
    IncidentView incident = incidents.incident(tenantId, incidentId).orElseThrow();
    if (incident.state() != IncidentState.FORMING) {
      return;
    }
    boolean stable =
        !now.isBefore(incident.lastAlertJoinedAt().plus(settings.settlePeriod()))
            || !now.isBefore(incident.createdAt().plus(settings.maxForming()));
    if (!stable) {
      return;
    }
    routing.evaluate(tenantId, ACTOR, incidentId, RequestedBy.CORRELATION);
    Map<UUID, FinalReason> finalReasons =
        GroupingRules.finalReasons(
            copies(tenantId, incidentId),
            GroupingRules.remembered((a, b) -> serviceMap.relation(tenantId, a, b)),
            settings.groupingWindow());
    incidents.open(tenantId, ACTOR, incidentId, finalReasons);
  }
}
