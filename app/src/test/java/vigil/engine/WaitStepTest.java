package vigil.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import vigil.incidents.api.AiStatus;
import vigil.incidents.api.IncidentState;
import vigil.incidents.api.IncidentView;
import vigil.shared.Completed;
import vigil.shared.StepAnswer;
import vigil.shared.Waiting;
import vigil.shared.Waiting.WakeEvent;

class WaitStepTest {

  private static final Instant T = Instant.parse("2026-10-24T10:00:00Z");

  private static IncidentView incident(Instant acknowledgedAt) {
    return new IncidentView(
        UUID.randomUUID(),
        1,
        1L,
        "Title",
        IncidentState.OPEN,
        T,
        T,
        T,
        T,
        1,
        List.of(),
        List.of(),
        null,
        null,
        null,
        List.of(),
        null,
        null,
        List.of(),
        null,
        true,
        0,
        acknowledgedAt,
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
        T,
        T);
  }

  private static JsonNode parameters(String json) {
    return RunContent.JSON.readTree(json);
  }

  private static String state(Instant dueAt) {
    return "{\"due_at\":\"" + dueAt + "\"}";
  }

  private static void assertWaiting(StepAnswer answer, Set<WakeEvent> events, Instant dueAt) {
    assertThat(answer).isInstanceOf(Waiting.class);
    Waiting waiting = (Waiting) answer;
    assertThat(waiting.wakeEvents()).isEqualTo(events);
    assertThat(waiting.timerDueAt()).isEqualTo(dueAt);
    assertThat(RunContent.JSON.readTree(waiting.stepState()))
        .isEqualTo(RunContent.JSON.readTree(state(dueAt)));
  }

  private static void assertOutput(StepAnswer answer, String output) {
    assertThat(answer).isInstanceOf(Completed.class);
    assertThat(((Completed) answer).namedOutput().value()).isEqualTo(output);
    assertThat(((Completed) answer).resultRef()).isNull();
  }

  @Test
  @DisplayName("DM-S-11 the Wait/timer step in duration and until_event mode")
  void waitStep() {
    JsonNode duration = parameters("{\"mode\": \"duration\", \"duration\": 60}");
    IncidentView open = incident(null);
    Instant due = T.plusSeconds(60);
    assertWaiting(WaitStep.answer(true, open, duration, null, T), Set.of(), due);
    assertWaiting(
        WaitStep.answer(false, open, duration, state(due), T.plusSeconds(59)), Set.of(), due);
    assertOutput(WaitStep.answer(false, open, duration, state(due), due), "timed_out");

    JsonNode untilEvent =
        parameters(
            "{\"mode\": \"until_event\", \"event\": \"incident_acknowledged\", \"timeout\": 60}");
    IncidentView acknowledged = incident(T.minusSeconds(5));
    assertOutput(WaitStep.answer(true, acknowledged, untilEvent, null, T), "event_received");
    assertWaiting(
        WaitStep.answer(true, open, untilEvent, null, T),
        Set.of(WakeEvent.INCIDENT_ACKNOWLEDGED),
        due);
    assertOutput(
        WaitStep.answer(false, acknowledged, untilEvent, state(due), T.plusSeconds(10)),
        "event_received");
    assertOutput(
        WaitStep.answer(false, acknowledged, untilEvent, state(due), T.plusSeconds(61)),
        "event_received");
    assertOutput(WaitStep.answer(false, open, untilEvent, state(due), due), "timed_out");
  }
}
