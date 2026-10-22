package vigil.intake;

import java.time.Duration;
import java.time.Instant;
import vigil.intake.api.Health;
import vigil.intake.api.TestOutcome;

/** DM-R-40: the health of a source from its row and now, and the cause of a change (DM-SM-03). */
final class HealthRule {

  private HealthRule() {}

  /** The values of a source row the rule reads. */
  record Inputs(
      Health health,
      Instant lastContactAt,
      Instant lastTestAt,
      TestOutcome lastTestOutcome,
      Duration staleAfter,
      Duration disconnectedAfter) {}

  /** The health now and, when it differs from the stored health, the cause; else {@code null}. */
  record Result(Health health, HealthCause cause) {}

  static Result evaluate(Inputs source, Instant now) {
    Health health;
    boolean testFailed = false;
    if (source.lastContactAt() == null) {
      health = Health.DISCONNECTED;
    } else if (source.lastTestOutcome() == TestOutcome.FAILED
        && !source.lastContactAt().isAfter(source.lastTestAt())) {
      health = Health.DISCONNECTED;
      testFailed = true;
    } else {
      Duration age = Duration.between(source.lastContactAt(), now);
      if (age.compareTo(source.disconnectedAfter()) > 0) {
        health = Health.DISCONNECTED;
      } else if (age.compareTo(source.staleAfter()) > 0) {
        health = Health.STALE;
      } else {
        health = Health.HEALTHY;
      }
    }
    if (health == source.health()) {
      return new Result(health, null);
    }
    HealthCause cause =
        switch (health) {
          case HEALTHY -> HealthCause.CONTACT;
          case STALE -> HealthCause.CONTACT_AGE;
          case DISCONNECTED -> testFailed ? HealthCause.TEST_FAILED : HealthCause.CONTACT_AGE;
        };
    return new Result(health, cause);
  }
}
