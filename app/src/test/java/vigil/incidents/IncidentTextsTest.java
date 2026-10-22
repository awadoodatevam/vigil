package vigil.incidents;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vigil.incidents.api.AffectedServiceCopy;
import vigil.incidents.api.AiStatus;
import vigil.incidents.api.AiUnavailableReason;
import vigil.incidents.api.AlertCopy;
import vigil.incidents.api.EscalationCause;
import vigil.incidents.api.IdentityPairCopy;
import vigil.incidents.api.IncidentState;
import vigil.incidents.api.NotifiedPurpose;
import vigil.incidents.api.Severity;

class IncidentTextsTest {

  private static final AlertCopy DISK_FULL =
      new AlertCopy(
          UUID.randomUUID(),
          "zabbix",
          "36",
          "disk_full",
          "Disk full",
          "Disk full: /data free space below 1 GiB",
          "srv-db-01",
          new IdentityPairCopy("host", "srv-db-01"),
          Instant.parse("2026-10-22T10:00:00Z"));
  private static final AlertCopy USSD =
      new AlertCopy(
          UUID.randomUUID(),
          "alertmanager",
          "f1",
          "ussd_timeouts",
          "USSD timeouts",
          "USSD gateway timeouts",
          "app-ussd",
          null,
          Instant.parse("2026-10-22T10:00:00Z"));

  @Test
  @DisplayName("DC-TX-01 incident numbers, state names and durations")
  void formats() {
    assertThat(IncidentTexts.number(1)).isEqualTo("INC-0001");
    assertThat(IncidentTexts.number(42)).isEqualTo("INC-0042");
    assertThat(IncidentTexts.number(12345)).isEqualTo("INC-12345");
    assertThat(List.of(IncidentState.values()).stream().map(IncidentTexts::state))
        .containsExactly("Forming", "Open", "Resolved", "Closed", "Merged");
    assertThat(IncidentTexts.duration(Duration.ofSeconds(45))).isEqualTo("45 s");
    assertThat(IncidentTexts.duration(Duration.ofSeconds(119))).isEqualTo("119 s");
    assertThat(IncidentTexts.duration(Duration.ofSeconds(120))).isEqualTo("2 min");
    assertThat(IncidentTexts.duration(Duration.ofSeconds(150))).isEqualTo("2 min 30 s");
  }

