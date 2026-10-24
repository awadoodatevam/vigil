package vigil.engine;

import java.time.Instant;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import vigil.incidents.api.IncidentView;
import vigil.shared.Completed;
import vigil.shared.Key;
import vigil.shared.StepAnswer;
import vigil.shared.Waiting;
import vigil.shared.Waiting.WakeEvent;

/**
 * The Wait/timer step (DM-S-11): mode {@code duration} waits {@code duration} seconds; mode {@code
 * until_event} waits for the incident's acknowledgement for at most {@code timeout} seconds. The
 * due time is kept in the step state.
 */
final class WaitStep {

  private static final String DUE_AT = "due_at";

  private WaitStep() {}

  /**
   * The answer to {@code start} (when {@code start}) or {@code evaluate}, from the parameters, the
   * stored step state and now.
   */
  static StepAnswer answer(
      boolean start, IncidentView incident, JsonNode parameters, String stepState, Instant now) {
    boolean untilEvent = "until_event".equals(parameters.path("mode").asString(""));
    if (untilEvent && incident.acknowledgedAt() != null) {
      return completed("event_received");
    }
    Set<WakeEvent> events = untilEvent ? Set.of(WakeEvent.INCIDENT_ACKNOWLEDGED) : Set.of();
    Instant dueAt;
    if (start) {
      long seconds = parameters.path(untilEvent ? "timeout" : "duration").asLong();
      dueAt = now.plusSeconds(seconds);
    } else {
      dueAt = Instant.parse(RunContent.JSON.readTree(stepState).get(DUE_AT).stringValue());
      if (!now.isBefore(dueAt)) {
        return completed("timed_out");
      }
    }
    return new Waiting(
        events,
        dueAt,
        RunContent.JSON.writeValueAsString(
            RunContent.JSON.createObjectNode().put(DUE_AT, dueAt.toString())));
  }

  private static Completed completed(String output) {
    return new Completed(new Key(output), null);
  }
}
