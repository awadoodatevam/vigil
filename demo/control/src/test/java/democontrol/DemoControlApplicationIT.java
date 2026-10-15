package democontrol;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;

@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
class DemoControlApplicationIT {

  @Autowired private ApplicationContext context;

  @Test
  @DisplayName("BP-R-06 demo-control is a separate application")
  void contextStarts() {
    assertThat(context.getBean(DemoControlApplication.class)).isNotNull();
  }

  @Test
  @DisplayName("BP-R-16 demo-control logs JSON lines on standard output")
  void logsJson(CapturedOutput output) throws JSONException {
    List<String> lines = output.getOut().lines().filter(line -> !line.isBlank()).toList();
    assertThat(lines).isNotEmpty();
    for (String line : lines) {
      JSONObject json = new JSONObject(line);
      assertThat(json.has("@timestamp")).as("@timestamp in %s", line).isTrue();
      assertThat(json.has("message")).as("message in %s", line).isTrue();
      assertThat(json.getJSONObject("log").has("level")).as("log.level in %s", line).isTrue();
    }
  }
}
