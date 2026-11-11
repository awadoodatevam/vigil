package vigil.ticketing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vigil.connectors.api.TicketLine;
import vigil.incidents.api.AffectedServiceCopy;
import vigil.incidents.api.AiStatus;
import vigil.incidents.api.AlertCopy;
import vigil.incidents.api.DataCategory;
import vigil.incidents.api.EscalationCause;
import vigil.incidents.api.EvidenceKind;
import vigil.incidents.api.EvidenceView;
import vigil.incidents.api.HypothesisCopy;
import vigil.incidents.api.IncidentState;
import vigil.incidents.api.IncidentView;
import vigil.incidents.api.MembershipView;
import vigil.investigation.api.IncidentSummaryView;
import vigil.investigation.api.SummarySource;
import vigil.shared.Ids;
import vigil.ticketing.api.SyncOperation;
import vigil.ticketing.api.SyncOutcome;

class TicketTextsTest {

  static final String TITLE = "Subscriber DB server: Disk full — 3 services affected";
  static final String LABEL = "Demo environment — fictional data";
  static final String LINK = "https://demo.vigil.test/incidents/0190a1b2";
  static final HypothesisCopy HYPOTHESIS =
      new HypothesisCopy(1, "srv-db-01", "Subscriber DB server", "server", "critical");
  static final List<AffectedServiceCopy> SERVICES =
      List.of(
          new AffectedServiceCopy("svc-ussd", "USSD self-service", "high", List.of("ussd-gw")),
          new AffectedServiceCopy("svc-balance", "Balance check", "high", List.of("api-balance")),
          new AffectedServiceCopy("svc-bundle", "Bundle purchase", "high", List.of("api-bundle")));

  static IncidentView incident(
      long alertCount,
      List<String> types,
      HypothesisCopy hypothesis,
      List<AffectedServiceCopy> services) {
    Instant now = Instant.now();
    return new IncidentView(
        Ids.newId(),
        1,
        1L,
        TITLE,
        IncidentState.OPEN,
        now,
        now,
        now,
        now,
        alertCount,
        types,
        List.of("zabbix"),
        hypothesis == null ? null : hypothesis.componentKey(),
        hypothesis,
        null,
        services,
        null,
        null,
        List.of(),
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
        now,
        now);
  }

  static MembershipView member(String type, String typeName) {
    return new MembershipView(
        Ids.newId(),
        Ids.newId(),
        new AlertCopy(
            Ids.newId(),
            "zabbix",
            "k-" + UUID.randomUUID(),
            type,
            typeName,
            "title",
            null,
            null,
            Instant.now()),
        "{}",
        null,
        Ids.newId(),
        Instant.now(),
        null);
  }

  static IncidentSummaryView summary(SummarySource source, String text) {
    return new IncidentSummaryView(
        Ids.newId(),
        Ids.newId(),
        null,
        text,
        source,
        source == SummarySource.AI ? "claude-api" : null,
        Instant.now());
  }

  static EvidenceView recovery(String content) {
    return new EvidenceView(
        Ids.newId(),
        Ids.newId(),
        EvidenceKind.RECOVERY_VERIFICATION,
        "vigil-recovery",
        Ids.newId(),
        "vigil-recovery, check db-disk-recovered",
        Instant.now(),
        "Recovery verified: every condition held for 60 s.",
        content,
        DataCategory.INCIDENT_RECORD);
  }

  static final String RECOVERY_CONTENT =
      "{\"definition_snapshot\":{\"observation_window_seconds\":60},"
          + "\"window_started_at\":\"2026-10-10T09:12:00.250Z\","
          + "\"decided_at\":\"2026-10-10T09:13:00.900Z\","
          + "\"last_evaluation\":{\"conditions\":["
          + "{\"detail\":\"No 'Disk full' alert firing on Subscriber DB server\"},"
          + "{\"detail\":\"No 'Database write errors' alert firing on Subscriber DB\"}]}}";

  static List<String> texts(List<TicketLine> lines) {
    return lines.stream().map(TicketLine::text).toList();
  }

