package vigil.incidents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import vigil.incidents.api.AiStatus;
import vigil.incidents.api.AiUnavailableReason;
import vigil.incidents.api.DataCategory;
import vigil.incidents.api.EscalationCause;
import vigil.incidents.api.EscalationView;
import vigil.incidents.api.EvidenceKind;
import vigil.incidents.api.EvidenceView;
import vigil.incidents.api.IncidentChanged;
import vigil.incidents.api.IncidentService;
import vigil.incidents.api.IncidentState;
import vigil.incidents.api.IncidentView;
import vigil.incidents.api.NotifiedPurpose;
import vigil.incidents.api.NotifiedTeamView;
import vigil.incidents.api.Severity;
import vigil.incidents.api.TimelineEntryType;
import vigil.incidents.api.TimelineView;
import vigil.shared.Actor;
import vigil.shared.Ids;
import vigil.shared.Key;
import vigil.testsupport.MutableClock;
import vigil.testsupport.VigilDatabase;
import vigil.testsupport.VigilTestApp;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IncidentRequestsIT {

  private static final MutableClock CLOCK = new MutableClock(Instant.parse("2026-10-22T11:00:00Z"));
  private static final String RULE = "Subscriber database P1";

  private VigilTestApp app;
  private JdbcClient postgres;
  private IncidentService incidents;
  private IncidentFixture f;
  private UUID storm;
  private Instant t0;

  private UUID evaluation1;
  private UUID evaluation2;
  private Map<String, Long> beforeRepeatedAssignment;
  private int eventsBeforeRepeatedAssignment;
  private Map<String, Long> afterRepeatedAssignment;
  private int eventsAfterRepeatedAssignment;
  private Map<String, Long> beforeRepeatedTeam;
  private int eventsBeforeRepeatedTeam;
  private Map<String, Long> afterRepeatedTeam;
  private int eventsAfterRepeatedTeam;
  private UUID diagnostic;
  private UUID diagnosticRecord;
  private Map<String, Long> beforeRepeatedEvidence;
  private int eventsBeforeRepeatedEvidence;
  private UUID repeatedEvidence;
  private Map<String, Long> afterRepeatedEvidence;
  private int eventsAfterRepeatedEvidence;

  /** The test clock and the event recorder. */
  static class TestBeans {

    @Bean
    @Primary
    Clock testClock() {
      return CLOCK;
    }

    @Bean
    IncidentFixture.Events incidentEvents() {
      return new IncidentFixture.Events();
    }
  }

  @BeforeAll
  void start() {
    VigilDatabase database = VigilDatabase.fresh();
    app = VigilTestApp.start(database, TestBeans.class);
    app.awaitStarted();
    postgres = database.postgres();
    incidents = app.context().getBean(IncidentService.class);
    f = IncidentFixture.create(app, "Tenant R", "r.incidents.test");
    t0 = CLOCK.instant();
    storm = f.stormLike(t0);
  }

  @AfterAll
  void stop() {
    app.close();
  }

  private IncidentView view() {
    return incidents.incident(f.tenant, storm).orElseThrow();
  }

  private List<TimelineView> entries(TimelineEntryType type) {
    return incidents.timeline(f.tenant, storm).stream().filter(e -> e.entryType() == type).toList();
  }

  private List<String> auditSummaries(String type) {
    return postgres
        .sql(
            "SELECT summary FROM audit.audit_event WHERE tenant_id = :t AND event_type = :type"
                + " ORDER BY sequence")
        .param("t", f.tenant.value())
        .param("type", type)
        .query(String.class)
        .list();
  }

  private Map<String, Long> rows() {
    return IncidentFixture.rows(postgres, f.tenant);
  }

  private static int events() {
    return IncidentFixture.Events.RECEIVED.size();
  }

  private void assign(UUID evaluation, Actor actor) {
    incidents.setAssignment(
        f.tenant,
        actor,
        storm,
        Severity.P1,
        new Key("db-ops"),
        Set.of(new Key("noc-l1"), new Key("it-apps")),
        evaluation,
        RULE);
  }

  private UUID evidence(
      Actor actor, EvidenceKind kind, UUID record, Instant collected, String summary) {
    return incidents.addEvidence(
        f.tenant,
        actor,
        storm,
        kind,
        record,
        "lab-db-01 via runner, operation disk_usage",
        collected,
        summary,
        "{\"used_percent\": 100}",
        Incidents.category(kind));
  }

  @Test
  @Order(1)
  @DisplayName("PM-M09-03 the assignment accepted only from vigil-routing, while forming")
  void assignment() {
    evaluation1 = Ids.newId();
    assign(evaluation1, IncidentFixture.ROUTING);
    assertThat(entries(TimelineEntryType.ASSIGNMENT_CHANGED).stream().map(TimelineView::text))
        .containsExactly(
            "Assigned P1, owner Database Operations, informed IT Applications, NOC Level 1"
                + " (rule 'Subscriber database P1').");
    assertThat(auditSummaries("assignment_changed"))
        .containsExactly(
            "Assigned P1, owner Database Operations, informed IT Applications, NOC Level 1 by"
                + " rule 'Subscriber database P1'.");
    evaluation2 = Ids.newId();
    assign(evaluation2, IncidentFixture.ROUTING);
    assertThat(view().assignmentEvaluationId()).isEqualTo(evaluation2);
    assertThat(entries(TimelineEntryType.ASSIGNMENT_CHANGED)).hasSize(2);
    assertThat(auditSummaries("assignment_changed")).hasSize(2);
    beforeRepeatedAssignment = rows();
    eventsBeforeRepeatedAssignment = events();
    assign(evaluation2, IncidentFixture.ROUTING);
    afterRepeatedAssignment = rows();
    eventsAfterRepeatedAssignment = events();
    assertThat(afterRepeatedAssignment).isEqualTo(beforeRepeatedAssignment);
    assertThat(eventsAfterRepeatedAssignment).isEqualTo(eventsBeforeRepeatedAssignment);
    assertThatThrownBy(() -> assign(Ids.newId(), IncidentFixture.CORRELATION))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("setAssignment is accepted only from vigil-routing");
    assertThat(view().assignmentEvaluationId()).isEqualTo(evaluation2);
  }

  @Test
  @Order(2)
  @DisplayName("DM-V-09 the stored assignment fields")
  void assignmentFields() {
    IncidentView view = view();
    assertThat(view.severity()).isEqualTo(Severity.P1);
    assertThat(view.ownerTeamKey()).isEqualTo("db-ops");
    assertThat(view.informedTeamKeys()).containsExactly("it-apps", "noc-l1");
    assertThat(view.assignmentEvaluationId()).isEqualTo(evaluation2);
    assertThat(
            postgres
                .sql("SELECT informed_team_keys::text FROM incidents.incident WHERE id = :id")
                .param("id", storm)
                .query(String.class)
                .single())
        .isEqualTo("[\"it-apps\", \"noc-l1\"]");
  }

  @Test
  @Order(3)
  @DisplayName("PM-M09-08 escalations from vigil-routing, notified teams from vigil-notifications")
  void escalationsAndNotifiedTeams() {
    f.open(storm);
    UUID escalation =
        incidents.recordEscalation(
            f.tenant,
            IncidentFixture.ROUTING,
            storm,
            1,
            Set.of(new Key("noc-l1")),
            EscalationCause.ACKNOWLEDGEMENT_TIMEOUT,
            Duration.ofSeconds(120),
            1);
    assertThat(view().escalationLevel()).isEqualTo(1);
    TimelineView escalated = entries(TimelineEntryType.ESCALATED).getFirst();
    assertThat(escalated.text())
        .isEqualTo("Escalated to level 1: NOC Level 1 (not acknowledged within 2 min).");
    assertThat(escalated.refType()).isEqualTo("IncidentEscalation");
    assertThat(escalated.refId()).isEqualTo(escalation);
    Map<String, Object> audit =
        postgres
            .sql(
                "SELECT subject_type, subject_id, incident_id, summary FROM audit.audit_event"
                    + " WHERE tenant_id = :t AND event_type = 'incident_escalated'")
            .param("t", f.tenant.value())
            .query()
            .singleRow();
    assertThat(audit.get("subject_type")).isEqualTo("IncidentEscalation");
    assertThat(audit.get("subject_id")).isEqualTo(escalation);
    assertThat(audit.get("incident_id")).isEqualTo(storm);
    assertThat(audit.get("summary")).isEqualTo(escalated.text());
    assertThatThrownBy(
            () ->
                incidents.recordEscalation(
                    f.tenant,
                    IncidentFixture.ROUTING,
                    storm,
                    2,
                    Set.of(new Key("noc-l1")),
                    EscalationCause.ESCALATE_NOW,
                    Duration.ofSeconds(60),
                    1))
        .isInstanceOf(IllegalArgumentException.class);
    f.notified(storm, "db-ops");
    beforeRepeatedTeam = rows();
    eventsBeforeRepeatedTeam = events();
    f.notified(storm, "db-ops");
    afterRepeatedTeam = rows();
    eventsAfterRepeatedTeam = events();
  }

  @Test
  @Order(4)
  @DisplayName("DM-E-36 the escalation record")
  void escalationRecord() {
    EscalationView escalation = incidents.escalations(f.tenant, storm).getFirst();
    assertThat(escalation.level()).isEqualTo(1);
    assertThat(escalation.teamKeys()).containsExactly("noc-l1");
    assertThat(escalation.cause()).isEqualTo(EscalationCause.ACKNOWLEDGEMENT_TIMEOUT);
    assertThat(escalation.timer()).isEqualTo(Duration.ofSeconds(120));
    assertThat(escalation.policyRevision()).isEqualTo(1);
    assertThat(escalation.at()).isEqualTo(t0);
    assertThat(incidents.escalations(f.tenant, storm)).hasSize(1);
  }

  @Test
  @Order(5)
  @DisplayName("DM-E-37 the notified team record")
  void notifiedTeamRecord() {
    NotifiedTeamView team = incidents.notifiedTeams(f.tenant, storm).getFirst();
    assertThat(team.teamKey()).isEqualTo("db-ops");
    assertThat(team.firstNotifiedAt()).isEqualTo(t0);
    assertThat(team.purpose()).isEqualTo(NotifiedPurpose.INCIDENT_NOTIFICATION);
    TimelineView entry = entries(TimelineEntryType.NOTIFIED_TEAM_ADDED).getFirst();
    assertThat(entry.text()).isEqualTo("Database Operations notified (incident notification).");
    assertThat(entry.refType()).isEqualTo("NotifiedTeam");
    assertThat(entry.refId()).isEqualTo(team.id());
  }

  @Test
  @Order(6)
  @DisplayName("DM-E37-R1 at most one record per incident and team")
  void oneRecordPerTeam() {
    assertThat(incidents.notifiedTeams(f.tenant, storm)).hasSize(1);
    assertThat(entries(TimelineEntryType.NOTIFIED_TEAM_ADDED)).hasSize(1);
    assertThat(afterRepeatedTeam).isEqualTo(beforeRepeatedTeam);
  }

  @Test
  @Order(7)
  @DisplayName("PM-M09-05 evidence entries as unchangeable copies supplied by their producers")
  void evidence() {
    diagnosticRecord = Ids.newId();
    diagnostic =
        evidence(
            IncidentFixture.DIAGNOSTICS,
            EvidenceKind.DIAGNOSTIC_RESULT,
            diagnosticRecord,
            t0.minusSeconds(5),
            "Disk usage of /data: 100%");
    beforeRepeatedEvidence = rows();
    eventsBeforeRepeatedEvidence = events();
    repeatedEvidence =
        evidence(
            IncidentFixture.DIAGNOSTICS,
            EvidenceKind.DIAGNOSTIC_RESULT,
            diagnosticRecord,
            t0,
            "another summary");
    afterRepeatedEvidence = rows();
    eventsAfterRepeatedEvidence = events();
    assertThatThrownBy(
            () ->
                evidence(
                    IncidentFixture.DIAGNOSTICS,
                    EvidenceKind.APPROVAL,
                    Ids.newId(),
                    t0,
                    "Approved by DB Ops Engineer: Clean up on Subscriber DB server."))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("addEvidence is accepted only from vigil-actions");
    String summary = "s".repeat(250);
    UUID cut =
        evidence(
            IncidentFixture.DIAGNOSTICS,
            EvidenceKind.DIAGNOSTIC_RESULT,
            Ids.newId(),
            t0.plusSeconds(30),
            summary);
    EvidenceView stored =
        incidents.evidence(f.tenant, storm).stream()
            .filter(e -> e.id().equals(cut))
            .findFirst()
            .orElseThrow();
    assertThat(stored.summary()).isEqualTo("s".repeat(199) + "…");
    assertThat(stored.summary().codePointCount(0, stored.summary().length())).isEqualTo(200);
  }

  @Test
  @Order(8)
  @DisplayName("DM-E-38 the evidence entry")
  void evidenceEntry() {
    EvidenceView entry = incidents.evidence(f.tenant, storm).getFirst();
    assertThat(entry.id()).isEqualTo(diagnostic);
    assertThat(entry.kind()).isEqualTo(EvidenceKind.DIAGNOSTIC_RESULT);
    assertThat(entry.producerModule()).isEqualTo("vigil-diagnostics");
    assertThat(entry.producerRecordId()).isEqualTo(diagnosticRecord);
    assertThat(entry.sourceSystem()).isEqualTo("lab-db-01 via runner, operation disk_usage");
    assertThat(entry.collectedAt()).isEqualTo(t0.minusSeconds(5));
    assertThat(entry.summary()).isEqualTo("Disk usage of /data: 100%");
    assertThat(IncidentJson.read(entry.contentJson()))
        .isEqualTo(IncidentJson.read("{\"used_percent\": 100}"));
    assertThat(entry.dataCategory()).isEqualTo(DataCategory.DIAGNOSTIC_OUTPUT);
    TimelineView added = entries(TimelineEntryType.EVIDENCE_ADDED).getFirst();
    assertThat(added.text()).isEqualTo("Evidence: Disk usage of /data: 100%");
    assertThat(added.refType()).isEqualTo("EvidenceEntry");
    assertThat(added.refId()).isEqualTo(diagnostic);
  }

  @Test
  @Order(9)
  @DisplayName("DM-R-65 an evidence entry is unique per producer module and record")
  void evidenceUnique() {
    assertThat(repeatedEvidence).isEqualTo(diagnostic);
    assertThat(afterRepeatedEvidence).isEqualTo(beforeRepeatedEvidence);
    assertThat(eventsAfterRepeatedEvidence).isEqualTo(eventsBeforeRepeatedEvidence);
  }

  @Test
  @Order(10)
  @DisplayName("DM-R-44 the data category of each evidence kind")
  void dataCategory() {
    assertThatThrownBy(
            () ->
                incidents.addEvidence(
                    f.tenant,
                    IncidentFixture.DIAGNOSTICS,
                    storm,
                    EvidenceKind.DIAGNOSTIC_RESULT,
                    Ids.newId(),
                    "lab-db-01 via runner, operation disk_usage",
                    t0,
                    "Disk usage of /data: 100%",
                    "{}",
                    DataCategory.INCIDENT_RECORD))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(Incidents.category(EvidenceKind.DIAGNOSTIC_RESULT))
        .isEqualTo(DataCategory.DIAGNOSTIC_OUTPUT);
    assertThat(Incidents.category(EvidenceKind.SIMILARITY_MATCH))
        .isEqualTo(DataCategory.INCIDENT_HISTORY);
    for (EvidenceKind kind :
        List.of(
            EvidenceKind.AI_INVESTIGATION,
            EvidenceKind.APPROVAL,
            EvidenceKind.ACTION_EXECUTION,
            EvidenceKind.RECOVERY_VERIFICATION)) {
      assertThat(Incidents.category(kind)).isEqualTo(DataCategory.INCIDENT_RECORD);
    }
  }

  @Test
  @Order(11)
  @DisplayName("PM-M09-11 the evidence summary in collection-time order")
  void evidenceSummary() {
    evidence(
        IncidentFixture.DIAGNOSTICS,
        EvidenceKind.DIAGNOSTIC_RESULT,
        Ids.newId(),
        t0.plusSeconds(20),
        "third");
    evidence(
        IncidentFixture.DIAGNOSTICS,
        EvidenceKind.DIAGNOSTIC_RESULT,
        Ids.newId(),
        t0.minusSeconds(10),
        "first");
    evidence(
        IncidentFixture.DIAGNOSTICS,
        EvidenceKind.DIAGNOSTIC_RESULT,
        Ids.newId(),
        t0.plusSeconds(10),
        "second");
    assertThat(incidents.evidenceSummary(f.tenant, storm))
        .containsExactly(
            "first", "Disk usage of /data: 100%", "second", "third", "s".repeat(199) + "…");
  }

  @Test
  @Order(12)
  @DisplayName("PM-M09-07 the AI status on request of vigil-investigation")
  void aiStatus() {
    UUID g1 = Ids.newId();
    incidents.setAiStatus(
        f.tenant,
        IncidentFixture.INVESTIGATION,
        storm,
        AiStatus.UNAVAILABLE,
        AiUnavailableReason.NO_PROVIDER_REACHABLE,
        g1);
    IncidentView unavailable = view();
    assertThat(unavailable.aiStatus()).isEqualTo(AiStatus.UNAVAILABLE);
    assertThat(unavailable.aiUnavailableReason())
        .isEqualTo(AiUnavailableReason.NO_PROVIDER_REACHABLE);
    assertThat(unavailable.aiStatusChangedAt()).isEqualTo(t0);
    assertThat(unavailable.aiLastRequestGroupId()).isEqualTo(g1);
    assertThat(entries(TimelineEntryType.AI_STATUS_CHANGED).stream().map(TimelineView::text))
        .containsExactly("AI investigation unavailable: no allowed AI provider was reachable.");
    CLOCK.advance(Duration.ofSeconds(5));
    UUID g2 = Ids.newId();
    int timeline = incidents.timeline(f.tenant, storm).size();
    int before = events();
    incidents.setAiStatus(
        f.tenant,
        IncidentFixture.INVESTIGATION,
        storm,
        AiStatus.UNAVAILABLE,
        AiUnavailableReason.NO_PROVIDER_REACHABLE,
        g2);
    assertThat(view().aiLastRequestGroupId()).isEqualTo(g2);
    assertThat(view().aiStatusChangedAt()).isEqualTo(t0);
    assertThat(incidents.timeline(f.tenant, storm)).hasSize(timeline);
    assertThat(IncidentFixture.Events.RECEIVED.subList(before, events()))
        .containsExactly(new IncidentChanged(f.tenant, storm));
    incidents.setAiStatus(
        f.tenant, IncidentFixture.INVESTIGATION, storm, AiStatus.AVAILABLE, null, Ids.newId());
    assertThat(entries(TimelineEntryType.AI_STATUS_CHANGED).getLast().text())
        .isEqualTo("AI investigation available.");
    assertThat(view().aiStatusChangedAt()).isEqualTo(t0.plusSeconds(5));

    f.resolve(storm);
    CLOCK.advance(Duration.ofSeconds(301));
    IncidentFixture.await(() -> view().state() == IncidentState.CLOSED);
    incidents.setAiStatus(
        f.tenant,
        IncidentFixture.INVESTIGATION,
        storm,
        AiStatus.UNAVAILABLE,
        AiUnavailableReason.OUTPUT_FAILED_CHECKS,
        Ids.newId());
    assertThat(view().aiStatus()).isEqualTo(AiStatus.UNAVAILABLE);
    UUID rerun =
        evidence(
            IncidentFixture.INVESTIGATION,
            EvidenceKind.AI_INVESTIGATION,
            Ids.newId(),
            CLOCK.instant(),
            "AI investigation by Claude; cites rb-disk-full.");
    assertThat(incidents.evidence(f.tenant, storm)).anyMatch(e -> e.id().equals(rerun));
    assertThatThrownBy(
            () ->
                evidence(
                    IncidentFixture.DIAGNOSTICS,
                    EvidenceKind.DIAGNOSTIC_RESULT,
                    Ids.newId(),
                    CLOCK.instant(),
                    "Disk usage of /data: 40%"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("addEvidence is not allowed in state closed");
  }

  @Test
  @Order(13)
  @DisplayName("DM-E32-R2 a timeline entry for every change; a request that changes nothing")
  void changesAndNoChanges() {
    assertThat(afterRepeatedAssignment).isEqualTo(beforeRepeatedAssignment);
    assertThat(eventsAfterRepeatedAssignment).isEqualTo(eventsBeforeRepeatedAssignment);
    assertThat(afterRepeatedTeam).isEqualTo(beforeRepeatedTeam);
    assertThat(eventsAfterRepeatedTeam).isEqualTo(eventsBeforeRepeatedTeam);
    assertThat(afterRepeatedEvidence).isEqualTo(beforeRepeatedEvidence);
    assertThat(eventsAfterRepeatedEvidence).isEqualTo(eventsBeforeRepeatedEvidence);
    UUID group = view().aiLastRequestGroupId();
    Map<String, Long> before = rows();
    int events = events();
    incidents.setAiStatus(
        f.tenant,
        IncidentFixture.INVESTIGATION,
        storm,
        AiStatus.UNAVAILABLE,
        AiUnavailableReason.OUTPUT_FAILED_CHECKS,
        group);
    assertThat(rows()).isEqualTo(before);
    assertThat(events()).isEqualTo(events);
    assertThat(incidents.timeline(f.tenant, storm).stream().map(e -> e.entryType().key()))
        .contains(
            "assignment_changed",
            "escalated",
            "notified_team_added",
            "evidence_added",
            "ai_status_changed",
            "state_changed");
  }
}
