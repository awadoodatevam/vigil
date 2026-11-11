package vigil.reporting.api;

import java.util.List;

/**
 * The dashboard (PM-M21): the four metrics in {@link MetricKey} order, the counted live and past
 * incidents and the distinct record labels of the counted past incidents, sorted (DC-S10-03).
 */
public record DashboardView(
    List<MetricView> metrics, long liveIncidents, long pastIncidents, List<String> recordLabels) {

  public DashboardView {
    metrics = List.copyOf(metrics);
    recordLabels = List.copyOf(recordLabels);
  }
}
