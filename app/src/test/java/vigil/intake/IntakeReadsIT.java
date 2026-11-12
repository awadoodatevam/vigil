package vigil.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import vigil.api.ApiFixture;
import vigil.intake.api.AlertView;
import vigil.intake.api.IntakeService;
import vigil.shared.TenantId;
import vigil.testsupport.TestTenants;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IntakeReadsIT {

  private ApiFixture fixture;
  private IntakeService intake;
  private AlertView a;
  private AlertView b;
  private AlertView c;
  private AlertView d;
  private Instant first;
  private Instant second;

  @BeforeAll
  void start() {
    fixture = new ApiFixture();
    intake = fixture.app.context().getBean(IntakeService.class);
    first = fixture.clock.instant();
    a = fixture.correlation().postAlertmanager("nowhere-reads-1", "i-1", "ReadsA");
    second = fixture.clock.advance(Duration.ofSeconds(1));
    b = fixture.correlation().postAlertmanager("nowhere-reads-2", "i-1", "ReadsB");
    fixture.clock.advance(Duration.ofSeconds(1));
    c = fixture.correlation().postAlertmanager("nowhere-reads-3", "i-1", "ReadsC");
    d = fixture.correlation().postAlertmanager("nowhere-reads-4", "i-1", "ReadsD");
    fixture.correlation().awaitDecided();
  }

  @AfterAll
  void stop() {
    fixture.close();
  }

  @Test
  @Order(1)
  @DisplayName("DC-R-08 alertsReceivedSince counts from the instant on; latestAlerts newest first")
  void reads() {
    assertThat(a.firstReceivedAt()).isEqualTo(first);
    assertThat(b.firstReceivedAt()).isEqualTo(second);
    assertThat(c.firstReceivedAt()).isEqualTo(d.firstReceivedAt());
    TenantId tenant = fixture.tenant;
    assertThat(intake.alertsReceivedSince(tenant, first)).isEqualTo(4);
    assertThat(intake.alertsReceivedSince(tenant, second)).isEqualTo(3);
    assertThat(intake.alertsReceivedSince(tenant, second.plusMillis(1))).isEqualTo(2);
    assertThat(intake.alertsReceivedSince(tenant, d.firstReceivedAt().plusMillis(1))).isZero();

    assertThat(intake.latestAlerts(tenant, 50))
        .extracting(AlertView::id)
        .containsExactly(d.id(), c.id(), b.id(), a.id());
    assertThat(intake.latestAlerts(tenant, 2))
        .extracting(AlertView::id)
        .containsExactly(d.id(), c.id());
    assertThat(intake.latestAlerts(tenant, 1).getFirst())
        .isEqualTo(intake.alert(tenant, d.id()).orElseThrow());
    for (int limit : new int[] {0, 51}) {
      assertThatThrownBy(() -> intake.latestAlerts(tenant, limit))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> intake.alertsReceivedSince(tenant, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @Order(2)
  @DisplayName(
      "PM-R-10 the alert reads of vigil-intake answer only the tenant's alerts, unmodifiable")
  void tenantOnly() {
    TenantId other = new TestTenants(fixture.app).tenant("Other").create().id();
    assertThat(intake.alertsReceivedSince(other, first)).isZero();
    assertThat(intake.latestAlerts(other, 50)).isEmpty();
    List<AlertView> latest = intake.latestAlerts(fixture.tenant, 50);
    assertThatThrownBy(() -> latest.add(a)).isInstanceOf(UnsupportedOperationException.class);
  }
}
