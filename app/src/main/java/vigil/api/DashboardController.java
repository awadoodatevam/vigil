package vigil.api;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import vigil.identity.api.Permission;
import vigil.reporting.api.DashboardView;
import vigil.reporting.api.ReportingService;
import vigil.shared.TenantId;

/** AR-I-80 the dashboard (DC-S10), computed on each call (DC-S10-04). */
@RestController
@Profile("!migrate")
class DashboardController {

  private final ReportingService reporting;

  DashboardController(ReportingService reporting) {
    this.reporting = reporting;
  }

  @GetMapping("/api/v1/dashboard")
  @RequiresPermission(Permission.VIEW)
  Dashboard dashboard(TenantId tenant) {
    DashboardView view = reporting.dashboard(tenant);
    return new Dashboard(
        view.metrics().stream()
            .map(
                metric ->
                    new Dashboard.Metric(
                        metric.key().key(),
                        metric.unit().key(),
                        metric.value(),
                        metric.baselineValue(),
                        metric.baselineLabel()))
            .toList(),
        view.liveIncidents(),
        view.pastIncidents(),
        view.recordLabels());
  }
}
