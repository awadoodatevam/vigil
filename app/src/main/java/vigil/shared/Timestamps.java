package vigil.shared;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The clock: UTC, timestamps in whole milliseconds (AR-P-01, DM-C-08). */
public final class Timestamps {

  private Timestamps() {}

  /** The current instant of {@code clock}, truncated to milliseconds. */
  public static Instant now(Clock clock) {
    return clock.instant().truncatedTo(ChronoUnit.MILLIS);
  }

  /** The {@link Clock} bean. Tests replace it with a fixed clock. */
  @Configuration
  public static class ClockConfiguration {

    @Bean
    public Clock clock() {
      return Clock.systemUTC();
    }
  }
}
