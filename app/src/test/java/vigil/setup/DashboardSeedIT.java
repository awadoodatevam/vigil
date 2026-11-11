package vigil.setup;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import vigil.knowledge.api.KnowledgeService;
import vigil.knowledge.api.PastIncidentView;
import vigil.reporting.api.DashboardView;
import vigil.reporting.api.MetricKey;
import vigil.reporting.api.MetricView;
import vigil.reporting.api.ReportingService;
import vigil.setup.api.Installation;
import vigil.setup.api.SeedImportState;
import vigil.setup.api.SeedImportView;
import vigil.setup.api.SeedImports;
import vigil.shared.TenantId;
import vigil.testsupport.TestSecrets;
import vigil.testsupport.VigilDatabase;
import vigil.testsupport.VigilTestApp;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DashboardSeedIT {

  private static final String LABEL = "Illustrative baseline";

  private VigilDatabase database;
  private VigilTestApp app;
  private TenantId tenant;
  private DashboardView dashboard;

  @BeforeAll
  void start() {
    database = VigilDatabase.fresh();
    TestSecrets secrets = SeedTests.secrets(database);
    app = VigilTestApp.start(database, secrets.folder(), List.of());
    app.awaitStarted();
    SeedImports seedImports = app.context().getBean(SeedImports.class);
    tenant = app.context().getBean(Installation.class).createTenant("dashboard");
    SeedImportView view =
        SeedTests.await(seedImports, seedImports.start(tenant, SeedTests.demoBundle()).id());
    assertThat(view.state()).isEqualTo(SeedImportState.SUCCEEDED);
    dashboard = app.context().getBean(ReportingService.class).dashboard(tenant);
  }

  @AfterAll
  void stop() {
    app.close();
  }

  private MetricView metric(MetricKey key) {
    return dashboard.metrics().stream().filter(m -> m.key() == key).findFirst().orElseThrow();
  }

  @Test
  @DisplayName("DD-K-11 the seeded starting values: 5.7, 41.1, 525 and 46")
  void startingValues() {
    assertThat(metric(MetricKey.TIME_TO_ACKNOWLEDGE).value()).isEqualTo(new BigDecimal("5.7"));
    assertThat(metric(MetricKey.TIME_TO_RECOVER).value()).isEqualTo(new BigDecimal("41.1"));
    assertThat(metric(MetricKey.ALERTS_SUPPRESSED).value()).isEqualTo(new BigDecimal("525"));
    assertThat(metric(MetricKey.INCIDENTS_MERGED).value()).isEqualTo(new BigDecimal("46"));
    assertThat(dashboard.liveIncidents()).isZero();
    assertThat(dashboard.pastIncidents()).isEqualTo(50);
    assertThat(dashboard.recordLabels()).containsExactly("Fictional record");
  }

  @Test
  @DisplayName("DM-R-52 the tenant's 50 past incidents of vigil-knowledge are the history counted")
  void pastIncidents() {
    List<PastIncidentView> past =
        app.context().getBean(KnowledgeService.class).pastIncidents(tenant);
    assertThat(past).hasSize(50).allMatch(PastIncidentView::active);
    assertThat(past.stream().mapToLong(PastIncidentView::alertCount).sum()).isEqualTo(575);
    assertThat(dashboard.pastIncidents()).isEqualTo(past.size());
    assertThat(metric(MetricKey.ALERTS_SUPPRESSED).value())
        .isEqualTo(BigDecimal.valueOf(575 - past.size()));
  }

  @Test
  @DisplayName("PM-M21-02 each metric with its baseline value and label")
  void baselines() {
    assertThat(dashboard.metrics())
        .extracting(MetricView::baselineValue)
        .containsExactly(
            new BigDecimal("11.0"),
            new BigDecimal("72.0"),
            new BigDecimal("0"),
            new BigDecimal("0"));
    assertThat(dashboard.metrics()).extracting(MetricView::baselineLabel).containsOnly(LABEL);
  }

  @Test
  @DisplayName("DD-F-07 baseline_values of 07-settings.yaml is imported as part 17")
  void rows() {
    List<Map<String, Object>> rows =
        database
            .postgres()
            .sql(
                "SELECT metric_key, value::text AS value, unit, label FROM reporting.baseline_value"
                    + " WHERE tenant_id = :t ORDER BY metric_key")
            .param("t", tenant.value())
            .query()
            .listOfRows();
    assertThat(rows)
        .containsExactly(
            Map.of(
                "metric_key", "alerts_suppressed", "value", "0.0", "unit", "count", "label", LABEL),
            Map.of(
                "metric_key", "incidents_merged", "value", "0.0", "unit", "count", "label", LABEL),
            Map.of(
                "metric_key",
                "time_to_acknowledge",
                "value",
                "11.0",
                "unit",
                "minutes",
                "label",
                LABEL),
            Map.of(
                "metric_key",
                "time_to_recover",
                "value",
                "72.0",
                "unit",
                "minutes",
                "label",
                LABEL));
  }
}
