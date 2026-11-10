package vigil.connectors;

import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The timings of this module's connectors. */
@Configuration
class ConnectorConfiguration {

  /**
   * Connect 5 s, monitoring source connection test 10 s, chat 90 s, local chat 120 s, embeddings 30
   * s for one text and 90 s for more (AR-TM-01, BP-A-30).
   */
  @Bean
  ConnectorTimings connectorTimings() {
    return new ConnectorTimings(
        Duration.ofSeconds(5),
        Duration.ofSeconds(10),
        Duration.ofSeconds(90),
        Duration.ofSeconds(120),
        Duration.ofSeconds(30),
        Duration.ofSeconds(90));
  }
}
