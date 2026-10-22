package vigil.intake;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vigil.intake.HealthRule.Inputs;
import vigil.intake.HealthRule.Result;
import vigil.intake.api.Health;
import vigil.intake.api.TestOutcome;

class SourceHealthRuleTest {

  private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");
  private static final Duration STALE = Duration.ofSeconds(120);
  private static final Duration DISCONNECTED = Duration.ofSeconds(300);

  private static Result evaluate(
      Health stored, Instant contact, Instant test, TestOutcome outcome) {
    return HealthRule.evaluate(
        new Inputs(stored, contact, test, outcome, STALE, DISCONNECTED), NOW);
  }

  private static Health health(Instant contact, Instant test, TestOutcome outcome) {
    return evaluate(Health.HEALTHY, contact, test, outcome).health();
  }

  private static Instant ago(long millis) {
    return NOW.minusMillis(millis);
  }

  @Test
  @DisplayName("DM-R-40 the health rule from the source's contact and tests")
  void healthRule() {
    assertThat(health(null, null, null)).isEqualTo(Health.DISCONNECTED);
    assertThat(health(ago(120_000), null, null)).isEqualTo(Health.HEALTHY);
    assertThat(health(ago(121_000), null, null)).isEqualTo(Health.STALE);
    assertThat(health(ago(300_000), null, null)).isEqualTo(Health.STALE);
    assertThat(health(ago(301_000), null, null)).isEqualTo(Health.DISCONNECTED);
    Result failedAfterContact =
        evaluate(Health.HEALTHY, ago(10_000), ago(5_000), TestOutcome.FAILED);
    assertThat(failedAfterContact)
        .isEqualTo(new Result(Health.DISCONNECTED, HealthCause.TEST_FAILED));
    assertThat(health(ago(4_999), ago(5_000), TestOutcome.FAILED)).isEqualTo(Health.HEALTHY);
    assertThat(health(ago(5_000), ago(5_000), TestOutcome.FAILED)).isEqualTo(Health.DISCONNECTED);
    assertThat(health(ago(5_000), ago(5_000), TestOutcome.PASSED)).isEqualTo(Health.HEALTHY);
  }

  @Test
  @DisplayName("DM-SM-03 each health change has its cause; no change has none")
  void causes() {
    assertThat(evaluate(Health.STALE, ago(1_000), null, null))
        .isEqualTo(new Result(Health.HEALTHY, HealthCause.CONTACT));
    assertThat(evaluate(Health.HEALTHY, ago(121_000), null, null))
        .isEqualTo(new Result(Health.STALE, HealthCause.CONTACT_AGE));
    assertThat(evaluate(Health.STALE, ago(301_000), null, null))
        .isEqualTo(new Result(Health.DISCONNECTED, HealthCause.CONTACT_AGE));
    assertThat(evaluate(Health.HEALTHY, ago(10_000), ago(1_000), TestOutcome.FAILED))
        .isEqualTo(new Result(Health.DISCONNECTED, HealthCause.TEST_FAILED));
    assertThat(evaluate(Health.HEALTHY, ago(1_000), null, null))
        .isEqualTo(new Result(Health.HEALTHY, null));
  }
}
