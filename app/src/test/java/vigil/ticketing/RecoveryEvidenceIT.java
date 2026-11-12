package vigil.ticketing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import vigil.connectors.api.TicketLine;
import vigil.incidents.api.EvidenceKind;
import vigil.incidents.api.EvidenceView;
import vigil.incidents.api.IncidentState;
import vigil.intake.IntakeFixture;
import vigil.intake.api.AlertView;
import vigil.intake.api.Health;
import vigil.recovery.RecoveryFixture;
import vigil.shared.Completed;
import vigil.shared.Ids;
import vigil.testsupport.MutableClock;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RecoveryEvidenceIT {

  static final MutableClock CLOCK = new MutableClock(Instant.parse("2026-11-12T12:00:00Z"));
  private static final DateTimeFormatter OUTBOUND =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);

  /** The test clock. */
  static class TestBeans {

    @Bean
    @Primary
    Clock testClock() {
      return CLOCK;
    }
  }

  private RecoveryFixture.App servers;

  @BeforeAll
  void start() {
    servers = RecoveryFixture.App.start(CLOCK, TestBeans.class);
  }

  @AfterAll
  void stop() {
    servers.close();
  }

  @Test
  @DisplayName("DM-SM14-T2 the recovery evidence gives exactly the lines of the resolve comment")
  void resolveComment() {
    RecoveryFixture f = servers.tenant().withDemoDefinitions();
    IntakeFixture.await(
        () -> f.intake.sources(f.tenant).stream().allMatch(s -> s.health() == Health.HEALTHY));
    AlertView zabbix =
        f.zabbix("srv-db-01", "lab-db-01", "Disk full: /data free space below 1 GiB");
    AlertView probe =
        f.alertmanager("ussd-probe", "lab-probe-01:9100", "SyntheticUssdSessionFailed");
    UUID incident = f.open(zabbix);
    assertThat(f.incidents.incidentOfAlert(f.tenant, probe.id())).contains(incident);
    UUID run = Ids.newId();
    UUID step = Ids.newId();
    f.handler.start(f.call(run, incident, step, 300, 1, "rc-subscriber-db"));
    CLOCK.advance(Duration.ofSeconds(5));
    f.resolve(probe);
    f.handler.evaluate(f.call(run, incident, step, 300, 2, "rc-subscriber-db"));
    CLOCK.advance(Duration.ofSeconds(10));
    Instant t1 = CLOCK.instant();
    f.resolve(zabbix);
    f.handler.evaluate(f.call(run, incident, step, 300, 3, "rc-subscriber-db"));
    CLOCK.set(t1.plusSeconds(60));
    assertThat(f.handler.evaluate(f.call(run, incident, step, 300, 4, "rc-subscriber-db")))
        .isInstanceOf(Completed.class);
    assertThat(f.incidents.incident(f.tenant, incident).orElseThrow().state())
        .isEqualTo(IncidentState.RESOLVED);

    EvidenceView entry =
        f.incidents.evidence(f.tenant, incident).stream()
            .filter(e -> e.kind() == EvidenceKind.RECOVERY_VERIFICATION)
            .findFirst()
            .orElseThrow();
    List<String> lines =
        TicketTexts.resolveLines(entry).orElseThrow().stream().map(TicketLine::text).toList();
    assertThat(lines)
        .containsExactly(
            "Resolved: recovery verified.",
            "Recovered: 'Subscriber DB services recovered' held for 60 s with every monitoring"
                + " source healthy.",
            "Conditions:",
            "- Every alert of the incident is resolved at its source: held",
            "- No alert of Synthetic USSD sessions is firing: held",
            "- Every monitoring source is healthy: held",
            "Observation window: 60 s, from "
                + OUTBOUND.format(t1)
                + " to "
                + OUTBOUND.format(t1.plusSeconds(60))
                + ".",
            "Source: Monitoring: Alertmanager — Jazz lab, Zabbix — Jazz lab");
  }
}
