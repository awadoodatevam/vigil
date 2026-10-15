package vigil.shared;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * The step waits for one of {@code wakeEvents} or for {@code timerDueAt} (may be {@code null}); the
 * engine keeps {@code stepState} (AR-R-21).
 */
public record Waiting(Set<WakeEvent> wakeEvents, Instant timerDueAt, String stepState)
    implements StepAnswer {

  /** The events that wake a waiting step (DM-E-51 {@code wake_events}). */
  public enum WakeEvent {
    ALERT_STATE_CHANGED,
    SOURCE_HEALTH_CHANGED,
    INCIDENT_ACKNOWLEDGED,
    INCIDENT_STATE_CHANGED,
    APPROVAL_DECIDED;

    /** The lower-case key of the wake event. */
    public String key() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  public Waiting {
    Objects.requireNonNull(wakeEvents, "wakeEvents");
    wakeEvents = Set.copyOf(wakeEvents);
  }
}
