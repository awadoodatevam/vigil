package vigil;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class LoggingLevelTest {

  @Test
  @DisplayName("AR-S-11 the logger org.apache.sshd writes at WARN and above only")
  void sshdAtWarn() throws IOException {
    Properties properties = new Properties();
    try (InputStream in = new ClassPathResource("application.properties").getInputStream()) {
      properties.load(in);
    }
    assertThat(properties.getProperty("logging.level.org.apache.sshd")).isEqualTo("WARN");
  }
}
