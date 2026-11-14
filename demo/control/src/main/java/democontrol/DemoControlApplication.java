package democontrol;

import java.time.Clock;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class DemoControlApplication {

  public static void main(String[] args) {
    SpringApplication application = new SpringApplication(DemoControlApplication.class);
    // Spec 6.2 item 1: a missing property or value stops demo-control with one log line naming it.
    application.addListeners(
        (ApplicationListener<ApplicationEnvironmentPreparedEvent>)
            event -> {
              try {
                Settings.of(event.getEnvironment());
              } catch (Failed e) {
                LoggerFactory.getLogger(DemoControlApplication.class).error(e.getMessage());
                System.exit(1);
              }
            });
    application.run(args);
  }

  @Bean
  Settings settings(Environment env) throws Failed {
    return Settings.of(env);
  }

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  Secrets secrets(Settings settings) {
    return new Secrets(settings.secrets());
  }
}
