package vigil;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.modulith.Modulithic;

@SpringBootApplication
@Modulithic(sharedModules = "shared")
public class VigilApplication {

  /** The profile of the one-shot unit vigil-migrate (AR-U-01). */
  public static final String MIGRATE_PROFILE = "migrate";

  public static void main(String[] args) {
    ConfigurableApplicationContext context = SpringApplication.run(VigilApplication.class, args);
    if (context.getEnvironment().matchesProfiles(MIGRATE_PROFILE)) {
      System.exit(SpringApplication.exit(context));
    }
  }
}
