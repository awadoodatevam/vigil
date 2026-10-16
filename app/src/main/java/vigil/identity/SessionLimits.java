package vigil.identity;

import java.time.Duration;
import java.time.Instant;

/** Session lifetime and idle limit (AR-TM-03, AR-S-02). */
final class SessionLimits {

  static final Duration LIFETIME = Duration.ofHours(12);
  static final Duration IDLE = Duration.ofHours(2);
  static final Duration ACTIVITY_UPDATE = Duration.ofSeconds(60);

  private SessionLimits() {}

  /** True when a session is past its limits at {@code now} (DM-SM02-T3). */
  static boolean expired(Instant now, Instant expiresAt, Instant lastActivityAt) {
    return !now.isBefore(expiresAt) || Duration.between(lastActivityAt, now).compareTo(IDLE) > 0;
  }
}
