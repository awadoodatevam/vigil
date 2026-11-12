package vigil.recovery;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.JsonNode;
import vigil.audit.api.AuditEventType;
import vigil.audit.api.AuditEventView;
import vigil.audit.api.AuditQuery;
import vigil.audit.api.AuditService;
import vigil.incidents.api.EvidenceKind;
import vigil.incidents.api.EvidenceView;
import vigil.incidents.api.IncidentState;
import vigil.incidents.api.IncidentView;
import vigil.incidents.api.MembershipView;
import vigil.incidents.api.TimelineView;
import vigil.intake.IntakeFixture;
import vigil.intake.api.AlertView;
import vigil.intake.api.Health;
import vigil.intake.api.SourceView;
import vigil.recovery.api.ConditionKind;
import vigil.recovery.api.RecoveryCheckView;
import vigil.recovery.api.RecoveryCondition;
import vigil.recovery.api.RecoveryPart;
import vigil.recovery.api.RecoveryPart.DefinitionPart;
import vigil.recovery.api.RecoveryService;
import vigil.recovery.api.RecoveryVerificationView;
import vigil.recovery.api.VerificationState;
import vigil.reporting.api.DashboardView;
import vigil.reporting.api.MetricKey;
import vigil.reporting.api.MetricView;
import vigil.reporting.api.ReportingService;
import vigil.shared.Completed;
import vigil.shared.Completed.ResultRef;
import vigil.shared.Ids;
import vigil.shared.Key;
import vigil.shared.StepAnswer;
import vigil.shared.StepCall;
import vigil.shared.StepCancel;
import vigil.shared.Waiting;
import vigil.testsupport.MutableClock;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RecoveryStepIT {

  static final MutableClock CLOCK = new MutableClock(Instant.parse("2026-11-12T10:00:00Z"));
  private static final String CHECK = "rc-subscriber-db";
  private static final String CHECK_NAME = "Subscriber DB services recovered";
  private static final String DISK_FULL = "Disk full: /data free space below 1 GiB";
  private static final String SOURCES = "Monitoring: Alertmanager — Jazz lab, Zabbix — Jazz lab";
  private static final Set<Waiting.WakeEvent> WAKE_EVENTS =
      Set.of(Waiting.WakeEvent.ALERT_STATE_CHANGED, Waiting.WakeEvent.SOURCE_HEALTH_CHANGED);

  /** The test clock. */
  static class TestBeans {

    @Bean
    @Primary
    Clock testClock() {
      return CLOCK;
    }
  }

  private RecoveryFixture.App servers;
  private RecoveryService recovery;
  private AuditService audit;

  @BeforeAll
  void start() {
    servers = RecoveryFixture.App.start(CLOCK, TestBeans.class);
    recovery = servers.app.context().getBean(RecoveryService.class);
    audit = servers.app.context().getBean(AuditService.class);
  }

  @AfterAll
  void stop() {
    servers.close();
  }

  /** One storyline: its tenant, alerts, open incident and the step's run and execution. */
  private final class Story {

    final RecoveryFixture f;
    final AlertView zabbix;
    final AlertView probe;
    final UUID incident;
    final UUID run = Ids.newId();
    final UUID step = Ids.newId();
    Instant t0;
    long count;
    String key = CHECK;

    Story(RecoveryFixture f, AlertView zabbix, AlertView probe, UUID incident) {
      this.f = f;
      this.zabbix = zabbix;
      this.probe = probe;
      this.incident = incident;
    }

    StepAnswer call() {
      return call(300);
    }

    StepAnswer call(Object maxWait) {
      count++;
      StepCall call = f.call(run, incident, step, maxWait, count, key);
      return count == 1 ? f.handler.start(call) : f.handler.evaluate(call);
    }

    /** The first call at {@code T0}. */
    StepAnswer first() {
      t0 = CLOCK.instant();
      return call();
    }

    RecoveryVerificationView verification() {
      List<RecoveryVerificationView> all = recovery.verifications(f.tenant, incident);
      assertThat(all).hasSize(1);
      return all.getFirst();
    }

    JsonNode evaluation() {
      return RecoveryFixture.JSON.readTree(verification().lastEvaluationJson());
    }

    List<EvidenceView> evidence() {
      return f.incidents.evidence(f.tenant, incident).stream()
          .filter(e -> e.kind() == EvidenceKind.RECOVERY_VERIFICATION)
          .toList();
    }

    List<AuditEventView> events() {
      return audit
          .query(
              f.tenant,
              new AuditQuery(
                  null,
                  null,
                  null,
                  Set.of(AuditEventType.RECOVERY_VERIFIED),
                  null,
                  null,
                  null,
                  50,
                  null))
          .items();
    }

    IncidentView incident() {
      return f.incidents.incident(f.tenant, incident).orElseThrow();
    }

    /** Resolves the Alertmanager alert, then at {@code T1} the Zabbix alert; returns {@code T1}. */
    Instant resolveBoth() {
      CLOCK.advance(Duration.ofSeconds(5));
      f.resolve(probe);
      call();
      CLOCK.advance(Duration.ofSeconds(10));
      Instant t1 = CLOCK.instant();
      f.resolve(zabbix);
      call();
      return t1;
    }

    /** The storyline to {@code recovered}; returns {@code T1}. */
    Instant recover() {
      first();
      Instant t1 = resolveBoth();
      CLOCK.set(t1.plusSeconds(60));
      assertThat(call()).isInstanceOf(Completed.class);
      return t1;
    }

    /** The storyline to {@code not_recovered}: the Zabbix alert still firing. */
    StepAnswer notRecover() {
      first();
      CLOCK.advance(Duration.ofSeconds(5));
      f.resolve(probe);
      call();
      CLOCK.set(t0.plusSeconds(300));
      return call();
    }
  }

  private RecoveryFixture tenant() {
    return servers.tenant().withDemoDefinitions();
  }

  /** The storyline's alerts in a new tenant, grouped into one open incident. */
  private Story story() {
    return story(tenant());
  }

  private Story story(RecoveryFixture f) {
    awaitHealthy(f);
    AlertView zabbix = f.zabbix("srv-db-01", "lab-db-01", DISK_FULL);
    AlertView probe =
        f.alertmanager("ussd-probe", "lab-probe-01:9100", "SyntheticUssdSessionFailed");
    UUID incident = f.open(zabbix);
    assertThat(f.incidents.members(f.tenant, incident))
        .extracting(MembershipView::alertId)
        .containsExactlyInAnyOrder(zabbix.id(), probe.id());
    return new Story(f, zabbix, probe, incident);
  }

  private static void awaitHealthy(RecoveryFixture f) {
    IntakeFixture.await(
        () -> f.intake.sources(f.tenant).stream().allMatch(s -> s.health() == Health.HEALTHY));
  }

  private static List<Boolean> held(JsonNode evaluation) {
    List<Boolean> held = new ArrayList<>();
    evaluation.get("conditions").forEach(c -> held.add(c.get("held").booleanValue()));
    return held;
  }

  private static List<String> details(JsonNode evaluation) {
    List<String> details = new ArrayList<>();
    evaluation.get("conditions").forEach(c -> details.add(c.get("detail").stringValue()));
    return details;
  }

  private static ResultRef ref(RecoveryVerificationView verification) {
    return new ResultRef("vigil-recovery", "RecoveryVerification", verification.id());
  }

  private static String snapshot() {
    return "{\"recovery_check_key\": \"rc-subscriber-db\", \"name\": \""
        + CHECK_NAME
        + "\","
        + " \"conditions\": [{\"kind\": \"incident_alerts_resolved\"}, {\"kind\":"
        + " \"component_no_firing_alert\", \"component_key\": \"ussd-probe\", \"alert_types\":"
        + " []}], \"observation_window_seconds\": 60}";
  }

  @Test
  @DisplayName("SL-A1-07 resolved only when every condition held for the window, sources healthy")
  void storyline() {
    Story s = story();
    StepAnswer first = s.first();
    assertThat(first).isEqualTo(new Waiting(WAKE_EVENTS, s.t0.plusSeconds(300), null));
    assertThat(held(s.evaluation())).containsExactly(false, false);
    assertThat(details(s.evaluation()))
        .containsExactly(
            "2 alerts of the incident are still firing",
            "1 alert of Synthetic USSD sessions is firing");

    CLOCK.advance(Duration.ofSeconds(5));
    s.f.resolve(s.probe);
    assertThat(s.call()).isEqualTo(new Waiting(WAKE_EVENTS, s.t0.plusSeconds(300), null));
    assertThat(held(s.evaluation())).containsExactly(false, true);
    assertThat(details(s.evaluation()))
        .containsExactly(
            "1 alert of the incident is still firing",
            "No alert of Synthetic USSD sessions is firing");
    assertThat(s.verification().windowStartedAt()).isNull();

    CLOCK.advance(Duration.ofSeconds(10));
    Instant t1 = CLOCK.instant();
    s.f.resolve(s.zabbix);
    assertThat(s.call()).isEqualTo(new Waiting(WAKE_EVENTS, t1.plusSeconds(60), null));
    assertThat(held(s.evaluation())).containsExactly(true, true);
    assertThat(s.verification().windowStartedAt()).isEqualTo(t1);

    CLOCK.set(t1.plusSeconds(59));
    assertThat(s.call()).isEqualTo(new Waiting(WAKE_EVENTS, t1.plusSeconds(60), null));
    assertThat(s.verification().state()).isEqualTo(VerificationState.OBSERVING);

    CLOCK.set(t1.plusSeconds(60));
    StepAnswer answer = s.call();
    RecoveryVerificationView verification = s.verification();
    assertThat(answer).isEqualTo(new Completed(new Key("recovered"), ref(verification)));
    assertThat(verification.state()).isEqualTo(VerificationState.RECOVERED);
    assertThat(verification.decidedAt()).isEqualTo(t1.plusSeconds(60));
    JsonNode evaluation = s.evaluation();
    assertThat(held(evaluation)).containsExactly(true, true);
    assertThat(evaluation.get("sources_healthy").booleanValue()).isTrue();
    assertThat(evaluation.get("unhealthy_source_ids")).isEmpty();

    List<EvidenceView> evidence = s.evidence();
    assertThat(evidence).hasSize(1);
    EvidenceView entry = evidence.getFirst();
    assertThat(entry.producerRecordId()).isEqualTo(verification.id());
    assertThat(entry.summary())
        .isEqualTo(
            "Recovered: 'Subscriber DB services recovered' held for 60 s with every monitoring"
                + " source healthy.");
    assertThat(entry.sourceSystem()).isEqualTo(SOURCES);
    assertThat(entry.collectedAt()).isEqualTo(t1.plusSeconds(60));
    String decided = t1.plusSeconds(60).toString();
    String expected =
        "{\"definition_snapshot\": "
            + snapshot()
            + ", \"window_started_at\": \""
            + t1
            + "\", \"decided_at\": \""
            + decided
            + "\", \"last_evaluation\": {\"at\": \""
            + decided
            + "\", \"conditions\": [{\"condition\": {\"kind\": \"incident_alerts_resolved\"},"
            + " \"held\": true, \"detail\": \"Every alert of the incident is resolved at its"
            + " source\", \"firing_count\": 0}, {\"condition\": {\"kind\":"
            + " \"component_no_firing_alert\", \"component_key\": \"ussd-probe\","
            + " \"alert_types\": []}, \"held\": true, \"detail\": \"No alert of Synthetic USSD"
            + " sessions is firing\", \"firing_count\": 0}], \"sources_healthy\": true,"
            + " \"unhealthy_source_ids\": []}}";
    assertThat(RecoveryFixture.JSON.readTree(entry.contentJson()))
        .isEqualTo(RecoveryFixture.JSON.readTree(expected));

    IncidentView incident = s.incident();
    assertThat(incident.state()).isEqualTo(IncidentState.RESOLVED);
    assertThat(incident.resolvedByVerificationId()).isEqualTo(verification.id());
  }

  @Test
  @DisplayName("PM-M15-02 the first call starts one observing verification of the definition")
  void firstCall() {
    Story s = story();
    s.first();
    RecoveryVerificationView verification = s.verification();
    assertThat(verification.state()).isEqualTo(VerificationState.OBSERVING);
    assertThat(verification.recoveryCheckKey()).isEqualTo(CHECK);
    assertThat(RecoveryFixture.JSON.readTree(verification.definitionSnapshotJson()))
        .isEqualTo(RecoveryFixture.JSON.readTree(snapshot()));
    assertThat(verification.startedAt()).isEqualTo(s.t0);
    assertThat(verification.maxWaitUntil()).isEqualTo(s.t0.plusSeconds(300));
  }

  @Test
  @DisplayName("PM-M15-03 the recovered verification's evidence entry and the resolve request")
  void evidenceAndResolve() {
    Story s = story();
    s.recover();
    List<String> timeline =
        s.f.incidents.timeline(s.f.tenant, s.incident).stream().map(TimelineView::text).toList();
    assertThat(timeline)
        .contains(
            "Evidence: Recovered: 'Subscriber DB services recovered' held for 60 s with every"
                + " monitoring source healthy.",
            "Resolved: recovery verified.");
    assertThat(s.evidence()).hasSize(1);
    assertThat(s.incident().state()).isEqualTo(IncidentState.RESOLVED);
  }

  @Test
  @DisplayName("PM-S-07 the outputs recovered and not_recovered with their result reference")
  void outputs() {
    Story recovered = story();
    recovered.recover();
    RecoveryVerificationView done = recovered.verification();
    assertThat(recovered.call()).isEqualTo(new Completed(new Key("recovered"), ref(done)));

    Story notRecovered = story();
    StepAnswer answer = notRecovered.notRecover();
    assertThat(answer)
        .isEqualTo(new Completed(new Key("not_recovered"), ref(notRecovered.verification())));
  }

  @Test
  @DisplayName("DM-E-62 the recovery verification row after the first call and the decision")
  void verificationRow() {
    Story s = story();
    s.first();
    RecoveryVerificationView first = s.verification();
    assertThat(first.id()).isNotNull();
    assertThat(first.incidentId()).isEqualTo(s.incident);
    assertThat(first.runId()).isEqualTo(s.run);
    assertThat(first.stepExecutionId()).isEqualTo(s.step);
    assertThat(first.recoveryCheckKey()).isEqualTo(CHECK);
    assertThat(first.state()).isEqualTo(VerificationState.OBSERVING);
    assertThat(first.startedAt()).isEqualTo(s.t0);
    assertThat(first.maxWaitUntil()).isEqualTo(s.t0.plusSeconds(300));
    assertThat(first.windowStartedAt()).isNull();
    assertThat(first.decidedAt()).isNull();
    assertThat(s.evaluation().get("at").stringValue()).isEqualTo(s.t0.toString());

    Instant t1 = s.resolveBoth();
    CLOCK.set(t1.plusSeconds(60));
    s.call();
    RecoveryVerificationView decided = s.verification();
    assertThat(decided.id()).isEqualTo(first.id());
    assertThat(decided.state()).isEqualTo(VerificationState.RECOVERED);
    assertThat(decided.startedAt()).isEqualTo(s.t0);
    assertThat(decided.maxWaitUntil()).isEqualTo(s.t0.plusSeconds(300));
    assertThat(decided.windowStartedAt()).isEqualTo(t1);
    assertThat(decided.decidedAt()).isEqualTo(t1.plusSeconds(60));
    assertThat(decided.definitionSnapshotJson()).isEqualTo(first.definitionSnapshotJson());
    assertThat(s.evaluation().get("at").stringValue()).isEqualTo(t1.plusSeconds(60).toString());
  }

  @Test
  @DisplayName("DM-SM-13 observing, recovered, not_recovered and cancelled")
  void stateMachine() {
    Story recovered = story();
    recovered.first();
    assertThat(recovered.verification().state()).isEqualTo(VerificationState.OBSERVING);
    Instant t1 = recovered.resolveBoth();
    CLOCK.set(t1.plusSeconds(60));
    recovered.call();
    assertThat(recovered.verification().state()).isEqualTo(VerificationState.RECOVERED);

    Story notRecovered = story();
    notRecovered.notRecover();
    assertThat(notRecovered.verification().state()).isEqualTo(VerificationState.NOT_RECOVERED);

    Story cancelled = story();
    cancelled.first();
    StepCancel cancel =
        new StepCancel(
            cancelled.f.tenant,
            cancelled.run,
            cancelled.incident,
            cancelled.step,
            new Key("s-recover"),
            StepCall.StepType.RECOVERY_CHECK);
    cancelled.f.handler.cancel(cancel);
    RecoveryVerificationView view = cancelled.verification();
    assertThat(view.state()).isEqualTo(VerificationState.CANCELLED);
    assertThat(view.decidedAt()).isNull();
    assertThat(cancelled.evidence()).isEmpty();
    assertThat(cancelled.events()).isEmpty();
    cancelled.f.handler.cancel(cancel);
    assertThat(cancelled.verification()).isEqualTo(view);
    assertThatThrownBy(cancelled::call)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("recovery verification " + view.id() + " is cancelled");
  }

  @Test
  @DisplayName("DM-R-35 a component condition counts the alerts matched to the component")
  void componentCondition() {
    RecoveryPart demo = RecoveryFixture.demoPart();
    List<DefinitionPart> definitions = new ArrayList<>(demo.definitions());
    definitions.add(
        new DefinitionPart(
            "rc-report-export",
            "Report export recovered",
            RecoveryFixture.JSON.readTree(
                "[{\"kind\": \"incident_alerts_resolved\"}, {\"kind\":"
                    + " \"component_no_firing_alert\", \"component_key\": \"report-export\","
                    + " \"alert_types\": [\"disk_full\"]}]"),
            "60",
            "false"));
    RecoveryFixture f = servers.tenant().withDefinitions(new RecoveryPart(definitions));
    awaitHealthy(f);
    AlertView disk = f.zabbix("srv-db-01", "lab-db-01", DISK_FULL);
    UUID incident = f.open(disk);
    Story s = new Story(f, disk, null, incident);
    s.key = "rc-report-export";
    s.first();
    assertThat(details(s.evaluation()).get(1))
        .isEqualTo("No Disk full alert of Report export job is firing");

    AlertView job = f.zabbix("report-export", "lab-rpt-01", "Report export job failed");
    assertThat(f.incidents.members(f.tenant, incident))
        .extracting(MembershipView::alertId)
        .doesNotContain(job.id());
    s.call();
    assertThat(held(s.evaluation()).get(1)).isTrue();
    assertThat(details(s.evaluation()).get(1))
        .isEqualTo("No Disk full alert of Report export job is firing");

    AlertView reportDisk = f.zabbix("report-export", "lab-rpt-01", DISK_FULL);
    assertThat(f.incidents.members(f.tenant, incident))
        .extracting(MembershipView::alertId)
        .doesNotContain(reportDisk.id());
    s.call();
    assertThat(held(s.evaluation()).get(1)).isFalse();
    assertThat(details(s.evaluation()).get(1))
        .isEqualTo("1 Disk full alert of Report export job is firing");
    assertThat(s.evaluation().get("conditions").get(1).get("firing_count").longValue())
        .isEqualTo(1);

    CLOCK.advance(Duration.ofSeconds(5));
    f.resolve(reportDisk);
    s.call();
    assertThat(held(s.evaluation()).get(1)).isTrue();
    assertThat(details(s.evaluation()).get(1))
        .isEqualTo("No Disk full alert of Report export job is firing");
  }

  @Test
  @DisplayName("DM-R-37 the window is cleared when a condition stops holding")
  void windowCleared() {
    Story s = story();
    s.first();
    Instant t1 = s.resolveBoth();
    assertThat(s.verification().windowStartedAt()).isEqualTo(t1);

    CLOCK.advance(Duration.ofSeconds(10));
    AlertView again = s.f.zabbix("srv-db-01", "lab-db-01", DISK_FULL);
    assertThat(s.f.incidents.incidentOfAlert(s.f.tenant, again.id())).contains(s.incident);
    s.call();
    assertThat(held(s.evaluation())).containsExactly(false, true);
    assertThat(details(s.evaluation()).getFirst())
        .isEqualTo("1 alert of the incident is still firing");
    assertThat(s.verification().windowStartedAt()).isNull();

    CLOCK.advance(Duration.ofSeconds(10));
    Instant t2 = CLOCK.instant();
    s.f.resolve(again);
    assertThat(s.call()).isEqualTo(new Waiting(WAKE_EVENTS, t2.plusSeconds(60), null));
    assertThat(s.verification().windowStartedAt()).isEqualTo(t2);
  }

  @Test
  @DisplayName("DM-R-36 a source that is not healthy clears the window; healthy again, it restarts")
  void guardFailedTest() {
    Story s = story();
    s.first();
    Instant t1 = s.resolveBoth();
    assertThat(s.verification().windowStartedAt()).isEqualTo(t1);

    SourceView alertmanager =
        s.f.intake.sources(s.f.tenant).stream()
            .filter(source -> source.sourceKey().equals(IntakeFixture.ALERTMANAGER))
            .findFirst()
            .orElseThrow();
    servers.wiremock.stubFor(
        get(urlEqualTo("/api/v2/status")).willReturn(aResponse().withStatus(500)));
    try {
      CLOCK.advance(Duration.ofSeconds(5));
      s.f.intake.testSource(s.f.tenant, IntakeFixture.ALERTMANAGER);
    } finally {
      IntakeFixture.stub(servers.wiremock);
    }
    s.call();
    JsonNode evaluation = s.evaluation();
    assertThat(held(evaluation)).containsExactly(true, true);
    assertThat(evaluation.get("sources_healthy").booleanValue()).isFalse();
    assertThat(evaluation.get("unhealthy_source_ids")).hasSize(1);
    assertThat(evaluation.get("unhealthy_source_ids").get(0).stringValue())
        .isEqualTo(alertmanager.id().toString());
    assertThat(s.verification().windowStartedAt()).isNull();

    s.f.intake.testSource(s.f.tenant, IntakeFixture.ALERTMANAGER);
    CLOCK.advance(Duration.ofSeconds(5));
    Instant t2 = CLOCK.instant();
    s.call();
    assertThat(s.evaluation().get("sources_healthy").booleanValue()).isTrue();
    assertThat(s.verification().windowStartedAt()).isEqualTo(t2);
  }

  @Test
  @DisplayName("DM-R-36 a source not healthy during the window restarts it, though healthy now")
  void guardStaleSources() {
    Story s = story(servers.tenant(IntakeFixture.demoPart()).withDemoDefinitions());
    s.first();
    Instant t1 = s.resolveBoth();
    assertThat(Duration.between(s.t0, t1)).isLessThan(Duration.ofSeconds(179));
    assertThat(s.verification().windowStartedAt()).isEqualTo(t1);

    Instant later = CLOCK.advance(Duration.ofSeconds(121));
    IntakeFixture.await(
        () ->
            s.f.intake.sources(s.f.tenant).stream()
                .filter(SourceView::active)
                .allMatch(
                    source ->
                        source.health() == Health.HEALTHY
                            && later.equals(source.healthChangedAt())));
    assertThat(s.call()).isInstanceOf(Waiting.class);
    assertThat(s.evaluation().get("sources_healthy").booleanValue()).isTrue();
    assertThat(s.verification().windowStartedAt()).isEqualTo(later);
    assertThat(s.verification().state()).isEqualTo(VerificationState.OBSERVING);
  }

  @Test
  @DisplayName("SL-A2-04 not_recovered at the maximum wait; the incident stays open")
  void notRecovered() {
    Story s = story();
    StepAnswer answer = s.notRecover();
    RecoveryVerificationView verification = s.verification();
    assertThat(answer).isEqualTo(new Completed(new Key("not_recovered"), ref(verification)));
    assertThat(verification.decidedAt()).isEqualTo(s.t0.plusSeconds(300));
    List<EvidenceView> evidence = s.evidence();
    assertThat(evidence).hasSize(1);
    assertThat(evidence.getFirst().summary())
        .isEqualTo("Not recovered within 5 min: 1 alert of the incident is still firing.");
    assertThat(s.incident().state()).isEqualTo(IncidentState.OPEN);
  }

  @Test
  @DisplayName("DM-S-07 max_wait from 60 to 86400 seconds; only Recovery check steps")
  void parameters() {
    Story s = story();
    for (Object maxWait : List.of(59, "\"300\"", "300.5", 86_401)) {
      assertThatThrownBy(
              () -> s.f.handler.start(s.f.call(s.run, s.incident, s.step, maxWait, 1, CHECK)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("recovery check step parameters: max_wait");
    }
    assertThatThrownBy(() -> s.f.handler.start(s.f.call(s.run, s.incident, s.step, "{}", 1, CHECK)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("recovery check step parameters: max_wait");
    StepCall wait =
        new StepCall(
            s.f.tenant,
            s.run,
            s.incident,
            s.step,
            new Key("s-wait"),
            StepCall.StepType.WAIT,
            "{\"max_wait\": 300}",
            1,
            null,
            new StepCall.WakeReason("start"),
            null,
            List.of());
    assertThatThrownBy(() -> s.f.handler.start(wait))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("recovery check step parameters: max_wait");
    assertThat(recovery.verifications(s.f.tenant, s.incident)).isEmpty();
    s.first();
    assertThat(s.verification().maxWaitUntil()).isEqualTo(s.t0.plusSeconds(300));
  }

  @Test
  @DisplayName("DM-R-38 max_wait must be greater than the observation window of the definition")
  void maxWaitAboveWindow() {
    Story s = story();
    assertThatThrownBy(() -> s.f.handler.start(s.f.call(s.run, s.incident, s.step, 60, 1, CHECK)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(
            "recovery check step parameters: max_wait must be greater than the observation window"
                + " of rc-subscriber-db");
    assertThat(recovery.verifications(s.f.tenant, s.incident)).isEmpty();
    s.t0 = CLOCK.instant();
    s.call(61);
    assertThat(s.verification().maxWaitUntil()).isEqualTo(s.t0.plusSeconds(61));
  }

  @Test
  @DisplayName("DM-R-34 the key in the call, else the active default definition")
  void definitionUsed() {
    Story s = story();
    s.key = null;
    s.first();
    RecoveryVerificationView verification = s.verification();
    assertThat(verification.recoveryCheckKey()).isEqualTo("rc-default");
    assertThat(s.evaluation().get("conditions")).hasSize(1);

    Story unknown = story();
    unknown.key = "rc-nowhere";
    assertThatThrownBy(unknown::first)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("recovery check rc-nowhere not found");
    assertThat(recovery.verifications(unknown.f.tenant, unknown.incident)).isEmpty();
  }

  @Test
  @DisplayName("DM-R-64 one verification per step execution, continued when evaluated again")
  void oneVerificationPerStep() {
    Story observing = story();
    observing.first();
    UUID id = observing.verification().id();
    observing.call();
    assertThat(observing.verification().id()).isEqualTo(id);

    Story recovered = story();
    recovered.recover();
    RecoveryVerificationView done = recovered.verification();
    CLOCK.advance(Duration.ofSeconds(30));
    assertThat(recovered.call()).isEqualTo(new Completed(new Key("recovered"), ref(done)));
    assertThat(recovered.verification()).isEqualTo(done);
    assertThat(recovered.evidence()).hasSize(1);
    assertThat(recovered.events()).hasSize(1);

    Story notRecovered = story();
    notRecovered.notRecover();
    RecoveryVerificationView failed = notRecovered.verification();
    CLOCK.advance(Duration.ofSeconds(30));
    assertThat(notRecovered.call()).isEqualTo(new Completed(new Key("not_recovered"), ref(failed)));
    assertThat(notRecovered.verification()).isEqualTo(failed);
    assertThat(notRecovered.evidence()).hasSize(1);
    assertThat(notRecovered.events()).hasSize(1);
  }

  @Test
  @DisplayName("DM-E08-R3 one recovery_verified event per decision, written by vigil-recovery")
  void auditEvent() {
    Story s = story();
    Instant t1 = s.recover();
    RecoveryVerificationView verification = s.verification();
    List<AuditEventView> events = s.events();
    assertThat(events).hasSize(1);
    AuditEventView event = events.getFirst();
    assertThat(event.actor()).isEqualTo(RecoveryStep.RECOVERY);
    assertThat(event.occurredAt()).isEqualTo(t1.plusSeconds(60));
    assertThat(event.subjectType()).isEqualTo("RecoveryVerification");
    assertThat(event.subjectId()).isEqualTo(verification.id());
    assertThat(event.incidentId()).isEqualTo(s.incident);
    assertThat(event.summary()).isEqualTo("Recovered: 'Subscriber DB services recovered'.");

    Story failed = story();
    failed.notRecover();
    AuditEventView second = failed.events().getFirst();
    assertThat(failed.events()).hasSize(1);
    assertThat(second.subjectId()).isEqualTo(failed.verification().id());
    assertThat(second.summary()).isEqualTo("Not recovered: 'Subscriber DB services recovered'.");
  }

  @Test
  @DisplayName("DM-E08-R1 recovery_verified details: the outcome")
  void auditDetails() {
    Story recovered = story();
    recovered.recover();
    assertThat(RecoveryFixture.JSON.readTree(recovered.events().getFirst().detailsJson()))
        .isEqualTo(RecoveryFixture.JSON.readTree("{\"outcome\": \"recovered\"}"));
    Story failed = story();
    failed.notRecover();
    assertThat(RecoveryFixture.JSON.readTree(failed.events().getFirst().detailsJson()))
        .isEqualTo(RecoveryFixture.JSON.readTree("{\"outcome\": \"not_recovered\"}"));
  }

  @Test
  @DisplayName("PM-M15-04 a definition with its conditions, window and requirement texts")
  void definition() {
    RecoveryFixture f = tenant();
    RecoveryCheckView view = recovery.definition(f.tenant, CHECK).orElseThrow();
    assertThat(view.name()).isEqualTo(CHECK_NAME);
    assertThat(view.conditions())
        .containsExactly(
            new RecoveryCondition(ConditionKind.INCIDENT_ALERTS_RESOLVED, null, List.of()),
            new RecoveryCondition(
                ConditionKind.COMPONENT_NO_FIRING_ALERT, "ussd-probe", List.of()));
    assertThat(view.observationWindow()).isEqualTo(Duration.ofSeconds(60));
    assertThat(view.isDefault()).isFalse();
    assertThat(view.active()).isTrue();
    assertThat(view.requirementTexts())
        .containsExactly(
            "Every alert of the incident is resolved at its source",
            "No alert of Synthetic USSD sessions is firing");
    assertThat(recovery.definition(f.tenant, "rc-nowhere")).isEmpty();
  }

  @Test
  @DisplayName("PM-M21-01 the dashboard metrics after the storyline")
  void dashboard() {
    Story s = story();
    Instant t1 = s.recover();
    DashboardView dashboard =
        servers.app.context().getBean(ReportingService.class).dashboard(s.f.tenant);
    IncidentView incident = s.incident();
    long millis = Duration.between(incident.firstAlertFiredAt(), t1.plusSeconds(60)).toMillis();
    BigDecimal recover =
        BigDecimal.valueOf(millis).divide(BigDecimal.valueOf(60_000), 1, RoundingMode.HALF_UP);
    assertThat(metric(dashboard, MetricKey.TIME_TO_ACKNOWLEDGE).value()).isNull();
    assertThat(metric(dashboard, MetricKey.TIME_TO_RECOVER).value()).isEqualTo(recover);
    assertThat(s.f.intake.alertCount(s.f.tenant)).isEqualTo(2);
    assertThat(metric(dashboard, MetricKey.ALERTS_SUPPRESSED).value())
        .isEqualTo(BigDecimal.valueOf(1));
    assertThat(metric(dashboard, MetricKey.INCIDENTS_MERGED).value())
        .isEqualTo(BigDecimal.valueOf(1));
    assertThat(dashboard.metrics())
        .allSatisfy(
            m -> {
              assertThat(m.baselineValue()).isNull();
              assertThat(m.baselineLabel()).isNull();
            });
    assertThat(dashboard.liveIncidents()).isEqualTo(1);
    assertThat(dashboard.pastIncidents()).isZero();
    assertThat(dashboard.recordLabels()).isEmpty();
  }

  private static MetricView metric(DashboardView dashboard, MetricKey key) {
    return dashboard.metrics().stream().filter(m -> m.key() == key).findFirst().orElseThrow();
  }
}
