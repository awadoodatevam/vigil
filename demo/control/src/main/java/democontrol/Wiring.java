package democontrol;

import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

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
  Lab lab(Settings settings, Secrets secrets) {
    return new Lab(settings, secrets);
  }

  @Bean
  Vigil vigil(Settings settings, Secrets secrets) {
    return new Vigil(settings, secrets);
  }

  @Bean
  Login login(Secrets secrets, Clock clock) {
    return new Login(secrets, clock);
  }

  @Bean
  Operations operations(Panel panel, Lab lab, Vigil vigil, Settings settings, Clock clock) {
    return new Operations(panel, lab, vigil, settings.values(), clock, LIMIT, SETTLE);
  }

  @Bean
  Reachability reachability(Panel panel, Lab lab, Vigil vigil) {
    return new Reachability(panel, lab, vigil);
  }

  @Bean
  Checks checks(Reachability reachability) {
    return new Checks(reachability);
  }

  /** The reachability checks every 5 s, from the start (spec 6.3 item 5). */
  static final class Checks {
    private final Reachability reachability;

    Checks(Reachability reachability) {
      this.reachability = reachability;
    }

    @Scheduled(initialDelay = 0, fixedDelay = 5000)
    void check() {
      reachability.check();
    }
  }
}
