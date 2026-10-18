package vigil.connectors;

import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The timings of this module's connectors. */
@Configuration
class ConnectorConfiguration {

  /** Connect 5 s, monitoring source connection test 10 s (AR-TM-01). */
  @Bean
  ConnectorTimings connectorTimings() {
    return new ConnectorTimings(Duration.ofSeconds(5), Duration.ofSeconds(10));
  }
}
