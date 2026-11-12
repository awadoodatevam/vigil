package vigil.incidents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import vigil.api.ApiFixture;
import vigil.incidents.api.FormingSummary;
import vigil.incidents.api.IncidentService;
import vigil.incidents.api.IncidentState;
import vigil.incidents.api.IncidentView;
import vigil.shared.TenantId;
import vigil.testsupport.TestTenants;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IncidentReadsIT {

  private static final Set<IncidentState> LISTED =
      EnumSet.of(IncidentState.OPEN, IncidentState.RESOLVED, IncidentState.CLOSED);

  private ApiFixture fixture;
  private IncidentService incidents;
  private TenantId tenant;
  private final List<Long> numbers = new java.util.ArrayList<>();
  private UUID resolved;

  @BeforeAll
  void start() {
    fixture = new ApiFixture();
    incidents = fixture.app.context().getBean(IncidentService.class);
    tenant = fixture.tenant;
  }

  @AfterAll
  void stop() {
    fixture.close();
  }

  private long number(UUID incident) {
    return incidents.incident(tenant, incident).orElseThrow().number();
  }

  private List<Long> page(Set<IncidentState> states, Long before, int limit) {
    return incidents.incidentPage(tenant, states, before, limit).stream()
        .map(IncidentView::number)
        .toList();
  }

  @Test
  @Order(1)
  @DisplayName("PM-R-10 incidentPage by number descending; formingSummary of the forming incidents")
  void reads() {
    assertThat(incidents.formingSummary(tenant)).isEqualTo(new FormingSummary(0, 0));
    for (int i = 0; i < 3; i++) {
      numbers.add(number(fixture.openIncident()));
    }
    resolved = incidents.incidentPage(tenant, LISTED, null, 51).getLast().id();
    fixture.engine.correlation.resolve(resolved);
    assertThat(incidents.formingSummary(tenant)).isEqualTo(new FormingSummary(0, 0));
    fixture.formingIncident();
    fixture.engine.correlation.postAlertmanager("nowhere-reads-two", "i-1", "First");
    fixture.engine.correlation.postAlertmanager("nowhere-reads-two", "i-2", "Second");
    fixture.engine.correlation.awaitDecided();
    fixture.engine.awaitPublications();
    assertThat(incidents.incidents(tenant, EnumSet.of(IncidentState.FORMING))).hasSize(2);
    assertThat(incidents.formingSummary(tenant)).isEqualTo(new FormingSummary(2, 3));

    long n1 = numbers.get(0);
    long n2 = numbers.get(1);
    long n3 = numbers.get(2);
    assertThat(page(LISTED, null, 51)).containsExactly(n3, n2, n1);
    assertThat(page(EnumSet.allOf(IncidentState.class), null, 51)).containsExactly(n3, n2, n1);
    assertThat(page(EnumSet.of(IncidentState.FORMING), null, 51)).isEmpty();
    assertThat(page(LISTED, n3, 51)).containsExactly(n2, n1);
    assertThat(page(LISTED, n2, 51)).containsExactly(n1);
    assertThat(page(LISTED, n1, 51)).isEmpty();
    assertThat(page(LISTED, null, 2)).containsExactly(n3, n2);
    assertThat(page(EnumSet.of(IncidentState.RESOLVED), null, 51)).containsExactly(n1);
    assertThat(page(EnumSet.of(IncidentState.OPEN), null, 51)).containsExactly(n3, n2);
    assertThat(incidents.incidentPage(tenant, LISTED, null, 1).getFirst())
        .isEqualTo(incidents.incidentPage(tenant, LISTED, null, 51).getFirst());
    for (int limit : new int[] {0, 52}) {
      assertThatThrownBy(() -> incidents.incidentPage(tenant, LISTED, null, limit))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> incidents.incidentPage(tenant, Set.of(), null, 10))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @Order(2)
  @DisplayName("DC-R-08 the incident reads of vigil-incidents answer only the tenant's incidents")
  void tenantOnly() {
    TenantId other = new TestTenants(fixture.app).tenant("Other").create().id();
    assertThat(incidents.incidentPage(other, LISTED, null, 51)).isEmpty();
    assertThat(incidents.formingSummary(other)).isEqualTo(new FormingSummary(0, 0));
    List<IncidentView> page = incidents.incidentPage(tenant, LISTED, null, 51);
    assertThat(page).hasSize(3);
    assertThatThrownBy(() -> page.remove(0)).isInstanceOf(UnsupportedOperationException.class);
  }
}
