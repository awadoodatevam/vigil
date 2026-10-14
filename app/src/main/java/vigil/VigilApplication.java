package vigil;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;

@SpringBootApplication
@Modulithic(sharedModules = "shared")
public class VigilApplication {

  public static void main(String[] args) {
    SpringApplication.run(VigilApplication.class, args);
  }
}
