package vigil.reporting;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vigil.incidents.api.AcknowledgementView;
import vigil.incidents.api.IncidentService;
import vigil.incidents.api.IncidentState;
import vigil.incidents.api.IncidentView;
import vigil.incidents.api.TransitionView;
import vigil.intake.api.IntakeService;
import vigil.knowledge.api.KnowledgeService;
import vigil.knowledge.api.PastIncidentView;
import vigil.reporting.api.DashboardView;
import vigil.reporting.api.MetricKey;
import vigil.reporting.api.ReportingService;
import vigil.shared.TenantId;

/**
 * The dashboard of vigil-reporting (PM-M21-01, PM-M21-02): the four metrics over the tenant's live
 * incidents and its seeded past incidents (DM-R-52, DM-R-53), read in one read-only transaction.
 */
@Service
class Reporting implements ReportingService {

  private final IncidentService incidents;
  private final KnowledgeService knowledge;
  private final IntakeService intake;
  private final BaselineRecords baselines;

  Reporting(
      IncidentService incidents,
      KnowledgeService knowledge,
      IntakeService intake,
      BaselineRecords baselines) {
    this.incidents = incidents;
    this.knowledge = knowledge;
    this.intake = intake;
    this.baselines = baselines;
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED, readOnly = true)
  public DashboardView dashboard(TenantId tenantId) {
    if (tenantId == null) {
      throw new IllegalArgumentException("a required argument is missing");
    }
    List<Metrics.Counted> counted = new ArrayList<>();
    long live = 0;
    for (IncidentView incident :
        incidents.incidents(
            tenantId,
            EnumSet.of(IncidentState.OPEN, IncidentState.RESOLVED, IncidentState.CLOSED))) {
      if (incident.number() == null) {
        continue;
      }
      live++;
      Instant acknowledged =
          incidents.acknowledgements(tenantId, incident.id()).stream()
              .filter(ack -> ack.episode() == 1)
              .map(AcknowledgementView::at)
              .findFirst()
              .orElse(null);
      Instant resolved =
          incidents.transitions(tenantId, incident.id()).stream()
              .filter(transition -> transition.toState() == IncidentState.RESOLVED)
              .map(TransitionView::at)
              .min(Comparator.naturalOrder())
              .orElse(null);
      counted.add(
          new Metrics.Counted(
              incident.firstAlertFiredAt(), acknowledged, resolved, incident.alertCount()));
    }
    List<PastIncidentView> past = knowledge.pastIncidents(tenantId);
    long alertsReceived = intake.alertCount(tenantId);
    long pastCounted = 0;
    TreeSet<String> labels =
        new TreeSet<>(Comparator.comparing(text -> text.codePoints().toArray(), Arrays::compare));
    for (PastIncidentView incident : past) {
      alertsReceived += incident.alertCount();
      if (!incident.active()) {
        continue;
      }
      pastCounted++;
      labels.add(incident.recordLabel());
      counted.add(
          new Metrics.Counted(
              incident.openedAt(),
              incident.acknowledgedAt(),
              incident.recoveredAt(),
              incident.alertCount()));
    }
    Map<MetricKey, BaselineRecords.Baseline> stored = baselines.baselines(tenantId);
    return new DashboardView(
        Metrics.compute(counted, alertsReceived, stored), live, pastCounted, List.copyOf(labels));
  }
}
