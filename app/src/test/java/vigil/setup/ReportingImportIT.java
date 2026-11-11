package vigil.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import vigil.reporting.api.ReportingImport;
import vigil.reporting.api.ReportingPart;
import vigil.setup.api.Installation;
import vigil.setup.api.PartOutcome;
import vigil.setup.api.SeedImportState;
import vigil.setup.api.SeedImportView;
import vigil.setup.api.SeedImports;
import vigil.shared.TenantId;
import vigil.shared.VigilError;
import vigil.testsupport.TestSecrets;
import vigil.testsupport.VigilDatabase;
import vigil.testsupport.VigilTestApp;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReportingImportIT {

  static final String SETTINGS = "07-settings.yaml";
  static final String PREFIX = "Part reporting refused. baseline_values";
  static final String ACK = "{metric_key: time_to_acknowledge, value: 11.0, unit: minutes,";
  static final String RECOVER = "{metric_key: time_to_recover,     value: 72.0, unit: minutes,";
  static final String SUPPRESSED = "{metric_key: alerts_suppressed,   value: 0,    unit: count,";
  static final String MERGED =
      "  - {metric_key: incidents_merged,    value: 0,    unit: count,   label: \"Illustrative"
          + " baseline\"}";

  private VigilDatabase database;
  private VigilTestApp app;
  private SeedImports seedImports;

  @BeforeAll
  void start() {
    database = VigilDatabase.fresh();
    TestSecrets secrets = SeedTests.secrets(database);
    app = VigilTestApp.start(database, secrets.folder(), List.of());
    app.awaitStarted();
    seedImports = app.context().getBean(SeedImports.class);
  }

  @AfterAll
  void stop() {
    app.close();
  }

  private SeedImportView changed(UnaryOperator<String> change) {
    return imported(SeedTests.demoBundleWith(SETTINGS, change));
  }

  private SeedImportView imported(byte[] bundle) {
    TenantId tenant =
        app.context().getBean(Installation.class).createTenant("reporting " + UUID.randomUUID());
    return SeedTests.await(seedImports, seedImports.start(tenant, bundle).id());
  }

  private void assertRefused(SeedImportView view, String error) {
    assertThat(view.state()).isEqualTo(SeedImportState.FAILED);
    assertThat(view.parts().get(16).outcome()).isEqualTo(PartOutcome.FAILED);
    assertThat(view.parts().get(16).error()).isEqualTo(error);
    assertThat(rows(view.tenantId())).isZero();
  }

  private long rows(TenantId tenant) {
    return database
        .postgres()
        .sql("SELECT count(*) FROM reporting.baseline_value WHERE tenant_id = :t")
        .param("t", tenant.value())
        .query(Long.class)
        .single();
  }

  @Test
  @DisplayName("DM-E-80 the baseline values of part 17 are refused with each problem")
  void refusals() {
    List<String[]> cases =
        List.of(
            new String[] {
              "metric_key: time_to_acknowledge,",
              "metric_key: time_to_page,",
              "[0].metric_key: one of time_to_acknowledge, time_to_recover, alerts_suppressed,"
                  + " incidents_merged; baseline_values: one value per metric, missing"
                  + " time_to_acknowledge"
            },
            new String[] {
              "metric_key: time_to_recover,    ",
              "metric_key: time_to_acknowledge,",
              "[1].metric_key: used more than once; baseline_values: one value per metric,"
                  + " missing time_to_recover"
            },
            new String[] {
              ACK,
              "{metric_key: time_to_acknowledge, value: -1, unit: minutes,",
              "[0].value: a number from 0 with at most one decimal place"
            },
            new String[] {
              ACK,
              "{metric_key: time_to_acknowledge, value: 11.25, unit: minutes,",
              "[0].value: a number from 0 with at most one decimal place"
            },
            new String[] {
              SUPPRESSED,
              "{metric_key: alerts_suppressed,   value: 0.5,  unit: count,",
              "[2].value: a whole number for a count"
            },
            new String[] {
              RECOVER,
              "{metric_key: time_to_recover,     value: 72.0, unit: count,",
              "[1].unit: minutes for time_to_recover"
            },
            new String[] {
              SUPPRESSED,
              "{metric_key: alerts_suppressed,   value: 0,    unit: minutes,",
              "[2].unit: count for alerts_suppressed"
            },
            new String[] {
              MERGED,
              "  - {metric_key: incidents_merged, value: 0, unit: count, label: \"Baseline\"}",
              "[3].label: Illustrative baseline"
            },
            new String[] {"\n" + MERGED, "", ": one value per metric, missing incidents_merged"});
    for (String[] c : cases) {
      assertRefused(changed(SeedTests.replacing(c[0], c[1])), PREFIX + c[2]);
    }

    SeedImportView ok = imported(SeedTests.demoBundle());
    assertThat(ok.state()).isEqualTo(SeedImportState.SUCCEEDED);
    assertThat(ok.parts().get(16).counts()).isEqualTo(Map.of("baseline_values", 4));
    ReportingImport importer = app.context().getBean(ReportingImport.class);
    assertThatThrownBy(
            () ->
                importer.importPart(
                    ok.tenantId(), new ReportingPart(List.of()), Instant.now(), Map.of()))
        .isInstanceOfSatisfying(
            VigilError.class,
            error -> {
              assertThat(error.code()).isEqualTo(VigilError.Code.INVALID);
              assertThat(error.message()).isEqualTo("Part reporting refused.");
              assertThat(error.details().get("problems"))
                  .isEqualTo(List.of("baseline values already exist"));
            });
    assertThat(rows(ok.tenantId())).isEqualTo(4);
  }
}
