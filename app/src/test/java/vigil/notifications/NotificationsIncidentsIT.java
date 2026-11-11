package vigil.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import vigil.incidents.api.EscalationCause;
import vigil.incidents.api.IncidentService;
import vigil.incidents.api.NotifiedPurpose;
import vigil.incidents.api.NotifiedTeamView;
import vigil.incidents.api.TimelineView;
import vigil.notifications.api.NotificationsService;
import vigil.notifications.api.NotificationsStepHandler;
import vigil.notifications.api.SendOutcome;
import vigil.shared.Ids;
import vigil.shared.Key;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotificationsIncidentsIT {

  private NotificationsApp servers;

  @BeforeAll
  void start() {
    servers = NotificationsApp.start();
  }

  @AfterAll
  void stop() {
    servers.close();
  }

  @Test
  @DisplayName("DM-E37-R1 a notified team is recorded once, when a message to it was delivered")
  void notifiedOnce() {
    servers.reset();
    NotificationsFixture f = servers.tenant("Notified");
    UUID incident = f.openIncident();
    IncidentService incidents = servers.app.context().getBean(IncidentService.class);
    servers
        .app
        .context()
        .getBean(NotificationsStepHandler.class)
        .start(
            f.notification(
                incident,
                Ids.newId(),
                "{\"recipients\": [\"named_teams\"], \"named_teams\": [\"noc-shift-lead\"]}"));
    SendOutcome outcome =
        servers
            .app
            .context()
            .getBean(NotificationsService.class)
            .notifyEscalation(
                f.tenant,
                incident,
                Ids.newId(),
                1,
                List.of(new Key("noc-shift-lead")),
                EscalationCause.ACKNOWLEDGEMENT_TIMEOUT,
                Duration.ofSeconds(120));
    assertThat(outcome).isEqualTo(SendOutcome.SENT);
    assertThat(servers.records(f, incident)).hasSize(4);
    List<NotifiedTeamView> notified = incidents.notifiedTeams(f.tenant, incident);
    assertThat(notified).hasSize(1);
    assertThat(notified.getFirst().teamKey()).isEqualTo("noc-shift-lead");
    assertThat(notified.getFirst().purpose()).isEqualTo(NotifiedPurpose.INCIDENT_NOTIFICATION);
    assertThat(
            incidents.timeline(f.tenant, incident).stream()
                .map(TimelineView::text)
                .filter(text -> text.contains("NOC Shift Lead"))
                .count())
        .isEqualTo(1);
  }
}
