package vigil.routing;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.JsonNode;
import vigil.connectors.Recordings;
import vigil.incidents.api.EscalationCause;
import vigil.incidents.api.EscalationView;
import vigil.incidents.api.IncidentService;
import vigil.incidents.api.NotifiedPurpose;
import vigil.notifications.NotificationsApp;
import vigil.notifications.NotificationsFixture;
import vigil.notifications.api.NotificationRecordView;
import vigil.routing.api.RoutingStepHandler;
import vigil.shared.Actor;
import vigil.shared.Completed;
import vigil.shared.Ids;
import vigil.shared.Key;
import vigil.shared.StepAnswer;
import vigil.shared.StepCall;
import vigil.shared.Waiting;
import vigil.testsupport.MutableClock;
import vigil.ticketing.api.SyncOutcome;
import vigil.ticketing.api.SyncRecordView;
import vigil.ticketing.api.TicketingService;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EscalationStepIT {

  static final Instant START = Instant.parse("2026-11-11T09:00:00Z");
  static final MutableClock CLOCK = new MutableClock(START);
  static final String COMMENT = "/rest/api/3/issue/NOC-1/comment";
  static final String WAIT_120 = "{\"mode\": \"wait_for_acknowledgement\", \"timer\": 120}";
  static final String NOW = "{\"mode\": \"escalate_now\"}";
  static final Duration TIMER = Duration.ofSeconds(120);
  static final Completed NEXT = new Completed(new Key("next"), null);

  /** The test clock. */
  static class TestBeans {
    @Bean
    @Primary
    Clock testClock() {
      return CLOCK;
    }
  }

  private NotificationsApp servers;
  private RoutingStepHandler step;
  private IncidentService incidents;
  private TicketingService ticketing;

  @BeforeAll
  void start() {
    servers = NotificationsApp.start(TestBeans.class);
    step = servers.app.context().getBean(RoutingStepHandler.class);
    incidents = servers.app.context().getBean(IncidentService.class);
    ticketing = servers.app.context().getBean(TicketingService.class);
  }

  @AfterAll
  void stop() {
    servers.close();
  }

  @BeforeEach
  void reset() {
    servers.reset();
    String comment = Recordings.provider("jira", "0005-comment.json", servers.base).responseBody();
    servers.wiremock.stubFor(
        post(urlEqualTo(COMMENT)).willReturn(aResponse().withStatus(201).withBody(comment)));
    CLOCK.set(START);
  }

  /** A tenant with an open level-0 incident that has its ticket NOC-1. */
  private record Case(NotificationsFixture f, UUID incident, UUID execution) {}

  private Case open() {
    NotificationsFixture f = servers.tenant("Escalate");
    UUID incident = f.openIncident();
    f.ticketLink(incident, servers.base);
    return new Case(f, incident, Ids.newId());
  }

  private StepAnswer start(Case c, String parameters) {
    return step.start(c.f().escalation(c.incident(), c.execution(), parameters, null, "start"));
  }

  private StepAnswer evaluate(Case c, String parameters, StepAnswer previous) {
    String state = previous instanceof Waiting waiting ? waiting.stepState() : null;
    return step.evaluate(c.f().escalation(c.incident(), c.execution(), parameters, state, "timer"));
  }

  private List<EscalationView> escalations(Case c) {
    return incidents.escalations(c.f().tenant, c.incident());
  }

  private List<LoggedRequest> comments() {
    return servers.wiremock.findAll(postRequestedFor(urlEqualTo(COMMENT)));
  }

  private static Waiting waiting(Instant due, long round, Instant started) {
    return new Waiting(
        Set.of(Waiting.WakeEvent.INCIDENT_ACKNOWLEDGED),
        due,
        "{\"round\":"
            + round
            + ",\"wait_started_at\":\""
            + started
            + "\",\"last_level_at_start\":false}");
  }

  private static void assertWaiting(StepAnswer answer, Instant due, long round, Instant started) {
    assertThat(answer).isInstanceOf(Waiting.class);
    Waiting waiting = (Waiting) answer;
    Waiting expected = waiting(due, round, started);
    assertThat(waiting.wakeEvents()).isEqualTo(expected.wakeEvents());
    assertThat(waiting.timerDueAt()).isEqualTo(expected.timerDueAt());
    assertThat(Recordings.tree(waiting.stepState()))
        .isEqualTo(Recordings.tree(expected.stepState()));
  }

  /** The escalation's messages: one per channel of each of its teams, by origin. */
  private List<NotificationRecordView> messages(Case c, UUID escalation) {
    return servers.records(c.f(), c.incident()).stream()
        .filter(r -> r.originId().equals(escalation))
        .toList();
  }

  private SyncRecordView syncRecord(Case c, UUID escalation) {
    return ticketing.syncRecords(c.f().tenant, c.incident()).stream()
        .filter(r -> escalation.equals(r.escalationId()))
        .findFirst()
        .orElseThrow();
  }

  /** Raises the incident to {@code level} with escalate_now steps; clears requests and mails. */
  private void raise(Case c, int level) {
    for (int i = 0; i < level; i++) {
      step.start(c.f().escalation(c.incident(), Ids.newId(), NOW, null, "start"));
    }
    servers.reset();
    servers.wiremock.stubFor(
        post(urlEqualTo(COMMENT)).willReturn(aResponse().withStatus(201).withBody("{}")));
  }

  @Test
  @DisplayName("SL-A2-03 the escalation through the levels: incident, messages, ticket comment")
  void throughTheLevels() {
    Case c = open();
    StepAnswer first = start(c, WAIT_120);
    assertWaiting(first, START.plus(TIMER), 0, START);
    assertThat(escalations(c)).isEmpty();

    Instant timeout = CLOCK.advance(TIMER);
    StepAnswer second = evaluate(c, WAIT_120, first);
    assertThat(escalations(c)).hasSize(1);
    EscalationView one = escalations(c).getFirst();
    assertThat(one.level()).isEqualTo(1);
    assertThat(one.teamKeys()).containsExactly("noc-shift-lead");
    assertThat(one.cause()).isEqualTo(EscalationCause.ACKNOWLEDGEMENT_TIMEOUT);
    assertThat(one.timer()).isEqualTo(TIMER);
    assertThat(incidents.incident(c.f().tenant, c.incident()).orElseThrow().escalationLevel())
        .isEqualTo(1);
    assertThat(servers.teamsKeys()).containsExactly("noc-shift-lead");
    assertThat(servers.emailRecipients()).containsExactly("noc-shift-lead@vigil.test");
    List<NotificationRecordView> messages = messages(c, one.id());
    assertThat(messages).hasSize(2);
    String[] lines = messages.getFirst().content().split("\n");
    assertThat(lines[0])
        .startsWith("Demo environment — fictional data - Escalation level 1: [P1] INC-");
    assertThat(lines[1]).isEqualTo("Escalated to NOC Shift Lead (not acknowledged within 2 min).");
    assertThat(lines[4]).isEqualTo("Evidence:");
    assertThat(lines[5]).startsWith("- ");
    assertThat(lines[lines.length - 1])
        .isEqualTo("Acknowledge: " + c.f().acknowledgeLink(c.incident()));
    assertThat(comments()).hasSize(1);
    assertThat(syncRecord(c, one.id()).outcome()).isEqualTo(SyncOutcome.DONE);
    assertWaiting(second, timeout.plus(TIMER), 1, timeout);

    servers.reset();
    servers.wiremock.stubFor(
        post(urlEqualTo(COMMENT)).willReturn(aResponse().withStatus(201).withBody("{}")));
    CLOCK.advance(TIMER);
    StepAnswer third = evaluate(c, WAIT_120, second);
    assertThat(third).isEqualTo(NEXT);
    assertThat(escalations(c)).hasSize(2);
    EscalationView two = escalations(c).getLast();
    assertThat(two.level()).isEqualTo(2);
    assertThat(two.teamKeys()).containsExactly("noc-management");
    assertThat(servers.teamsKeys()).containsExactly("noc-management");
    assertThat(servers.emailRecipients()).containsExactly("noc-management@vigil.test");
    assertThat(comments()).hasSize(1);
    assertThat(incidents.notifiedTeams(c.f().tenant, c.incident()))
        .filteredOn(team -> team.purpose() == NotifiedPurpose.ESCALATION)
        .extracting(team -> team.teamKey())
        .containsExactlyInAnyOrder("noc-shift-lead", "noc-management");
  }

  @Test
  @DisplayName("PM-M10-05 an escalation is recorded on the incident, then messaged, then commented")
  void bothModes() {
    Case c = open();
    assertThat(start(c, NOW)).isEqualTo(NEXT);
    EscalationView escalation = escalations(c).getFirst();
    assertThat(escalation.cause()).isEqualTo(EscalationCause.ESCALATE_NOW);
    assertThat(escalation.timer()).isNull();
    assertThat(escalation.policyRevision()).isEqualTo(1);
    List<NotificationRecordView> messages = messages(c, escalation.id());
    assertThat(messages)
        .allMatch(r -> r.originModule().equals("vigil-incidents"))
        .allMatch(r -> r.originEntity().equals("IncidentEscalation"))
        .allMatch(r -> r.purpose() == NotifiedPurpose.ESCALATION)
        .hasSize(2);
    assertThat(messages.getFirst().content().split("\n")[1])
        .isEqualTo("Escalated to NOC Shift Lead (workflow escalation step).");
    SyncRecordView comment = syncRecord(c, escalation.id());
    assertThat(comment.outcome()).isEqualTo(SyncOutcome.DONE);
    assertThat(messages.getFirst().at()).isAfterOrEqualTo(escalation.at());
    JsonNode body = Recordings.tree(comments().getFirst().getBodyAsString());
    assertThat(
            body.get("body").get("content").get(0).get("content").get(0).get("text").stringValue())
        .isEqualTo("Escalated to level 1: NOC Shift Lead (workflow escalation step).");

    Case w = open();
    StepAnswer waiting = start(w, WAIT_120);
    CLOCK.advance(TIMER);
    evaluate(w, WAIT_120, waiting);
    EscalationView timed = escalations(w).getFirst();
    assertThat(timed.cause()).isEqualTo(EscalationCause.ACKNOWLEDGEMENT_TIMEOUT);
    assertThat(messages(w, timed.id())).hasSize(2);
    assertThat(syncRecord(w, timed.id()).outcome()).isEqualTo(SyncOutcome.DONE);
  }

  @Test
  @DisplayName("PM-S-08 the Escalation step leaves by its output next")
  void outputNext() {
    Case c = open();
    assertThat(start(c, NOW)).isEqualTo(NEXT);
    Case a = open();
    a.f().acknowledge(a.incident());
    assertThat(start(a, WAIT_120)).isEqualTo(NEXT);
  }

  @Test
  @DisplayName("DM-S-08 both modes from the incident's level; the timer kept in step_state")
  void modesAndParameters() {
    Case c = open();
    for (String parameters :
        List.of(
            "{}",
            "{\"mode\": \"later\"}",
            "{\"mode\": \"wait_for_acknowledgement\"}",
            "{\"mode\": \"wait_for_acknowledgement\", \"timer\": 29}",
            "{\"mode\": \"wait_for_acknowledgement\", \"timer\": 86401}",
            "{\"mode\": \"wait_for_acknowledgement\", \"timer\": 120.5}",
            "{\"mode\": \"wait_for_acknowledgement\", \"timer\": \"120\"}",
            "not json")) {
      assertThatThrownBy(() -> start(c, parameters))
          .as(parameters)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("escalation step parameters: mode and timer");
    }
    StepCall notification = c.f().notification(c.incident(), c.execution(), WAIT_120);
    assertThatThrownBy(() -> step.start(notification))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("escalation step parameters: mode and timer");
    assertWaiting(
        start(c, "{\"mode\": \"wait_for_acknowledgement\", \"timer\": 30}"),
        START.plusSeconds(30),
        0,
        START);
    StepAnswer waiting = start(c, WAIT_120);
    CLOCK.advance(Duration.ofSeconds(60));
    assertWaiting(evaluate(c, WAIT_120, waiting), START.plus(TIMER), 0, START);
    assertThat(escalations(c)).isEmpty();
    Case now = open();
    assertThat(start(now, NOW)).isEqualTo(NEXT);
    assertThat(escalations(now)).extracting(EscalationView::level).containsExactly(1L);
  }

  @Test
  @DisplayName("DM-D-09 an acknowledgement counts whenever it was made")
  void acknowledgementCounts() {
    Case before = open();
    before.f().acknowledge(before.incident());
    assertThat(start(before, WAIT_120)).isEqualTo(NEXT);
    assertThat(escalations(before)).isEmpty();

    Case waiting = open();
    StepAnswer first = start(waiting, WAIT_120);
    assertThat(first).isInstanceOf(Waiting.class);
    waiting.f().acknowledge(waiting.incident());
    StepAnswer woken =
        step.evaluate(
            waiting
                .f()
                .escalation(
                    waiting.incident(),
                    waiting.execution(),
                    WAIT_120,
                    ((Waiting) first).stepState(),
                    "event:incident_acknowledged"));
    assertThat(woken).isEqualTo(NEXT);
    CLOCK.advance(TIMER.multipliedBy(2));
    assertThat(evaluate(waiting, WAIT_120, first)).isEqualTo(NEXT);
    assertThat(escalations(waiting)).isEmpty();
    assertThat(servers.teamsPosts()).isEmpty();
  }

  @Test
  @DisplayName("DM-S-08 escalate_now: level 1, the last again, not when resolved, above the last")
  void escalateNow() {
    Case c = open();
    assertThat(start(c, NOW)).isEqualTo(NEXT);
    raise(c, 1);
    assertThat(escalations(c)).extracting(EscalationView::level).containsExactly(1L, 2L);
    Case again = new Case(c.f(), c.incident(), Ids.newId());
    assertThat(start(again, NOW)).isEqualTo(NEXT);
    EscalationView last = escalations(c).getLast();
    assertThat(last.level()).isEqualTo(2);
    assertThat(last.cause()).isEqualTo(EscalationCause.ESCALATE_NOW);
    assertThat(servers.teamsKeys()).containsExactly("noc-management");

    Case resolved = open();
    resolved.f().resolve(resolved.incident());
    assertThat(start(resolved, NOW)).isEqualTo(NEXT);
    assertThat(escalations(resolved)).isEmpty();

    Case shortened = open();
    raise(shortened, 2);
    shortened
        .f()
        .postgres()
        .sql(
            "INSERT INTO routing.escalation_policy_revision (id, tenant_id, created_at, revision,"
                + " levels, saved_by_kind, saved_by_module, saved_at) VALUES (:id, :t, :now, 2,"
                + " CAST('[{\"level\":1,\"team_keys\":[\"noc-shift-lead\"]}]' AS jsonb), 'module',"
                + " 'vigil-setup', :now)")
        .param("id", Ids.newId())
        .param("t", shortened.f().tenant.value())
        .param("now", Timestamp.from(START))
        .update();
    assertThat(start(shortened, NOW)).isEqualTo(NEXT);
    EscalationView lastLevel = escalations(shortened).getLast();
    assertThat(lastLevel.level()).isEqualTo(1);
    assertThat(lastLevel.teamKeys()).containsExactly("noc-shift-lead");
    assertThat(lastLevel.policyRevision()).isEqualTo(2);
  }

  @Test
  @DisplayName("PM-D-08 wait_for_acknowledgement started at the last level: one wait, then next")
  void startedAtTheLastLevel() {
    Case c = open();
    raise(c, 2);
    StepAnswer first = start(c, WAIT_120);
    assertThat(first).isInstanceOf(Waiting.class);
    assertThat(
            Recordings.tree(((Waiting) first).stepState())
                .get("last_level_at_start")
                .booleanValue())
        .isTrue();
    CLOCK.advance(TIMER);
    assertThat(evaluate(c, WAIT_120, first)).isEqualTo(NEXT);
    EscalationView last = escalations(c).getLast();
    assertThat(escalations(c)).hasSize(3);
    assertThat(last.level()).isEqualTo(2);
    assertThat(last.cause()).isEqualTo(EscalationCause.ACKNOWLEDGEMENT_TIMEOUT);
    assertThat(servers.teamsKeys()).containsExactly("noc-management");
  }

  /** Records an escalation as vigil-routing and its row, as a stop after the transaction left. */
  private UUID recordedRound(Case c, long round, long level, String team, Instant at) {
    UUID escalation =
        incidents.recordEscalation(
            c.f().tenant,
            Actor.module("vigil-routing"),
            c.incident(),
            level,
            Set.of(new Key(team)),
            EscalationCause.ACKNOWLEDGEMENT_TIMEOUT,
            TIMER,
            1);
    c.f()
        .postgres()
        .sql(
            "INSERT INTO routing.step_escalation (id, tenant_id, created_at, incident_id,"
                + " step_execution_id, round, escalation_id, level, at) VALUES (:id, :t, :at,"
                + " :i, :s, :r, :e, :l, :at)")
        .param("id", Ids.newId())
        .param("t", c.f().tenant.value())
        .param("at", Timestamp.from(at))
        .param("i", c.incident())
        .param("s", c.execution())
        .param("r", round)
        .param("e", escalation)
        .param("l", level)
        .update();
    return escalation;
  }

  @Test
  @DisplayName("DM-R-64 evaluated again after a stop, the step continues with its recorded round")
  void recordedRoundFirst() {
    Case c = open();
    StepAnswer first = start(c, WAIT_120);
    Instant timeout = CLOCK.advance(TIMER);
    UUID one = recordedRound(c, 1, 1, "noc-shift-lead", timeout);
    c.f().acknowledge(c.incident());
    StepAnswer resumed =
        step.evaluate(
            c.f()
                .escalation(
                    c.incident(),
                    c.execution(),
                    WAIT_120,
                    ((Waiting) first).stepState(),
                    "restart"));
    assertThat(escalations(c)).hasSize(1);
    assertThat(servers.teamsKeys()).containsExactly("noc-shift-lead");
    assertThat(servers.emailRecipients()).containsExactly("noc-shift-lead@vigil.test");
    assertThat(comments()).hasSize(1);
    assertThat(syncRecord(c, one).outcome()).isEqualTo(SyncOutcome.DONE);
    assertWaiting(resumed, timeout.plus(TIMER), 1, timeout);
    StepAnswer again =
        step.evaluate(
            c.f()
                .escalation(
                    c.incident(),
                    c.execution(),
                    WAIT_120,
                    ((Waiting) first).stepState(),
                    "restart"));
    assertWaiting(again, timeout.plus(TIMER), 1, timeout);
    assertThat(servers.teamsPosts()).hasSize(1);
    assertThat(servers.emails()).hasSize(1);
    assertThat(comments()).hasSize(1);

    servers.reset();
    servers.wiremock.stubFor(
        post(urlEqualTo(COMMENT)).willReturn(aResponse().withStatus(201).withBody("{}")));
    Instant later = CLOCK.advance(TIMER);
    UUID two = recordedRound(c, 2, 2, "noc-management", later);
    assertThat(evaluate(c, WAIT_120, resumed)).isEqualTo(NEXT);
    assertThat(escalations(c)).hasSize(2);
    assertThat(servers.teamsKeys()).containsExactly("noc-management");
    assertThat(servers.emailRecipients()).containsExactly("noc-management@vigil.test");
    assertThat(comments()).hasSize(1);
    assertThat(messages(c, two)).hasSize(2);
    assertThat(messages(c, one)).hasSize(2);
  }
}
