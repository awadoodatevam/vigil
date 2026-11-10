package vigil.aigateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vigil.aigateway.api.DataCategory;

class DataCategoryTest {

  @Test
  @DisplayName("DM-R-42 exactly the six data categories with the display names of 40-demo-cut")
  void categories() {
    assertThat(Arrays.stream(DataCategory.values()).map(c -> c.key() + " " + c.displayName()))
        .containsExactly(
            "alert_data Alert data",
            "service_context Service context",
            "diagnostic_output Diagnostic output",
            "runbook_text Runbook text",
            "incident_history Incident history",
            "incident_record Incident record");
    for (DataCategory category : DataCategory.values()) {
      assertThat(DataCategory.fromKey(category.key())).contains(category);
    }
    assertThat(DataCategory.fromKey("Alert data")).isEmpty();
    assertThat(DataCategory.fromKey(null)).isEmpty();
  }
}
