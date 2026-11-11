package vigil.routing;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import vigil.identity.api.IdentityService;
import vigil.identity.api.TeamView;
import vigil.incidents.api.EscalationCause;
import vigil.incidents.api.EscalationView;
import vigil.incidents.api.IncidentService;
import vigil.incidents.api.IncidentState;
import vigil.incidents.api.IncidentView;
import vigil.notifications.api.NotificationsService;
import vigil.routing.StepEscalations.Round;
import vigil.routing.api.EscalationLevel;
import vigil.routing.api.EscalationPolicyView;
import vigil.routing.api.RoutingService;
import vigil.routing.api.RoutingStepHandler;
import vigil.shared.Actor;
import vigil.shared.Completed;
import vigil.shared.Key;
import vigil.shared.StepAnswer;
import vigil.shared.StepCall;
import vigil.shared.StepCancel;
import vigil.shared.TenantId;
import vigil.shared.Timestamps;
import vigil.shared.TransactionGuard;
import vigil.shared.Waiting;
import vigil.ticketing.api.SyncOutcome;
import vigil.ticketing.api.TicketingService;

/**
 * The Escalation step (DM-S-08, PM-S-08, PM-M10-05, PM-D-08, DM-D-09, DM-R-64; P-22 spec Section
 * 6.10): each round is recorded on the incident with its row of {@code routing.step_escalation},
 * then messaged and commented in the ticket; a round already recorded is sent and continued from
 * first. {@code cancel} does nothing.
 */
@Component
class EscalationStep implements RoutingStepHandler {

  static final String BAD_PARAMETERS = "escalation step parameters: mode and timer";
  static final Actor ROUTING = Actor.module("vigil-routing");
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Logger LOG = LoggerFactory.getLogger(EscalationStep.class);
  private static final Key NEXT = new Key("next");
  private static final long TIMER_MIN = 30;
  private static final long TIMER_MAX = 86_400;
  private static final String ROUND = "round";
  private static final String WAIT_STARTED_AT = "wait_started_at";
  private static final String LAST_LEVEL_AT_START = "last_level_at_start";

  private final RoutingService routing;
  private final StepEscalations steps;
  private final IncidentService incidents;
  private final NotificationsService notifications;
  private final TicketingService ticketing;
  private final IdentityService identity;
  private final Clock clock;
  private final TransactionTemplate tx;

  EscalationStep(
      RoutingService routing,
      StepEscalations steps,
      IncidentService incidents,
      NotificationsService notifications,
      TicketingService ticketing,
      IdentityService identity,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.routing = routing;
    this.steps = steps;
    this.incidents = incidents;
    this.notifications = notifications;
    this.ticketing = ticketing;
    this.identity = identity;
    this.clock = clock;
    this.tx = new TransactionTemplate(transactionManager);
  }

  @Override
  public StepAnswer start(StepCall call) {
    return step(call);
  }

  @Override
  public StepAnswer evaluate(StepCall call) {
    return step(call);
  }

  /** The step leaves nothing open (AR-R-21). */
  @Override
  public void cancel(StepCancel cancel) {
    // Nothing to cancel.
  }

  /** The step's mode and, for {@code wait_for_acknowledgement}, its timer. */
  private record Parameters(boolean escalateNow, Duration timer) {}

  /** The step state of {@code wait_for_acknowledgement}. */
  private record State(long round, Instant waitStartedAt, boolean lastLevelAtStart) {}

  /** The policy's levels in ascending order and its revision. */
  private record Policy(List<EscalationLevel> levels, long revision) {

    long last() {
      return levels.getLast().level();
    }
  }

  private StepAnswer step(StepCall call) {
    TransactionGuard.assertNoTransaction();
    Parameters parameters = parameters(call);
    TenantId tenantId = call.tenantId();
    Optional<EscalationPolicyView> found = routing.escalationPolicy(tenantId);
    if (found.isEmpty() || found.get().levels().isEmpty()) {
      return next();
    }
    Policy policy =
        new Policy(
            found.get().levels().stream()
                .sorted(Comparator.comparingLong(EscalationLevel::level))
                .toList(),
            found.get().revision());
    IncidentView incident =
        incidents
            .incident(tenantId, call.incidentId())
            .orElseThrow(
                () -> new IllegalStateException("incident " + call.incidentId() + " not found"));
    long current = incident.escalationLevel();
    if (parameters.escalateNow()) {
      Optional<Round> recorded = steps.round(tenantId, call.stepExecutionId(), 1);
      if (recorded.isPresent()) {
        send(tenantId, incident.id(), recorded.get());
        return next();
      }
      if (incident.state() != IncidentState.OPEN) {
        return next();
      }
      Round round = record(call, incident, policy, 1, current, EscalationCause.ESCALATE_NOW, null);
      send(tenantId, incident.id(), round);
      return next();
    }
    Duration timer = parameters.timer();
    State state =
        call.stepState() == null
            ? new State(0, Timestamps.now(clock), current >= policy.last())
            : state(call.stepState());
    Optional<Round> recorded = steps.round(tenantId, call.stepExecutionId(), state.round() + 1);
    if (recorded.isPresent()) {
      send(tenantId, incident.id(), recorded.get());
      return continueFrom(recorded.get(), state.lastLevelAtStart(), policy, timer);
    }
    if (incident.state() != IncidentState.OPEN) {
      return next();
    }
    if (incidents.acknowledgement(tenantId, incident.id()).isPresent()) {
      return next();
    }
    Instant due = state.waitStartedAt().plus(timer);
    if (Timestamps.now(clock).isBefore(due)) {
      return new Waiting(Set.of(Waiting.WakeEvent.INCIDENT_ACKNOWLEDGED), due, json(state));
    }
    Round round =
        record(
            call,
            incident,
            policy,
            state.round() + 1,
            current,
            EscalationCause.ACKNOWLEDGEMENT_TIMEOUT,
            timer);
    send(tenantId, incident.id(), round);
    return continueFrom(round, state.lastLevelAtStart(), policy, timer);
  }