  @Test
  @DisplayName("DC-OUT-01 the title and the seven description parts of a create")
  void create() {
    IncidentView incident =
        incident(7, List.of("disk_full", "db_write_errors"), HYPOTHESIS, SERVICES);
    List<MembershipView> members =
        List.of(
            member("disk_full", "Disk full"),
            member("db_write_errors", "Database write errors"),
            member("disk_full", "Another name"));
    List<String> evidence =
        List.of("/data is 100% full (15.9 of 15.9 GiB used, 0.1 GiB free)", "Second entry");
    List<TicketLine> ai =
        TicketTexts.createLines(
            incident,
            members,
            summary(SummarySource.AI, "The disk is full."),
            evidence,
            "Techfinity",
            LINK);
    assertThat(texts(ai))
        .containsExactly(
            "Incident: " + TITLE,
            "Alerts: 7 alerts. Types: Database write errors, Disk full.",
            "Customer impact: Balance check, Bundle purchase, USSD self-service.",
            "Root-cause hypothesis: Subscriber DB server (srv-db-01). A hypothesis from the"
                + " service map, not a confirmed cause.",
            "AI summary: The disk is full.",
            "Evidence:",
            "- /data is 100% full (15.9 of 15.9 GiB used, 0.1 GiB free)",
            "- Second entry",
            "Open INC-0001 in Techfinity: " + LINK);
    assertThat(ai.getLast().link()).isEqualTo(LINK);
    assertThat(ai.subList(0, ai.size() - 1)).allMatch(line -> line.link() == null);

    List<TicketLine> template =
        TicketTexts.createLines(
            incident(1, List.of("disk_full"), null, List.of()),
            List.of(),
            summary(SummarySource.TEMPLATE, "Template text."),
            List.of(),
            "Techfinity",
            LINK);
    assertThat(texts(template))
        .containsExactly(
            "Incident: " + TITLE,
            "Alerts: 1 alert. Types: disk_full.",
            "Customer impact: none identified.",
            "Root-cause hypothesis: none.",
            "Summary (template): Template text.",
            "Evidence:",
            "- none yet",
            "Open INC-0001 in Techfinity: " + LINK);
    assertThat(TicketTexts.number(1)).isEqualTo("INC-0001");
    assertThat(TicketTexts.number(12345)).isEqualTo("INC-12345");
    assertThat(TicketTexts.title(LABEL, TITLE))
        .isEqualTo("Demo environment — fictional data - " + TITLE)
        .hasSize(89);
  }

  @Test
  @DisplayName("DM-E02-R2 the environment label prefix of the Jira title, none when empty; cut")
  void titlePrefix() {
    assertThat(TicketTexts.title(LABEL, TITLE)).isEqualTo(LABEL + " - " + TITLE);
    assertThat(TicketTexts.title(null, TITLE)).isEqualTo(TITLE);
    assertThat(TicketTexts.title("", TITLE)).isEqualTo(TITLE);
    String long300 = "😀".repeat(300);
    String cut = TicketTexts.title("L", long300);
    assertThat(cut.codePointCount(0, cut.length())).isEqualTo(255);
    assertThat(cut).startsWith("L - 😀");
  }

  @Test
  @DisplayName("DC-OUT-02 the escalation comment with team names, cause and evidence")
  void escalation() {
    assertThat(
            texts(
                TicketTexts.escalationLines(
                    1,
                    List.of("NOC Shift Lead", "Database Operations"),
                    EscalationCause.ACKNOWLEDGEMENT_TIMEOUT,
                    Duration.ofSeconds(120),
                    List.of("E1 summary", "E2 summary"))))
        .containsExactly(
            "Escalated to level 1: Database Operations, NOC Shift Lead"
                + " (not acknowledged within 2 min).",
            "Evidence:",
            "- E1 summary",
            "- E2 summary");
    assertThat(
            texts(
                TicketTexts.escalationLines(
                    2, List.of(), EscalationCause.ESCALATE_NOW, null, List.of())))
        .containsExactly("Escalated to level 2: none (workflow escalation step).", "Evidence:");
  }

