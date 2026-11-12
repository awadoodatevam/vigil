package vigil.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import vigil.incidents.api.AcknowledgementView;
import vigil.incidents.api.IncidentService;
import vigil.incidents.api.IncidentState;
import vigil.incidents.api.IncidentView;
import vigil.incidents.api.TransitionView;
import vigil.intake.api.IntakeService;
import vigil.knowledge.api.KnowledgeService;
import vigil.knowledge.api.PastIncidentView;
import vigil.notifications.NotificationsApp;
import vigil.notifications.NotificationsFixture;
import vigil.reporting.api.DashboardView;
import vigil.reporting.api.MetricKey;
import vigil.reporting.api.MetricView;
import vigil.reporting.api.ReportingService;
import vigil.testsupport.MutableClock;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DashboardIT {

  static final MutableClock CLOCK = new MutableClock(Instant.parse("2026-11-12T09:00:00Z"));

  /** The test clock. */
  static class TestBeans {

    @Bean
    @Primary
    Clock testClock() {
      return CLOCK;
    }
  }

  private NotificationsApp servers;

  @BeforeAll
  void start() {
    servers = NotificationsApp.start(TestBeans.class);
  }

  @AfterAll
  void stop() {
    servers.close();
  }

  /** One counted incident: detection, first acknowledgement, first resolution, alert count. */
  private record Counted(Instant detected, Instant acknowledged, Instant resolved, long alerts) {}

  @Test
  @DisplayName("SL-A1-09 the four metrics computed from live and seeded past incidents")
  void metrics() {
    CLOCK.set(Instant.now());
    NotificationsFixture f = servers.tenant("Dashboard");
    IncidentService incidents = servers.app.context().getBean(IncidentService.class);
    UUID a = f.openIncident();
    CLOCK.advance(Duration.ofMinutes(3));
    f.acknowledge(a);
    CLOCK.advance(Duration.ofMinutes(10));
    f.resolve(a);
    UUID b =
        f.ticketing.investigation.openIncident(
            List.<String[]>of(NotificationsFixture.ALERTS.getFirst()), "srv-db-01");
    UUID merged = f.ticketing.investigation.mergedIncident();
    assertThat(incidents.incident(f.tenant, merged).orElseThrow().state())
        .isEqualTo(IncidentState.MERGED);

    List<Counted> counted = new ArrayList<>();
    for (UUID id : List.of(a, b)) {
      IncidentView view = incidents.incident(f.tenant, id).orElseThrow();
      assertThat(view.number()).isNotNull();
      Instant ack =
          incidents.acknowledgements(f.tenant, id).stream()
              .filter(x -> x.episode() == 1)
              .map(AcknowledgementView::at)
              .findFirst()
              .orElse(null);
      Instant resolved =
          incidents.transitions(f.tenant, id).stream()
              .filter(t -> t.toState() == IncidentState.RESOLVED)
              .map(TransitionView::at)
              .findFirst()
              .orElse(null);
      counted.add(new Counted(view.firstAlertFiredAt(), ack, resolved, view.alertCount()));
    }
    assertThat(counted.get(0).acknowledged()).isNotNull();
    assertThat(counted.get(0).resolved()).isNotNull();
    assertThat(counted.get(0).alerts()).isEqualTo(2);
    assertThat(counted.get(1).acknowledged()).isNull();
    List<PastIncidentView> past =
        servers.app.context().getBean(KnowledgeService.class).pastIncidents(f.tenant);
    assertThat(past).hasSize(50);
    long received = servers.app.context().getBean(IntakeService.class).alertCount(f.tenant);
    assertThat(received).isZero();
    for (PastIncidentView incident : past) {
      received += incident.alertCount();
      if (incident.active()) {
        counted.add(
            new Counted(
                incident.openedAt(),
                incident.acknowledgedAt(),
                incident.recoveredAt(),
                incident.alertCount()));
      }
    }

    DashboardView dashboard =
        servers.app.context().getBean(ReportingService.class).dashboard(f.tenant);
    assertThat(metric(dashboard, MetricKey.TIME_TO_ACKNOWLEDGE).value())
        .isEqualTo(mean(counted, true));
    assertThat(metric(dashboard, MetricKey.TIME_TO_RECOVER).value())
        .isEqualTo(mean(counted, false));
    assertThat(metric(dashboard, MetricKey.ALERTS_SUPPRESSED).value())
        .isEqualTo(BigDecimal.valueOf(received - counted.size()));
    assertThat(metric(dashboard, MetricKey.INCIDENTS_MERGED).value())
        .isEqualTo(BigDecimal.valueOf(counted.stream().filter(c -> c.alerts() >= 2).count()));
    assertThat(dashboard.liveIncidents()).isEqualTo(2);
    assertThat(dashboard.pastIncidents()).isEqualTo(50);
    assertThat(dashboard.recordLabels()).containsExactly("Fictional record");
  }

  private static BigDecimal mean(List<Counted> counted, boolean acknowledged) {
    long millis = 0;
    long n = 0;
    for (Counted c : counted) {
      Instant at = acknowledged ? c.acknowledged() : c.resolved();
      if (at != null) {
        millis += Duration.between(c.detected(), at).toMillis();
        n++;
      }
    }
    return BigDecimal.valueOf(millis)
        .divide(BigDecimal.valueOf(n * 60_000), 1, RoundingMode.HALF_UP);
  }

  private static MetricView metric(DashboardView dashboard, MetricKey key) {
    return dashboard.metrics().stream().filter(m -> m.key() == key).findFirst().orElseThrow();
  }
}
