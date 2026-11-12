package vigil.api;

import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import vigil.identity.api.Permission;
import vigil.incidents.api.FormingSummary;
import vigil.incidents.api.IncidentService;
import vigil.intake.api.AlertTypeView;
import vigil.intake.api.Health;
import vigil.intake.api.IntakeService;
import vigil.intake.api.SourceView;
import vigil.shared.TenantId;
import vigil.shared.Timestamps;

/** AR-I-20: the incoming alerts strip that the incident list polls (DC-S02-02, AR-I-05). */
@RestController
@Profile("!migrate")
class AlertStripController {

  private static final Duration RECENT = Duration.ofMinutes(10);
  private static final int LATEST = 10;

  private final IntakeService intake;
  private final IncidentService incidents;
  private final Clock clock;

  AlertStripController(IntakeService intake, IncidentService incidents, Clock clock) {
    this.intake = intake;
    this.incidents = incidents;
    this.clock = clock;
  }

  @GetMapping("/api/v1/alerts/strip")
  @RequiresPermission(Permission.VIEW)
  AlertStrip strip(TenantId tenant) {
    long recent = intake.alertsReceivedSince(tenant, Timestamps.now(clock).minus(RECENT));
    FormingSummary forming = incidents.formingSummary(tenant);
    List<SourceView> active = intake.sources(tenant).stream().filter(SourceView::active).toList();
    List<AlertStrip.NotHealthy> notHealthy =
        active.stream()
            .filter(source -> source.health() != Health.HEALTHY)
            .sorted(Comparator.comparing(SourceView::name).thenComparing(SourceView::sourceKey))
            .map(
                source ->
                    new AlertStrip.NotHealthy(
                        source.sourceKey(), source.name(), source.health().key()))
            .toList();
    Map<String, String> typeNames = new HashMap<>();
    for (AlertTypeView type : intake.alertTypes(tenant)) {
      if (type.displayName() != null) {
        typeNames.put(type.alertType(), type.displayName());
      }
    }
    List<AlertStrip.LatestAlert> latest =
        intake.latestAlerts(tenant, LATEST).stream()
            .map(
                alert ->
                    new AlertStrip.LatestAlert(
                        alert.id().toString(),
                        ApiTimes.format(alert.firstReceivedAt()),
                        alert.sourceKind().key(),
                        typeNames.getOrDefault(alert.alertType(), alert.alertType()),
                        alert.title()))
            .toList();
    return new AlertStrip(
        recent,
        new AlertStrip.Forming(forming.incidents(), forming.alerts()),
        new AlertStrip.Sources(active.size() - notHealthy.size(), active.size(), notHealthy),
        latest);
  }
}
