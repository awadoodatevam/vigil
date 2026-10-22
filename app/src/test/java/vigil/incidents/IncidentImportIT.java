package vigil.incidents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

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
import org.springframework.jdbc.core.simple.JdbcClient;
import vigil.incidents.api.IncidentImport;
import vigil.incidents.api.IncidentService;
import vigil.incidents.api.IncidentSettingsPart;
import vigil.incidents.api.IncidentSettingsView;
import vigil.shared.Actor;
import vigil.shared.TenantId;
import vigil.shared.VigilError;
import vigil.tenancy.api.TenantService;
import vigil.testsupport.VigilDatabase;
import vigil.testsupport.VigilTestApp;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IncidentImportIT {

  private VigilTestApp app;
  private JdbcClient postgres;
  private IncidentImport incidentImport;
  private IncidentService incidents;
  private TenantService tenants;
  private TenantId tenant;

  @BeforeAll
  void start() {
    VigilDatabase database = VigilDatabase.fresh();
    app = VigilTestApp.start(database);
    app.awaitStarted();
    postgres = database.postgres();
    incidentImport = app.context().getBean(IncidentImport.class);
    incidents = app.context().getBean(IncidentService.class);
    tenants = app.context().getBean(TenantService.class);
  }

  @AfterAll
  void stop() {
    app.close();
  }

  private TenantId newTenant() {
    return tenants.createTenant("incident import test", Actor.module("vigil-setup"));
  }

  private void importPart(TenantId into, String window, String interval) {
    incidentImport.importPart(
        into, new IncidentSettingsPart(window, interval), Instant.now(), Map.of());
  }

  /** The part is refused with exactly {@code problems}, and nothing is stored. */
  private void refused(TenantId into, String window, String interval, String... problems) {
    long before = settingsRows();
    VigilError error =
        catchThrowableOfType(VigilError.class, () -> importPart(into, window, interval));
    assertThat(error.code()).isEqualTo(VigilError.Code.INVALID);
    assertThat(error.message()).isEqualTo("Part incidents refused.");
    assertThat(error.details()).isEqualTo(Map.of("problems", List.of(problems)));
    assertThat(settingsRows()).isEqualTo(before);
  }

  private long settingsRows() {
    return postgres
        .sql("SELECT count(*) FROM incidents.incident_settings")
        .query(Long.class)
        .single();
  }

  @Test
  @Order(1)
  @DisplayName("DM-E-40 the incident settings of a tenant, imported as seed part 9")
  void imported() {
    tenant = newTenant();
    importPart(tenant, "300", "30");
    assertThat(incidents.settings(tenant))
        .contains(new IncidentSettingsView(Duration.ofSeconds(300), Duration.ofSeconds(30)));
    assertThat(
            postgres
                .sql(
                    "SELECT created_at = updated_at FROM incidents.incident_settings"
                        + " WHERE tenant_id = :t")
                .param("t", tenant.value())
                .query(Boolean.class)
                .single())
        .isTrue();
    assertThat(
            postgres
                .sql("SELECT count(*) FROM audit.audit_event WHERE tenant_id = :t")
                .param("t", tenant.value())
                .query(Long.class)
                .single())
        .isZero();
  }

  @Test
  @Order(2)
  @DisplayName("DM-E40-R1 exactly one settings record per tenant")
  void onePerTenant() {
    refused(tenant, "300", "30", "incident settings already exist");
    assertThat(
            postgres
                .sql("SELECT count(*) FROM incidents.incident_settings WHERE tenant_id = :t")
                .param("t", tenant.value())
                .query(Long.class)
                .single())
        .isEqualTo(1);
  }

  @Test
  @Order(3)
  @DisplayName("DM-R-57 part 9 checked and refused as a whole, with every problem")
  void refusedAsAWhole() {
    refused(
        newTenant(),
        "5m",
        "30",
        "incident_settings: reopen_window_s: not a whole number of seconds");
    refused(
        newTenant(),
        "300",
        "x",
        "incident_settings: closure_check_interval_s: not a whole number of seconds");
    refused(newTenant(), "300", "0", "incident_settings: closure_check_interval_s: 1 to 60");
    refused(newTenant(), "300", "61", "incident_settings: closure_check_interval_s: 1 to 60");
    refused(
        newTenant(),
        "-1",
        "61",
        "incident_settings: reopen_window_s: not a whole number of seconds",
        "incident_settings: closure_check_interval_s: 1 to 60");
  }
}