  @Test
  @DisplayName("DC-OUT-03 the resolve comment from the recovery verification's content")
  void resolve() {
    assertThat(texts(TicketTexts.resolveLines(recovery(RECOVERY_CONTENT)).orElseThrow()))
        .containsExactly(
            "Resolved: recovery verified.",
            "Recovery verified: every condition held for 60 s.",
            "Conditions:",
            "- No 'Disk full' alert firing on Subscriber DB server: held",
            "- No 'Database write errors' alert firing on Subscriber DB: held",
            "- Every monitoring source is healthy: held",
            "Observation window: 60 s, from 2026-10-10 09:12:00 UTC to 2026-10-10 09:13:00 UTC.",
            "Source: vigil-recovery, check db-disk-recovered");
    for (String bad :
        List.of(
            "{}",
            "not json",
            RECOVERY_CONTENT.replace("60}", "0}"),
            RECOVERY_CONTENT.replace("60}", "\"60\"}"),
            RECOVERY_CONTENT.replace("2026-10-10T09:12:00.250Z", "yesterday"),
            RECOVERY_CONTENT.replace("{\"detail\":\"No 'Disk", "{\"text\":\"No 'Disk"))) {
      assertThat(TicketTexts.resolveLines(recovery(bad))).as(bad).isEmpty();
    }
  }

  @Test
  @DisplayName("DC-OUT-04 the reopen comment names the alert, its source kind and time")
  void reopen() {
    assertThat(
            TicketTexts.reopenLine(
                    "Disk full on srv-db-01", "zabbix", Instant.parse("2026-10-10T09:12:00.999Z"))
                .text())
        .isEqualTo(
            "Reopened by alert 'Disk full on srv-db-01' (Zabbix) received at"
                + " 2026-10-10 09:12:00 UTC.");
    assertThat(
            TicketTexts.reopenLine("X", "alertmanager", Instant.parse("2026-01-02T03:04:05Z"))
                .text())
        .isEqualTo("Reopened by alert 'X' (Alertmanager) received at 2026-01-02 03:04:05 UTC.");
  }

  @Test
  @DisplayName("DC-OUT-00 durations, outbound times, counts and the audit summary")
  void formats() {
    assertThat(TicketTexts.duration(Duration.ofSeconds(59))).isEqualTo("59 s");
    assertThat(TicketTexts.duration(Duration.ofSeconds(119))).isEqualTo("119 s");
    assertThat(TicketTexts.duration(Duration.ofSeconds(120))).isEqualTo("2 min");
    assertThat(TicketTexts.duration(Duration.ofSeconds(125))).isEqualTo("2 min 5 s");
    assertThat(TicketTexts.duration(Duration.ofSeconds(300))).isEqualTo("5 min");
    assertThat(TicketTexts.time(Instant.parse("2026-10-10T09:12:00.999Z")))
        .isEqualTo("2026-10-10 09:12:00 UTC");
    IncidentView two = incident(2, List.of("disk_full"), null, List.of());
    assertThat(
            TicketTexts.createLines(
                    two,
                    List.of(),
                    summary(SummarySource.TEMPLATE, "t"),
                    List.of(),
                    "Techfinity",
                    LINK)
                .get(1)
                .text())
        .isEqualTo("Alerts: 2 alerts. Types: disk_full.");
    assertThat(TicketTexts.auditSummary("NOC-1", SyncOperation.CREATE, SyncOutcome.DONE))
        .isEqualTo("Jira NOC-1: create done.");
    assertThat(TicketTexts.auditSummary(null, SyncOperation.RESOLVE, SyncOutcome.FAILED))
        .isEqualTo("Jira no ticket: resolve failed.");
    assertThat(TicketTexts.auditSummary("NOC-1", SyncOperation.RECORD_ESCALATION, SyncOutcome.DONE))
        .isEqualTo("Jira NOC-1: record escalation done.");
    assertThat(TicketTexts.createContent("T", List.of(TicketLine.of("a")), "vigil-x"))
        .isEqualTo("{\"title\":\"T\",\"description\":[\"a\"],\"labels\":[\"vigil-x\"]}");
    assertThat(TicketTexts.commentContent(List.of(TicketLine.of("a")), "Done"))
        .isEqualTo("{\"comment\":[\"a\"],\"transition_to\":\"Done\"}");
  }
}
