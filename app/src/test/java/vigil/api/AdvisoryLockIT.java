package vigil.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import vigil.testsupport.VigilDatabase;
import vigil.testsupport.VigilTestApp;

@ExtendWith(OutputCaptureExtension.class)
class AdvisoryLockIT {

  @Test
  @DisplayName("AR-R-01 a second vigil-app on the same database stops with the message")
  void secondStartOnTheSameDatabaseFails(CapturedOutput output) {
    VigilDatabase database = VigilDatabase.fresh();
    try (VigilTestApp first = VigilTestApp.start(database)) {
      first.awaitStarted();

      try (VigilTestApp second = VigilTestApp.start(database)) {
        assertThatThrownBy(second::awaitStarted)
            .rootCause()
            .hasMessage("Another vigil-app is running against this database.");
      }
      assertThat(output.getOut())
          .containsPattern(
              "\"log\":\\{\"level\":\"ERROR\"[^}]*\\}.*"
                  + "\"message\":\"Another vigil-app is running against this database.\"");

      try (VigilTestApp other = VigilTestApp.start(VigilDatabase.fresh())) {
        assertThat(other.awaitStarted().isActive()).isTrue();
      }
    }
  }
}
