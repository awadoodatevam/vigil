package vigil.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vigil.connectors.api.MessageLine;
import vigil.incidents.api.AffectedServiceCopy;
import vigil.incidents.api.AiStatus;
import vigil.incidents.api.EscalationCause;
import vigil.incidents.api.IncidentState;
import vigil.incidents.api.IncidentView;
import vigil.incidents.api.Severity;

class MessageTextsTest {

  static final String LABEL = "Demo environment — fictional data";
  static final UUID ID = UUID.fromString("0190f0b2-0000-7000-8000-000000000007");
  static final String ACK = "https://demo.vigil.test/m/acknowledge/" + ID;
  static final String APPROVE =
      "https://demo.vigil.test/m/approve/0190f0b2-0000-7000-8000-0000000000aa";
  static final String TITLE = "Subscriber DB server: Disk full — 3 services affected";
  static final String SUMMARY = "The /data filesystem of the subscriber database server is full.";
  static final List<String> EVIDENCE =
      List.of("/data is 100% full (15.9 of 15.9 GiB used, 0.1 GiB free)", "Likely cause found");

  static IncidentView incident(List<AffectedServiceCopy> services) {
    Instant at = Instant.parse("2026-11-11T09:00:00Z");
    return new IncidentView(
        ID,
        7,
        7L,
        TITLE,
        IncidentState.OPEN,
        at,
        at,
        at,
        at,
        3,
        List.of("disk_full"),
        List.of("zabbix"),
        "srv-db-01",
        null,
        null,
        services,
        Severity.P1,
        "db-ops",
        List.of("customer-care", "vas-service-owners"),
        null,
        false,
        0,
        null,
        null,
        AiStatus.NOT_RUN,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        0,
        at,
        at);
  }

  static final List<AffectedServiceCopy> SERVICES =
      List.of(
          new AffectedServiceCopy("ussd", "USSD self-service", "critical", List.of("srv-db-01")),
          new AffectedServiceCopy("balance", "Prepaid balance", "high", List.of("srv-db-01")),
          new AffectedServiceCopy("bundles", "Data bundles", "high", List.of("srv-db-01")));

  static MessageTexts.IncidentText text(String label) {
    return new MessageTexts.IncidentText(
        label,
        incident(SERVICES),
        "Database Operations",
        List.of("VAS Service Owners", "Customer Care"),
        SUMMARY);
  }

  static List<String> texts(List<MessageLine> lines) {
    return lines.stream().map(MessageLine::text).toList();
  }

  @Test
  @DisplayName("DC-OUT-05 the incident notification, the escalation and the approval request lines")
  void incidentNotification() {
    assertThat(texts(MessageTexts.incidentNotification(text(LABEL), ACK)))
        .containsExactly(
            LABEL + " - [P1] INC-0007 " + TITLE,
            "Owner: Database Operations. Informed: Customer Care, VAS Service Owners.",
            "Customer impact: Data bundles, Prepaid balance, USSD self-service.",
            "Summary: " + SUMMARY,
            "Acknowledge: " + ACK);
    MessageTexts.IncidentText bare =
        new MessageTexts.IncidentText(null, incident(List.of()), null, List.of(), SUMMARY);
    assertThat(texts(MessageTexts.incidentNotification(bare, ACK)))
        .containsExactly(
            "[P1] INC-0007 " + TITLE,
            "Owner: none. Informed: none.",
            "Customer impact: none identified.",
            "Summary: " + SUMMARY,
            "Acknowledge: " + ACK);
    assertThat(
            texts(
                MessageTexts.escalation(
                    text(LABEL),
                    1,
                    List.of("NOC Shift Lead"),
                    EscalationCause.ACKNOWLEDGEMENT_TIMEOUT,
                    Duration.ofSeconds(120),
                    EVIDENCE,
                    ACK)))
        .containsExactly(
            LABEL + " - Escalation level 1: [P1] INC-0007 " + TITLE,
            "Escalated to NOC Shift Lead (not acknowledged within 2 min).",
            "Owner: Database Operations. Informed: Customer Care, VAS Service Owners.",
            "Summary: " + SUMMARY,
            "Evidence:",
            "- " + EVIDENCE.get(0),
            "- " + EVIDENCE.get(1),
            "Acknowledge: " + ACK);
    assertThat(
            texts(
                MessageTexts.escalation(
                    text(null),
                    2,
                    List.of("NOC Management"),
                    EscalationCause.ESCALATE_NOW,
                    null,
                    List.of(),
                    ACK)))
        .containsExactly(
            "Escalation level 2: [P1] INC-0007 " + TITLE,
            "Escalated to NOC Management (workflow escalation step).",
            "Owner: Database Operations. Informed: Customer Care, VAS Service Owners.",
            "Summary: " + SUMMARY,
            "Evidence:",
            "Acknowledge: " + ACK);
    assertThat(
            texts(
                MessageTexts.approvalRequest(
                    text(LABEL),
                    "Purge archived transaction logs",
                    "Subscriber DB server",
                    Instant.parse("2026-11-11T09:15:00.789Z"),
                    APPROVE)))
        .containsExactly(
            LABEL
                + " - Approval needed: Purge archived transaction logs on Subscriber DB server"
                + " for INC-0007",
            "[P1] INC-0007 " + TITLE,
            "Expires: 2026-11-11 09:15:00 UTC",
            "Summary: " + SUMMARY,
            "Approve or reject: " + APPROVE);
  }

