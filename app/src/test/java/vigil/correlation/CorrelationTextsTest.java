package vigil.correlation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vigil.correlation.api.Decision;

class CorrelationTextsTest {

  private static final String TITLE = "Disk full on srv-db-01";

  @Test
  @DisplayName("DC-TX-10 the summaries of alert_grouped and hypothesis_changed")
  void summaries() {
    assertThat(CorrelationTexts.alertGrouped(TITLE, "zabbix", Decision.CREATED_INCIDENT, 0))
        .isEqualTo("Alert 'Disk full on srv-db-01' (Zabbix) created a new incident.");
    assertThat(CorrelationTexts.alertGrouped(TITLE, "alertmanager", Decision.JOINED, 0))
        .isEqualTo("Alert 'Disk full on srv-db-01' (Alertmanager) joined the incident.");
    assertThat(CorrelationTexts.alertGrouped(TITLE, "zabbix", Decision.REOPENED, 0))
        .isEqualTo("Alert 'Disk full on srv-db-01' (Zabbix) reopened the incident.");
    assertThat(CorrelationTexts.alertGrouped(TITLE, "zabbix", Decision.JOINED, 1))
        .isEqualTo("Alert 'Disk full on srv-db-01' (Zabbix) joined the incident; 1 group merged.");
    assertThat(CorrelationTexts.alertGrouped(TITLE, "zabbix", Decision.JOINED, 3))
        .isEqualTo("Alert 'Disk full on srv-db-01' (Zabbix) joined the incident; 3 groups merged.");
    assertThat(CorrelationTexts.hypothesisChanged(null, "Subscriber DB server"))
        .isEqualTo("Hypothesis: none → Subscriber DB server.");
    assertThat(CorrelationTexts.hypothesisChanged("Subscriber database", "Subscriber DB server"))
        .isEqualTo("Hypothesis: Subscriber database → Subscriber DB server.");
    String summary =
        CorrelationTexts.alertGrouped("x".repeat(400), "zabbix", Decision.CREATED_INCIDENT, 0);
    assertThat(summary.codePointCount(0, summary.length())).isEqualTo(300);
    assertThat(summary).isEqualTo("Alert '" + "x".repeat(293));
  }
}