  @Test
  @DisplayName("DC-TX-04 every timeline text, exact")
  void timelineTexts() {
    assertThat(IncidentTexts.created(DISK_FULL))
        .isEqualTo(
            "Incident created from alert 'Disk full: /data free space below 1 GiB' (Zabbix).");
    assertThat(IncidentTexts.alertJoined(USSD))
        .isEqualTo("Alert 'USSD gateway timeouts' (Alertmanager) joined.");
    assertThat(IncidentTexts.mergedIn(1)).isEqualTo("1 alert merged in from another group.");
    assertThat(IncidentTexts.mergedIn(3)).isEqualTo("3 alerts merged in from another group.");
    assertThat(IncidentTexts.hypothesisChanged(null, "Subscriber DB server"))
        .isEqualTo("Hypothesis: Subscriber DB server.");
    assertThat(IncidentTexts.hypothesisChanged("Subscriber database", "Subscriber DB server"))
        .isEqualTo("Hypothesis changed from Subscriber database to Subscriber DB server.");
    assertThat(
            IncidentTexts.impactChanged(
                List.of(
                    new AffectedServiceCopy("svc-a", "Zone billing", null, List.of("c")),
                    new AffectedServiceCopy("svc-z", "Account top-up", "high", List.of("c")))))
        .isEqualTo("Customer impact: Account top-up, Zone billing.");
    assertThat(IncidentTexts.impactChanged(List.of()))
        .isEqualTo("Customer impact: none identified.");
    assertThat(
            IncidentTexts.assignmentChanged(
                Severity.P1,
                "Database Operations",
                List.of("NOC Level 1", "IT Applications"),
                "Subscriber database P1"))
        .isEqualTo(
            "Assigned P1, owner Database Operations, informed IT Applications, NOC Level 1"
                + " (rule 'Subscriber database P1').");
    assertThat(IncidentTexts.opened(2)).isEqualTo("Opened as INC-0002: the alert group is stable.");
    assertThat(IncidentTexts.RESOLVED).isEqualTo("Resolved: recovery verified.");
    assertThat(IncidentTexts.reopened(DISK_FULL))
        .isEqualTo("Reopened by alert 'Disk full: /data free space below 1 GiB' (Zabbix).");
    assertThat(IncidentTexts.CLOSED).isEqualTo("Closed: the reopen window has passed.");
    assertThat(IncidentTexts.MERGED).isEqualTo("Merged into another group.");
    assertThat(IncidentTexts.acknowledged("DB Ops Engineer"))
        .isEqualTo("Acknowledged by DB Ops Engineer.");
    assertThat(
            IncidentTexts.escalated(
                1,
                List.of("NOC Level 1"),
                EscalationCause.ACKNOWLEDGEMENT_TIMEOUT,
                Duration.ofSeconds(120)))
        .isEqualTo("Escalated to level 1: NOC Level 1 (not acknowledged within 2 min).");
    assertThat(
            IncidentTexts.escalated(
                2,
                List.of("NOC Level 2", "Database Operations"),
                EscalationCause.ESCALATE_NOW,
                null))
        .isEqualTo(
            "Escalated to level 2: Database Operations, NOC Level 2 (workflow escalation step).");
    assertThat(IncidentTexts.evidenceAdded("Disk usage of /data: 100%"))
        .isEqualTo("Evidence: Disk usage of /data: 100%");
    assertThat(IncidentTexts.aiStatusChanged(AiStatus.AVAILABLE, null))
        .isEqualTo("AI investigation available.");
    assertThat(
            IncidentTexts.aiStatusChanged(
                AiStatus.UNAVAILABLE, AiUnavailableReason.NO_PROVIDER_REACHABLE))
        .isEqualTo("AI investigation unavailable: no allowed AI provider was reachable.");
    assertThat(
            IncidentTexts.aiStatusChanged(
                AiStatus.UNAVAILABLE, AiUnavailableReason.NO_PROVIDER_ALLOWED))
        .isEqualTo("AI investigation unavailable: data routing allows no provider for this data.");
    assertThat(
            IncidentTexts.aiStatusChanged(
                AiStatus.UNAVAILABLE, AiUnavailableReason.OUTPUT_FAILED_CHECKS))
        .isEqualTo(
            "AI investigation unavailable: the AI output failed the checks and was not used.");
    assertThat(
            IncidentTexts.notifiedTeamAdded(
                "Database Operations", NotifiedPurpose.INCIDENT_NOTIFICATION))
        .isEqualTo("Database Operations notified (incident notification).");
    assertThat(IncidentTexts.notifiedTeamAdded("NOC Level 2", NotifiedPurpose.ESCALATION))
        .isEqualTo("NOC Level 2 notified (escalation).");
    assertThat(IncidentTexts.notifiedTeamAdded("DB Approvers", NotifiedPurpose.APPROVAL_REQUEST))
        .isEqualTo("DB Approvers notified (approval request).");
    assertThat(IncidentTexts.titleChanged("Subscriber DB server: Disk full"))
        .isEqualTo("Title: Subscriber DB server: Disk full");
  }

  @Test
  @DisplayName("DC-TX-10 the summaries of the five audit event types, exact")
  void auditSummaries() {
    assertThat(IncidentTexts.created(USSD))
        .isEqualTo("Incident created from alert 'USSD gateway timeouts' (Alertmanager).");
    assertThat(IncidentTexts.auditStateChanged(IncidentState.FORMING, IncidentState.OPEN))
        .isEqualTo("Forming → Open.");
    assertThat(
            IncidentTexts.auditAssignmentChanged(
                Severity.P2, "Database Operations", List.of(), "Default"))
        .isEqualTo("Assigned P2, owner Database Operations, informed none by rule 'Default'.");
    assertThat(
            IncidentTexts.auditAssignmentChanged(
                Severity.P1,
                "Database Operations",
                List.of("NOC Level 1"),
                "Subscriber database P1"))
        .isEqualTo(
            "Assigned P1, owner Database Operations, informed NOC Level 1 by rule"
                + " 'Subscriber database P1'.");
    assertThat(IncidentTexts.acknowledged("NOC Operator"))
        .isEqualTo("Acknowledged by NOC Operator.");
    assertThat(
            IncidentTexts.escalated(
                1,
                List.of("NOC Level 1"),
                EscalationCause.ACKNOWLEDGEMENT_TIMEOUT,
                Duration.ofSeconds(90)))
        .isEqualTo("Escalated to level 1: NOC Level 1 (not acknowledged within 90 s).");
  }
}
