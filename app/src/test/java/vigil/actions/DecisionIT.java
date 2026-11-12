package vigil.actions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.EventListener;
import vigil.actions.api.ActionsService;
import vigil.actions.api.ActionsStepHandler;
import vigil.actions.api.ApprovalDecided;
import vigil.actions.api.ApprovalRequestView;
import vigil.actions.api.Decision;
import vigil.actions.api.RequestState;
import vigil.notifications.NotificationsApp;
import vigil.shared.Ids;
import vigil.shared.StepCall;
import vigil.shared.VigilError;
import vigil.testsupport.MutableClock;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DecisionIT {

  static final Instant T0 = Instant.parse("2026-11-12T09:00:00Z");
  static final MutableClock CLOCK = new MutableClock(T0);
  static final List<ApprovalDecided> EVENTS = new CopyOnWriteArrayList<>();

  /** The test clock and a listener that records {@link ApprovalDecided}. */
  static class TestBeans {
    @Bean
    @Primary
    Clock testClock() {
      return CLOCK;
    }

    @Bean
    Recorder approvalRecorder() {
      return new Recorder();
    }
  }

  /** Records every {@link ApprovalDecided}. */
  static class Recorder {
    @EventListener
    public void on(ApprovalDecided event) {
      EVENTS.add(event);
    }
  }

  private NotificationsApp servers;
  private ActionsStepHandler step;
  private ActionsService actions;
  private ActionsFixture demo;
  private ActionsFixture preauthorized;

  @BeforeAll
  void start() {
    servers = NotificationsApp.start(TestBeans.class);
    step = servers.app.context().getBean(ActionsStepHandler.class);
    actions = servers.app.context().getBean(ActionsService.class);
    demo = ActionsFixture.create(servers, "Decide");
    preauthorized =
        ActionsFixture.create(
            servers,
            "Decide preauthorized",
            ActionsFixture.part(1, ActionsFixture.policy(null, "preauthorized")));
  }

  @AfterAll
  void stop() {
    servers.close();
  }

  @BeforeEach
  void reset() {
    servers.reset();
    CLOCK.set(T0);
    EVENTS.clear();
  }

  /** A request of a new run's Approval step, with these earlier approvers. */
  private record Request(StepCall call, ApprovalRequestView view) {}

  private Request request(ActionsFixture f, List<UUID> earlier) {
    UUID incident = f.openIncident();
    f.match(incident);
    StepCall call = f.approval(Ids.newId(), incident, ActionsFixture.APPROVAL, earlier);
    step.start(call);
    Map<String, Object> row = f.rowsOfRun("approval_request", call.runId()).getFirst();
    return new Request(call, view(f, (UUID) row.get("id")));
  }

  private ApprovalRequestView view(ActionsFixture f, UUID id) {
    Map<String, Object> row =
        f.postgres()
            .sql(
                "SELECT state, snapshot_hash, decided_at FROM actions.approval_request WHERE id = :id")
            .param("id", id)
            .query()
            .singleRow();
    return new ApprovalRequestView(
        id,
        null,
        null,
        null,
        null,
        1,
        null,
        List.of(),
        List.of(),
        null,
        (String) row.get("snapshot_hash"),
        null,
        RequestState.fromKey((String) row.get("state")).orElseThrow(),
        null,
        row.get("decided_at") == null ? null : ((Timestamp) row.get("decided_at")).toInstant(),
        null,
        null);
  }

  private ApprovalRequestView decide(
      ActionsFixture f, Request r, String user, Decision decision, String hash) {
    return actions.decide(f.tenant, r.view().id(), f.userId(user), decision, hash);
  }

  private static void refused(
      Runnable decide, VigilError.Code code, String message, Map<String, Object> details) {
    assertThatThrownBy(decide::run)
        .isInstanceOfSatisfying(
            VigilError.class,
            e -> {
              assertThat(e.code()).isEqualTo(code);
              assertThat(e.message()).isEqualTo(message);
              assertThat(e.details()).isEqualTo(details);
            });
  }

  @Test
  @DisplayName("DM-E59-R1 each refusal of a decision with its message and details, in order")
  void refusals() {
    Request r = request(demo, List.of());
    String hash = r.view().snapshotHash();
    refused(
        () -> decide(demo, r, "presenter", Decision.APPROVE, hash),
        VigilError.Code.FORBIDDEN,
        null,
        Map.of());
    refused(
        () ->
            actions.decide(
                demo.tenant, Ids.newId(), demo.userId("dbops-lead"), Decision.APPROVE, hash),
        VigilError.Code.NOT_FOUND,
        null,
        Map.of());

    Request cancelled = request(demo, List.of());
    step.cancel(demo.cancel(cancelled.call()));
    refused(
        () -> decide(demo, cancelled, "noc-l1", Decision.APPROVE, "wrong"),
        VigilError.Code.REFUSED,
        "This request was cancelled because the incident's workflow run ended.",
        Map.of("refusal", "cancelled"));

    Request pre = request(preauthorized, List.of());
    refused(
        () -> decide(preauthorized, pre, "dbops-lead", Decision.APPROVE, pre.view().snapshotHash()),
        VigilError.Code.REFUSED,
        "Preauthorized by policy",
        Map.of("refusal", "not_pending", "state", "preauthorized"));

    Request approved = request(demo, List.of());
    decide(demo, approved, "dbops-engineer", Decision.APPROVE, approved.view().snapshotHash());
    refused(
        () -> decide(demo, approved, "dbops-lead", Decision.REJECT, approved.view().snapshotHash()),
        VigilError.Code.REFUSED,
        "Already approved by Usman Tariq (DB Ops) at 2026-11-12 09:00:00 UTC.",
        Map.of(
            "refusal",
            "already_decided",
            "state",
            "approved",
            "user_id",
            demo.userId("dbops-engineer").toString(),
            "at",
            "2026-11-12T09:00:00.000Z"));

    Request expired = request(demo, List.of());
    CLOCK.set(T0.plusSeconds(900));
    refused(
        () -> decide(demo, expired, "noc-l1", Decision.APPROVE, "wrong"),
        VigilError.Code.REFUSED,
        "This request expired at 2026-11-12 09:15:00 UTC.",
        Map.of("refusal", "expired", "at", "2026-11-12T09:15:00.000Z"));
    assertThat(view(demo, expired.view().id()).state()).isEqualTo(RequestState.PENDING);
    CLOCK.set(T0);

    refused(
        () -> decide(demo, r, "noc-l1", Decision.APPROVE, "wrong"),
        VigilError.Code.REFUSED,
        "You are not in a team that can approve this action (Database Operations).",
        Map.of("refusal", "not_eligible"));

    Request second = request(demo, List.of(demo.userId("dbops-engineer")));
    refused(
        () -> decide(demo, second, "dbops-engineer", Decision.APPROVE, "wrong"),
        VigilError.Code.REFUSED,
        "You approved an earlier step of this run. A different approver is needed.",
        Map.of("refusal", "excluded"));

    refused(
        () -> decide(demo, r, "dbops-lead", Decision.APPROVE, hash.replace(hash.charAt(0), 'x')),
        VigilError.Code.REFUSED,
        "This request changed after you opened it. Reload the page to see the current request.",
        Map.of("refusal", "snapshot_changed"));
    assertThat(demo.rows("approval_decision"))
        .noneMatch(d -> d.get("request_id").equals(r.view().id()));
    assertThat(view(demo, r.view().id()).state()).isEqualTo(RequestState.PENDING);
  }

  @Test
  @DisplayName("PM-M14-05 approve and reject by a user who passes every check")
  void accepted() {
    Request a = request(demo, List.of());
    ApprovalRequestView approved =
        decide(demo, a, "dbops-engineer", Decision.APPROVE, a.view().snapshotHash());
    assertThat(approved.state()).isEqualTo(RequestState.APPROVED);
    assertThat(approved.decidedBy()).isEqualTo(demo.userId("dbops-engineer"));
    assertThat(approved.decidedAt()).isEqualTo(T0);
    Request r = request(demo, List.of(demo.userId("dbops-engineer")));
    ApprovalRequestView rejected =
        decide(demo, r, "dbops-lead", Decision.REJECT, r.view().snapshotHash());
    assertThat(rejected.state()).isEqualTo(RequestState.REJECTED);
    assertThat(rejected.decidedBy()).isEqualTo(demo.userId("dbops-lead"));
  }

  @Test
  @DisplayName("DM-E-59 the decision row")
  void decisionRow() {
    Request a = request(demo, List.of());
    CLOCK.set(T0.plusSeconds(30));
    decide(demo, a, "dbops-lead", Decision.REJECT, a.view().snapshotHash());
    List<Map<String, Object>> rows =
        demo.rows("approval_decision").stream()
            .filter(d -> d.get("request_id").equals(a.view().id()))
            .toList();
    assertThat(rows).hasSize(1);
    Map<String, Object> d = rows.getFirst();
    assertThat(d.get("user_id")).isEqualTo(demo.userId("dbops-lead"));
    assertThat(d.get("team_key")).isEqualTo("db-ops");
    assertThat(d.get("decision")).isEqualTo("reject");
    assertThat(d.get("snapshot_hash_shown")).isEqualTo(a.view().snapshotHash());
    assertThat(((Timestamp) d.get("at")).toInstant()).isEqualTo(T0.plusSeconds(30));
  }

  @Test
  @DisplayName("DM-R-63 of two simultaneous decisions exactly one is accepted")
  void simultaneous() throws Exception {
    Request a = request(demo, List.of());
    CountDownLatch go = new CountDownLatch(1);
    List<Callable<String>> tasks = new ArrayList<>();
    for (String user : List.of("dbops-engineer", "dbops-lead")) {
      tasks.add(
          () -> {
            go.await();
            try {
              decide(demo, a, user, Decision.APPROVE, a.view().snapshotHash());
              return "accepted";
            } catch (VigilError e) {
              return String.valueOf(e.details().get("refusal"));
            }
          });
    }
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<Future<String>> futures = new ArrayList<>();
      for (Callable<String> task : tasks) {
        futures.add(pool.submit(task));
      }
      go.countDown();
      List<String> results = new ArrayList<>();
      for (Future<String> future : futures) {
        results.add(future.get());
      }
      assertThat(results).containsExactlyInAnyOrder("accepted", "already_decided");
    } finally {
      pool.shutdownNow();
    }
    assertThat(demo.rows("approval_decision"))
        .filteredOn(d -> d.get("request_id").equals(a.view().id()))
        .hasSize(1);
  }

  @Test
  @DisplayName(
      "AR-E-09 an accepted decision publishes ApprovalDecided; an expiry or policy does not")
  void event() {
    Request a = request(demo, List.of());
    decide(demo, a, "dbops-engineer", Decision.APPROVE, a.view().snapshotHash());
    assertThat(EVENTS)
        .containsExactly(
            new ApprovalDecided(
                demo.tenant, a.view().id(), a.call().runId(), a.call().stepExecutionId()));
    EVENTS.clear();
    request(preauthorized, List.of());
    Request expired = request(demo, List.of());
    CLOCK.set(T0.plusSeconds(900));
    step.evaluate(expired.call());
    assertThat(view(demo, expired.view().id()).state()).isEqualTo(RequestState.EXPIRED);
    assertThat(EVENTS).isEmpty();
  }
}
