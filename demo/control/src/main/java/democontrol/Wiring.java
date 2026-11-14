package democontrol;

import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The objects of demo-control with the production limits (AR-B-03: 58 s; DD-F-10: 12 s). */
@Configuration
class Wiring {

  static final Duration LIMIT = Duration.ofSeconds(58);
  static final Duration SETTLE = Duration.ofSeconds(12);

  @Bean
  Panel panel(Clock clock, Settings settings) {
    return new Panel(clock, settings.product(), settings.vigilUrl(), settings.stateFile());
  }

  @Bean
  Operations operations(Panel panel, Lab lab, Vigil vigil, Settings settings, Clock clock) {
    return new Operations(panel, lab, vigil, settings.values(), clock, LIMIT, SETTLE);
  }
}
