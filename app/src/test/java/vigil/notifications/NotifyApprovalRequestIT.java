package vigil.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import vigil.incidents.api.IncidentService;
import vigil.incidents.api.NotifiedPurpose;
import vigil.incidents.api.NotifiedTeamView;
import vigil.notifications.api.ApprovalRecipient;
import vigil.notifications.api.Channel;
import vigil.notifications.api.DeliveryStatus;
import vigil.notifications.api.NotificationRecordView;
import vigil.notifications.api.NotificationsService;
import vigil.notifications.api.RecipientKind;
import vigil.notifications.api.SendOutcome;
import vigil.shared.Ids;
import vigil.shared.Key;
import vigil.ticketing.TicketingFixture;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotifyApprovalRequestIT {

  static final Instant EXPIRES = Instant.parse("2026-11-11T09:15:00.250Z");
  static final List<ApprovalRecipient> DB_OPS =
      List.of(
          new ApprovalRecipient(new Key("dbops-engineer"), new Key("db-ops")),
          new ApprovalRecipient(new Key("dbops-lead"), new Key("db-ops")));

  private NotificationsApp servers;
  private NotificationsService notifications;
  private IncidentService incidents;

  @BeforeAll
  void start() {
    servers = NotificationsApp.start();
    notifications = servers.app.context().getBean(NotificationsService.class);
    incidents = servers.app.context().getBean(IncidentService.class);
  }

  @AfterAll
  void stop() {
    servers.close();
  }

  @BeforeEach
  void reset() {
    servers.reset();
  }

  private SendOutcome request(NotificationsFixture f, UUID incident, UUID request) {
    return notifications.notifyApprovalRequest(
        f.tenant,
        incident,
        request,
        DB_OPS,
        "Purge archived transaction logs",
        "Subscriber DB server",
        EXPIRES);
  }

  @Test
  @DisplayName("AR-I-102 the approval request to the approvers with the Approval link")
  void approvalRequest() {
    NotificationsFixture f = servers.tenant("Approve");
    UUID incident = f.openIncident();
    UUID request = Ids.newId();
    assertThat(request(f, incident, request)).isEqualTo(SendOutcome.SENT);
    String link = "https://" + f.host + "/m/approve/" + request;
    List<NotificationRecordView> records = notifications.records(f.tenant, incident);
    assertThat(records).hasSize(4);
    assertThat(records)
        .allMatch(r -> r.recipientKind() == RecipientKind.USER)
        .allMatch(r -> "db-ops".equals(r.viaTeamKey()))
        .allMatch(r -> r.purpose() == NotifiedPurpose.APPROVAL_REQUEST)
        .allMatch(r -> r.originModule().equals("vigil-actions"))
        .allMatch(r -> r.originEntity().equals("ApprovalRequest"))
        .allMatch(r -> r.originId().equals(request))
        .allMatch(r -> r.status() == DeliveryStatus.SENT)
        .allMatch(r -> r.link().equals(link));
    String[] lines = records.getFirst().content().split("\n");
    assertThat(lines).hasSize(5);
    assertThat(lines[0])
        .isEqualTo(
            TicketingFixture.LABEL
                + " - Approval needed: Purge archived transaction logs on Subscriber DB server"
                + " for "
                + lines[1].split(" ")[1]);
    assertThat(lines[1]).startsWith("[P1] INC-");
    assertThat(lines[2]).isEqualTo("Expires: 2026-11-11 09:15:00 UTC");
    assertThat(lines[3]).startsWith("Summary: ");
    assertThat(lines[4]).isEqualTo("Approve or reject: " + link);
    assertThat(NotificationsApp.teamsTexts(servers.teamsPosts().getFirst()).getLast())
        .isEqualTo("Approve or reject: [" + link + "](" + link + ")");
    assertThat(incidents.notifiedTeams(f.tenant, incident))
        .extracting(NotifiedTeamView::teamKey, NotifiedTeamView::purpose)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("db-ops", NotifiedPurpose.APPROVAL_REQUEST));
    assertThat(request(f, incident, request)).isEqualTo(SendOutcome.SENT);
    assertThat(notifications.records(f.tenant, incident)).hasSize(4);
    assertThatThrownBy(
            () ->
                notifications.notifyApprovalRequest(
                    f.tenant, incident, Ids.newId(), List.of(), "a", "t", EXPIRES))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("recipients: at least one");
    assertThatThrownBy(
            () ->
                notifications.notifyApprovalRequest(
                    f.tenant, incident, Ids.newId(), DB_OPS, null, "t", EXPIRES))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("a required argument is missing");
    assertThatThrownBy(
            () ->
                notifications.notifyApprovalRequest(
                    f.tenant, Ids.newId(), Ids.newId(), DB_OPS, "a", "t", EXPIRES))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("DM-E77-R1 approval requests go to the users' own addresses, Teams and email")
  void usersAddresses() {
    NotificationsFixture f = servers.tenant("Approve");
    UUID incident = f.openIncident();
    request(f, incident, Ids.newId());
    assertThat(servers.teamsKeys()).containsExactly("dbops-engineer", "dbops-lead");
    assertThat(servers.emailRecipients())
        .containsExactly("dbops-engineer@vigil.test", "dbops-lead@vigil.test");
    List<NotificationRecordView> records = notifications.records(f.tenant, incident);
    assertThat(records)
        .extracting(r -> r.recipientKey() + " " + r.channel().key() + " " + r.address())
        .containsExactly(
            "dbops-engineer teams Techfinity Demo / Approvals — Usman",
            "dbops-engineer email dbops-engineer@vigil.test",
            "dbops-lead teams Techfinity Demo / Approvals — Farah",
            "dbops-lead email dbops-lead@vigil.test");
    assertThat(records).noneMatch(r -> r.recipientKey().equals("db-ops"));
    assertThat(records.stream().filter(r -> r.channel() == Channel.TEAMS)).hasSize(2);
  }
}
