package vigil.incidents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
import vigil.incidents.api.AcknowledgementView;
import vigil.incidents.api.IncidentAcknowledged;
import vigil.incidents.api.IncidentChanged;
import vigil.incidents.api.IncidentService;
import vigil.incidents.api.IncidentState;
import vigil.incidents.api.IncidentView;
import vigil.incidents.api.TimelineEntryType;
import vigil.incidents.api.TimelineView;
import vigil.shared.Ids;
import vigil.shared.VigilError;
import vigil.testsupport.MutableClock;
import vigil.testsupport.VigilDatabase;
import vigil.testsupport.VigilTestApp;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AcknowledgementIT {

  private static final MutableClock CLOCK = new MutableClock(Instant.parse("2026-10-22T12:00:00Z"));

  private VigilTestApp app;
  private JdbcClient postgres;
  private IncidentService incidents;
  private IncidentFixture f;
  private UUID incident;
  private AcknowledgementView first;
  private Instant acknowledgedAt;
  private List<Object> acknowledgeEvents;

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
    f = IncidentFixture.create(app, "Tenant K", "k.incidents.test");
  }

  @AfterAll
  void stop() {
    app.close();
  }

  /** A new open incident whose notified teams are db-ops and noc-l1. */
  private UUID openIncident() {
    UUID id = f.create(f.alert("disk_full", "Disk full", "srv-db-01", CLOCK.instant()));
    f.open(id);
    f.notified(id, "db-ops");
    f.notified(id, "noc-l1");
    return id;
  }

  private VigilError refusal(UUID incidentId, String username) {
    return catchThrowableOfType(
        VigilError.class, () -> incidents.acknowledge(f.tenant, incidentId, f.userId(username)));
  }

  @Test
  @Order(1)
  @DisplayName("DM-E35-R1 the checks of an acknowledgement, each refusal with its message")
  void refusals() {
    UUID closed = openIncident();
    f.resolve(closed);
    CLOCK.advance(Duration.ofSeconds(301));
    IncidentFixture.await(
        () -> incidents.incident(f.tenant, closed).orElseThrow().state() == IncidentState.CLOSED);
    UUID resolved = openIncident();
    f.resolve(resolved);
    incident = openIncident();

    VigilError forbidden = refusal(incident, "viewer");
    assertThat(forbidden.code()).isEqualTo(VigilError.Code.FORBIDDEN);
    assertThat(forbidden.message()).isNull();

    VigilError notOpen = refusal(resolved, "dbops-engineer");
    assertThat(notOpen.code()).isEqualTo(VigilError.Code.REFUSED);
    assertThat(notOpen.message())
        .isEqualTo("This incident is resolved. It does not need acknowledgement.");
    assertThat(notOpen.details()).isEqualTo(Map.of("refusal", "not_open", "state", "resolved"));
    VigilError notOpenClosed = refusal(closed, "dbops-engineer");
    assertThat(notOpenClosed.message())
        .isEqualTo("This incident is closed. It does not need acknowledgement.");
    assertThat(notOpenClosed.details()).isEqualTo(Map.of("refusal", "not_open", "state", "closed"));

    VigilError notNotified = refusal(incident, "outsider");
    assertThat(notNotified.code()).isEqualTo(VigilError.Code.REFUSED);
    assertThat(notNotified.message())
        .isEqualTo("You are not in a team that was notified about this incident.");
    assertThat(notNotified.details()).isEqualTo(Map.of("refusal", "not_notified"));

    int before = IncidentFixture.Events.RECEIVED.size();
    acknowledgedAt = CLOCK.instant();
    first = incidents.acknowledge(f.tenant, incident, f.userId("dbops-engineer"));
    acknowledgeEvents =
        List.copyOf(
            IncidentFixture.Events.RECEIVED.subList(
                before, IncidentFixture.Events.RECEIVED.size()));
    CLOCK.advance(Duration.ofSeconds(7));

    VigilError already = refusal(incident, "noc-l1");
    assertThat(already.code()).isEqualTo(VigilError.Code.REFUSED);
    assertThat(already.message())
        .isEqualTo(
            "Already acknowledged by DB Ops Engineer at "
                + IncidentTexts.outbound(acknowledgedAt)
                + ".");
    assertThat(already.message()).contains("2026-10-22 12:05:01 UTC");
    assertThat(already.details())
        .isEqualTo(
            Map.of(
                "refusal",
                "already_acknowledged",
                "user_id",
                f.userId("dbops-engineer").toString(),
                "at",
                "2026-10-22T12:05:01.000Z"));
    assertThat(incidents.acknowledgements(f.tenant, incident)).hasSize(1);
  }

  @Test
  @Order(2)
  @DisplayName("DM-E-35 the acknowledgement record")
  void record() {
    assertThat(first.incidentId()).isEqualTo(incident);
    assertThat(first.userId()).isEqualTo(f.userId("dbops-engineer"));
    assertThat(first.teamKey()).isEqualTo("db-ops");
    assertThat(first.episode()).isEqualTo(1);
    assertThat(first.at()).isEqualTo(acknowledgedAt);
    assertThat(incidents.acknowledgement(f.tenant, incident)).contains(first);
    IncidentView view = incidents.incident(f.tenant, incident).orElseThrow();
    assertThat(view.acknowledgedAt()).isEqualTo(acknowledgedAt);
    assertThat(view.acknowledgedBy()).isEqualTo(f.userId("dbops-engineer"));
  }

  @Test
  @Order(3)
  @DisplayName("PM-M09-04 the acknowledgement recorded with user and time; a new episode's")
  void recordedWithUser() {
    TimelineView entry =
        incidents.timeline(f.tenant, incident).stream()
            .filter(e -> e.entryType() == TimelineEntryType.ACKNOWLEDGED)
            .findFirst()
            .orElseThrow();
    assertThat(entry.text()).isEqualTo("Acknowledged by DB Ops Engineer.");
    assertThat(entry.actor().userId()).isEqualTo(f.userId("dbops-engineer"));
    assertThat(entry.refType()).isEqualTo("Acknowledgement");
    assertThat(entry.refId()).isEqualTo(first.id());
    Map<String, Object> audit =
        postgres
            .sql(
                "SELECT actor_kind, actor_user_id, subject_type, subject_id, incident_id, summary"
                    + " FROM audit.audit_event WHERE tenant_id = :t"
                    + " AND event_type = 'incident_acknowledged'")
            .param("t", f.tenant.value())
            .query()
            .singleRow();
    assertThat(audit.get("actor_kind")).isEqualTo("user");
    assertThat(audit.get("actor_user_id")).isEqualTo(f.userId("dbops-engineer"));
    assertThat(audit.get("subject_type")).isEqualTo("Acknowledgement");
    assertThat(audit.get("subject_id")).isEqualTo(first.id());
    assertThat(audit.get("incident_id")).isEqualTo(incident);
    assertThat(audit.get("summary")).isEqualTo("Acknowledged by DB Ops Engineer.");

    f.resolve(incident);
    incidents.reopen(
        f.tenant,
        IncidentFixture.CORRELATION,
        incident,
        Ids.newId(),
        f.alert("disk_full", "Disk full", "srv-db-01", CLOCK.instant()),
        IncidentFixture.reopened(Ids.newId()));
    assertThat(incidents.acknowledgement(f.tenant, incident)).isEmpty();
    AcknowledgementView second = incidents.acknowledge(f.tenant, incident, f.userId("noc-l1"));
    assertThat(second.episode()).isEqualTo(2);
    assertThat(second.teamKey()).isEqualTo("noc-l1");
    assertThat(incidents.acknowledgements(f.tenant, incident)).containsExactly(first, second);
    assertThat(incidents.acknowledgement(f.tenant, incident)).contains(second);
  }

  @Test
  @Order(4)
  @DisplayName("AR-E-06 IncidentAcknowledged with the episode, then IncidentChanged")
  void acknowledgedEvent() {
    assertThat(acknowledgeEvents)
        .containsExactly(
            new IncidentAcknowledged(f.tenant, incident, 1),
            new IncidentChanged(f.tenant, incident));
    assertThat(IncidentFixture.Events.of(incident))
        .contains(new IncidentAcknowledged(f.tenant, incident, 2));
  }

  @Test
  @Order(5)
  @DisplayName("DM-R-63 the acknowledgement is checked and stored atomically")
  void atomic() throws InterruptedException, ExecutionException {
    UUID target = openIncident();
    List<UUID> users = List.of(f.userId("dbops-engineer"), f.userId("noc-l1"));
    ExecutorService pool = Executors.newFixedThreadPool(8);
    CountDownLatch go = new CountDownLatch(1);
    List<Future<Object>> results = new ArrayList<>();
    try {
      for (int i = 0; i < 8; i++) {
        UUID user = users.get(i % 2);
        results.add(
            pool.submit(
                () -> {
                  go.await();
                  try {
                    return incidents.acknowledge(f.tenant, target, user);
                  } catch (VigilError e) {
                    return e;
                  }
                }));
      }
      go.countDown();
      List<Object> outcomes = new ArrayList<>();
      for (Future<Object> result : results) {
        outcomes.add(result.get());
      }
      assertThat(outcomes.stream().filter(AcknowledgementView.class::isInstance)).hasSize(1);
      assertThat(outcomes.stream().filter(VigilError.class::isInstance))
          .hasSize(7)
          .allSatisfy(
              error ->
                  assertThat(((VigilError) error).details().get("refusal"))
                      .isEqualTo("already_acknowledged"));
    } finally {
      pool.shutdownNow();
    }
    assertThat(incidents.acknowledgements(f.tenant, target)).hasSize(1);
  }

  @Test
  @Order(6)
  @DisplayName("AR-R-12 one acknowledgement row per episode: row lock and unique constraint")
  void lockAndConstraint() {
    assertThat(
            IncidentFixture.count(
                postgres,
                "SELECT count(*) FROM (SELECT incident_id, episode FROM incidents.acknowledgement"
                    + " WHERE tenant_id = :t GROUP BY incident_id, episode HAVING count(*) > 1) d",
                f.tenant))
        .isZero();
    assertThat(
            postgres
                .sql(
                    "SELECT count(*) FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid"
                        + " JOIN pg_namespace n ON n.oid = t.relnamespace WHERE n.nspname ="
                        + " 'incidents' AND t.relname = 'acknowledgement' AND c.contype = 'u'")
                .query(Long.class)
                .single())
        .isEqualTo(1);
  }
}