  @Test
  @DisplayName("DC-OUT-06 the escalation message: level, teams, cause, parties, summary, evidence")
  void escalation() {
    List<String> lines =
        texts(
            MessageTexts.escalation(
                text(LABEL),
                1,
                List.of("NOC Shift Lead", "Database Operations"),
                EscalationCause.ACKNOWLEDGEMENT_TIMEOUT,
                Duration.ofSeconds(600),
                EVIDENCE,
                ACK));
    assertThat(lines.get(1))
        .isEqualTo(
            "Escalated to Database Operations, NOC Shift Lead (not acknowledged within 10 min).");
    assertThat(lines).hasSize(8);
    assertThat(MessageTexts.cause(EscalationCause.ACKNOWLEDGEMENT_TIMEOUT, Duration.ofSeconds(90)))
        .isEqualTo("not acknowledged within 90 s");
    assertThat(MessageTexts.cause(EscalationCause.ACKNOWLEDGEMENT_TIMEOUT, Duration.ofSeconds(150)))
        .isEqualTo("not acknowledged within 2 min 30 s");
    assertThat(MessageTexts.cause(EscalationCause.ESCALATE_NOW, null))
        .isEqualTo("workflow escalation step");
  }

  @Test
  @DisplayName("DC-OUT-07 the approval request message with the outbound expiry time")
  void approvalRequest() {
    List<MessageLine> lines =
        MessageTexts.approvalRequest(
            text(LABEL),
            "Purge archived transaction logs",
            "Subscriber DB server",
            Instant.parse("2026-11-11T23:59:59.999Z"),
            APPROVE);
    assertThat(lines).hasSize(5);
    assertThat(lines.get(2).text()).isEqualTo("Expires: 2026-11-11 23:59:59 UTC");
    assertThat(lines.get(4)).isEqualTo(new MessageLine("Approve or reject: " + APPROVE, APPROVE));
  }

  @Test
  @DisplayName(
      "DC-OUT-00 messages are ordered lines with the names and formats of DC-TX-00, DC-TX-01")
  void ordered() {
    assertThat(MessageTexts.number(7)).isEqualTo("INC-0007");
    assertThat(MessageTexts.number(12345)).isEqualTo("INC-12345");
    assertThat(MessageTexts.time(Instant.parse("2026-01-02T03:04:05.678Z")))
        .isEqualTo("2026-01-02 03:04:05 UTC");
    assertThat(MessageTexts.list(List.of("b", "B", "a"), "none")).isEqualTo("B, a, b");
    assertThat(MessageTexts.list(List.of(), "none")).isEqualTo("none");
    List<MessageLine> lines = MessageTexts.incidentNotification(text(LABEL), ACK);
    assertThat(MessageTexts.content(lines)).isEqualTo(String.join("\n", texts(lines)));
    assertThat(MessageTexts.content(List.of(MessageLine.of("x".repeat(5000))))).hasSize(4000);
  }

  @Test
  @DisplayName("DM-E02-R2 the environment label prefixes the first line of every message")
  void prefix() {
    assertThat(MessageTexts.prefix(LABEL)).isEqualTo(LABEL + " - ");
    assertThat(MessageTexts.prefix(null)).isEmpty();
    assertThat(MessageTexts.prefix("")).isEmpty();
    assertThat(texts(MessageTexts.incidentNotification(text(""), ACK)).getFirst())
        .isEqualTo("[P1] INC-0007 " + TITLE);
    assertThat(
            MessageTexts.approvalRequest(text(LABEL), "a", "t", Instant.EPOCH, APPROVE)
                .getFirst()
                .text())
        .startsWith(LABEL + " - Approval needed: ");
  }

  @Test
  @DisplayName(
      "PM-M20-03 prefix, summary and link in all three kinds; evidence only in escalations")
  void contents() {
    List<List<MessageLine>> all =
        List.of(
            MessageTexts.incidentNotification(text(LABEL), ACK),
            MessageTexts.escalation(
                text(LABEL),
                1,
                List.of("NOC Shift Lead"),
                EscalationCause.ESCALATE_NOW,
                null,
                EVIDENCE,
                ACK),
            MessageTexts.approvalRequest(text(LABEL), "a", "t", Instant.EPOCH, APPROVE));
    for (List<MessageLine> lines : all) {
      assertThat(lines.getFirst().text()).startsWith(LABEL + " - ");
      assertThat(texts(lines)).contains("Summary: " + SUMMARY);
      assertThat(lines.getLast().link()).isNotNull();
      assertThat(lines.getLast().text()).endsWith(lines.getLast().link());
    }
    assertThat(texts(all.get(1))).contains("Evidence:", "- " + EVIDENCE.get(0));
    assertThat(texts(all.get(0)))
        .noneMatch(line -> line.startsWith("Evidence") || line.startsWith("- "));
    assertThat(texts(all.get(2)))
        .noneMatch(line -> line.startsWith("Evidence") || line.startsWith("- "));
    assertThat(MessageTexts.incidentNotification(text(LABEL), null).getLast())
        .isEqualTo(MessageLine.of("Acknowledge: "));
  }
}
