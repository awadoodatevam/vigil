package vigil.connectors;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vigil.connectors.api.AiPromptPart;

class AiRenderingTest {

  @Test
  @DisplayName("AR-G-10 the parts are rendered into one user message, joined by one empty line")
  void rendering() {
    String rendered =
        AiConnector.render(
            List.of(
                new AiPromptPart("Member alerts", "Alert data", "1. disk_full\n2. db_write_errors"),
                new AiPromptPart("E1", "Diagnostic output", "/data 100% used"),
                new AiPromptPart("RB-DB-01", "Runbook text", "Free space on /data.")));
    assertThat(rendered)
        .isEqualTo(
            "### Member alerts (Alert data)\n1. disk_full\n2. db_write_errors\n\n"
                + "### E1 (Diagnostic output)\n/data 100% used\n\n"
                + "### RB-DB-01 (Runbook text)\nFree space on /data.");
  }
}