  /**
   * Records round {@code number} to level {@code min(current + 1, last)} in one transaction: the
   * escalation on the incident and the row of the step execution.
   */
  private Round record(
      StepCall call,
      IncidentView incident,
      Policy policy,
      long number,
      long current,
      EscalationCause cause,
      Duration timer) {
    long target = Math.min(current + 1, policy.last());
    EscalationLevel level =
        policy.levels().stream()
            .filter(candidate -> candidate.level() >= target)
            .findFirst()
            .orElse(policy.levels().getLast());
    Set<Key> teamKeys = new LinkedHashSet<>();
    level.teamKeys().forEach(key -> teamKeys.add(new Key(key)));
    Instant now = Timestamps.now(clock);
    return tx.execute(
        status -> {
          UUID escalationId =
              incidents.recordEscalation(
                  call.tenantId(),
                  ROUTING,
                  incident.id(),
                  level.level(),
                  teamKeys,
                  cause,
                  timer,
                  policy.revision());
          Round round = new Round(call.stepExecutionId(), number, escalationId, level.level(), now);
          steps.insert(call.tenantId(), incident.id(), round);
          return round;
        });
  }

  /**
   * Sends a recorded round with no transaction open: the messages, then the ticket comment; both
   * send nothing twice (DM-R-65, P-21 spec Section 6.8).
   */
  private void send(TenantId tenantId, UUID incidentId, Round round) {
    EscalationView escalation =
        incidents.escalations(tenantId, incidentId).stream()
            .filter(candidate -> candidate.id().equals(round.escalationId()))
            .findFirst()
            .orElseThrow(
                () ->
                    new IllegalStateException("escalation " + round.escalationId() + " not found"));
    notifications.notifyEscalation(
        tenantId,
        incidentId,
        escalation.id(),
        escalation.level(),
        escalation.teamKeys().stream().map(Key::new).toList(),
        escalation.cause(),
        escalation.timer());
    List<String> names =
        escalation.teamKeys().stream()
            .map(key -> identity.team(tenantId, new Key(key)).map(TeamView::name).orElse(key))
            .toList();
    SyncOutcome outcome =
        ticketing.recordEscalation(
            tenantId,
            incidentId,
            escalation.id(),
            escalation.level(),
            names,
            escalation.cause(),
            escalation.timer());
    if (outcome == SyncOutcome.FAILED) {
      LOG.warn(
          "escalation of incident {} of tenant {}: the ticket comment failed",
          incidentId,
          tenantId.value());
    }
  }

  /** After round {@code round}: {@code next} once the last level is notified, else wait again. */
  private StepAnswer continueFrom(
      Round round, boolean lastLevelAtStart, Policy policy, Duration timer) {
    if (lastLevelAtStart || round.level() >= policy.last()) {
      return next();
    }
    return new Waiting(
        Set.of(Waiting.WakeEvent.INCIDENT_ACKNOWLEDGED),
        round.at().plus(timer),
        json(new State(round.round(), round.at(), false)));
  }

  private static Completed next() {
    return new Completed(NEXT, null);
  }

  private static String json(State state) {
    ObjectNode node = JSON.createObjectNode();
    node.put(ROUND, state.round());
    node.put(WAIT_STARTED_AT, state.waitStartedAt().toString());
    node.put(LAST_LEVEL_AT_START, state.lastLevelAtStart());
    return JSON.writeValueAsString(node);
  }

  private static State state(String stepState) {
    try {
      JsonNode node = JSON.readTree(stepState);
      JsonNode round = node == null ? null : node.get(ROUND);
      JsonNode started = node == null ? null : node.get(WAIT_STARTED_AT);
      JsonNode last = node == null ? null : node.get(LAST_LEVEL_AT_START);
      if (round == null
          || !round.isIntegralNumber()
          || round.longValue() < 0
          || started == null
          || !started.isString()
          || last == null
          || !last.isBoolean()) {
        throw new IllegalStateException("escalation step state is not valid");
      }
      return new State(
          round.longValue(), Instant.parse(started.stringValue()), last.booleanValue());
    } catch (JacksonException | DateTimeParseException e) {
      throw new IllegalStateException("escalation step state is not valid", e);
    }
  }

  private static Parameters parameters(StepCall call) {
    if (call.stepType() != StepCall.StepType.ESCALATION) {
      throw new IllegalArgumentException(BAD_PARAMETERS);
    }
    JsonNode tree;
    try {
      tree = JSON.readTree(call.parameters());
    } catch (JacksonException e) {
      throw new IllegalArgumentException(BAD_PARAMETERS, e);
    }
    JsonNode mode = tree == null || !tree.isObject() ? null : tree.get("mode");
    if (mode == null || !mode.isString()) {
      throw new IllegalArgumentException(BAD_PARAMETERS);
    }
    if (mode.stringValue().equals("escalate_now")) {
      return new Parameters(true, null);
    }
    if (!mode.stringValue().equals("wait_for_acknowledgement")) {
      throw new IllegalArgumentException(BAD_PARAMETERS);
    }
    JsonNode timer = tree.get("timer");
    if (timer == null
        || !timer.isIntegralNumber()
        || !timer.canConvertToLong()
        || timer.longValue() < TIMER_MIN
        || timer.longValue() > TIMER_MAX) {
      throw new IllegalArgumentException(BAD_PARAMETERS);
    }
    return new Parameters(false, Duration.ofSeconds(timer.longValue()));
  }
}
