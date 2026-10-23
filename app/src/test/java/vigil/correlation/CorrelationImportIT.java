package vigil.correlation;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import vigil.correlation.api.CorrelationImport;
import vigil.correlation.api.CorrelationService;
import vigil.correlation.api.CorrelationSettingsPart;
import vigil.correlation.api.CorrelationSettingsView;
import vigil.intake.IntakeFixture;
import vigil.shared.Actor;
import vigil.shared.TenantId;
import vigil.shared.VigilError;
import vigil.tenancy.api.TenantService;
import vigil.testsupport.MutableClock;
import vigil.testsupport.TestSecrets;
import vigil.testsupport.VigilDatabase;
import vigil.testsupport.VigilTestApp;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CorrelationImportIT {

  private static final MutableClock CLOCK = new MutableClock(Instant.parse("2026-10-22T10:00:00Z"));

  private WireMockServer wiremock;
  private VigilDatabase database;
  private VigilTestApp app;
  private CorrelationImport correlationImport;
  private CorrelationService correlation;
  private TenantService tenants;
  private TenantId tenant;

  /** The test clock. */
  static class TestBeans {

    @Bean
    @Primary
    Clock testClock() {
      return CLOCK;
    }
  }

  @BeforeAll
  void start() {
    wiremock = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort());
    wiremock.start();
    IntakeFixture.stub(wiremock);
    database = VigilDatabase.fresh();
    TestSecrets secrets = IntakeFixture.secrets(database);
    app = VigilTestApp.start(database, secrets.folder(), List.of(), TestBeans.class);
    app.awaitStarted();
    correlationImport = app.context().getBean(CorrelationImport.class);
    correlation = app.context().getBean(CorrelationService.class);
    tenants = app.context().getBean(TenantService.class);
  }

  @AfterAll
  void stop() {
    app.close();
    wiremock.stop();
  }

  private TenantId newTenant() {
    return tenants.createTenant("Tenant", Actor.module("vigil-setup"));
  }

  private void importPart(TenantId into, CorrelationSettingsPart part) {
    correlationImport.importPart(into, part, Instant.now(), Map.of());
  }

  /** The part is refused with exactly {@code problems}, and nothing is stored. */
  private void refused(TenantId into, CorrelationSettingsPart part, String... problems) {
    VigilError error = catchThrowableOfType(VigilError.class, () -> importPart(into, part));
    assertThat(error).isNotNull();
    assertThat(error.code()).isEqualTo(VigilError.Code.INVALID);
    assertThat(error.message()).isEqualTo("Part correlation refused.");
    assertThat(error.details()).isEqualTo(Map.of("problems", List.of(problems)));
    long rows =
        database
            .postgres()
            .sql("SELECT count(*) FROM correlation.correlation_settings WHERE tenant_id = :t")
            .param("t", into.value())
            .query(Long.class)
            .single();
    assertThat(rows).isZero();
  }

  @Test
  @Order(1)
  @DisplayName("DM-E-31 the correlation settings are imported from part 10")
  void imported() {
    tenant = newTenant();
    importPart(tenant, new CorrelationSettingsPart("300", "40", "300", "5"));
    assertThat(correlation.settings(tenant))
        .contains(
            new CorrelationSettingsView(
                Duration.ofSeconds(300),
                Duration.ofSeconds(40),
                Duration.ofSeconds(300),
                Duration.ofSeconds(5)));
  }

  @Test
  @Order(2)
  @DisplayName("DM-E31-R1 a second import is refused: exactly one settings record per tenant")
  void exactlyOne() {
    VigilError error =
        catchThrowableOfType(
            VigilError.class,
            () -> importPart(tenant, new CorrelationSettingsPart("60", "10", "100", "1")));
    assertThat(error.code()).isEqualTo(VigilError.Code.INVALID);
    assertThat(error.details())
        .isEqualTo(Map.of("problems", List.of("correlation settings already exist")));
    assertThat(correlation.settings(tenant).orElseThrow().groupingWindow())
        .isEqualTo(Duration.ofSeconds(300));
  }

  @Test
  @Order(3)
  @DisplayName("DM-R-57 part 10 is checked and refused as a whole")
  void refusals() {
    refused(
        newTenant(),
        new CorrelationSettingsPart("5m", "40", "300", "5"),
        "correlation_settings: grouping_window_s: not a whole number of seconds");
    refused(
        newTenant(),
        new CorrelationSettingsPart("300", null, "300", "5"),
        "correlation_settings: settle_period_s: not a whole number of seconds");
    refused(
        newTenant(),
        new CorrelationSettingsPart("0", "40", "300", "5"),
        "correlation_settings: grouping_window_s: at least 1");
    refused(
        newTenant(),
        new CorrelationSettingsPart("300", "0", "300", "5"),
        "correlation_settings: settle_period_s: at least 1");
    refused(
        newTenant(),
        new CorrelationSettingsPart("300", "40", "40", "5"),
        "correlation_settings: max_forming_s: must be greater than settle_period_s");
    refused(
        newTenant(),
        new CorrelationSettingsPart("300", "40", "300", "0"),
        "correlation_settings: stability_check_interval_s: 1 to 5");
    refused(
        newTenant(),
        new CorrelationSettingsPart("300", "40", "300", "6"),
        "correlation_settings: stability_check_interval_s: 1 to 5");
    refused(
        newTenant(),
        new CorrelationSettingsPart("0", "40", "300", "6"),
        "correlation_settings: grouping_window_s: at least 1",
        "correlation_settings: stability_check_interval_s: 1 to 5");
  }
}
