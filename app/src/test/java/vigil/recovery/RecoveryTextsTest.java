package vigil.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RecoveryTextsTest {

  private static final String CHECK = "Subscriber DB services recovered";
  private static final String ZABBIX = "Zabbix — Jazz lab";
  private static final String ALERTMANAGER = "Alertmanager — Jazz lab";

  @Test
  @DisplayName("DC-TX-03 the recovery condition texts, evidence summaries and source system")
  void texts() {
    assertThat(RecoveryTexts.incidentAlerts(true, 0))
        .isEqualTo("Every alert of the incident is resolved at its source");
    assertThat(RecoveryTexts.incidentAlerts(false, 1))
        .isEqualTo("1 alert of the incident is still firing");
    assertThat(RecoveryTexts.incidentAlerts(false, 2))
        .isEqualTo("2 alerts of the incident are still firing");

    String probe = "Synthetic USSD sessions";
    assertThat(RecoveryTexts.componentAlerts(true, 0, probe, List.of()))
        .isEqualTo("No alert of Synthetic USSD sessions is firing");
    assertThat(RecoveryTexts.componentAlerts(false, 1, probe, List.of()))
        .isEqualTo("1 alert of Synthetic USSD sessions is firing");
    assertThat(RecoveryTexts.componentAlerts(false, 2, probe, List.of()))
        .isEqualTo("2 alerts of Synthetic USSD sessions are firing");

    String report = "Report export job";
    List<String> types = List.of("Report export job failed", "Disk full");
    assertThat(RecoveryTexts.componentAlerts(true, 0, report, types))
        .isEqualTo("No Disk full or Report export job failed alert of Report export job is firing");
    assertThat(RecoveryTexts.componentAlerts(false, 1, report, types))
        .isEqualTo("1 Disk full or Report export job failed alert of Report export job is firing");
    assertThat(RecoveryTexts.componentAlerts(false, 2, report, types))
        .isEqualTo(
            "2 Disk full or Report export job failed alerts of Report export job are firing");

    assertThat(RecoveryTexts.guard(List.of())).isEqualTo("Every monitoring source is healthy");
    assertThat(RecoveryTexts.guard(List.of(ZABBIX))).isEqualTo("Zabbix — Jazz lab not healthy");
    assertThat(RecoveryTexts.guard(List.of(ZABBIX, ALERTMANAGER)))
        .isEqualTo("Alertmanager — Jazz lab, Zabbix — Jazz lab not healthy");

    Duration window = Duration.ofSeconds(60);
    Duration maxWait = Duration.ofSeconds(300);
    assertThat(RecoveryTexts.recovered(CHECK, window))
        .isEqualTo(
            "Recovered: 'Subscriber DB services recovered' held for 60 s with every monitoring"
                + " source healthy.");
    assertThat(
            RecoveryTexts.notRecovered(
                maxWait,
                List.of(
                    "1 alert of the incident is still firing",
                    RecoveryTexts.guard(List.of(ZABBIX))),
                window))
        .isEqualTo(
            "Not recovered within 5 min: 1 alert of the incident is still firing; Zabbix — Jazz"
                + " lab not healthy.");
    assertThat(RecoveryTexts.notRecovered(maxWait, List.of(), window))
        .isEqualTo("Not recovered within 5 min: the conditions held for less than 60 s.");
    assertThat(RecoveryTexts.duration(Duration.ofSeconds(119))).isEqualTo("119 s");
    assertThat(RecoveryTexts.duration(Duration.ofSeconds(150))).isEqualTo("2 min 30 s");

    assertThat(RecoveryTexts.sourceSystem(List.of(ZABBIX, ALERTMANAGER)))
        .isEqualTo("Monitoring: Alertmanager — Jazz lab, Zabbix — Jazz lab");
    assertThat(RecoveryTexts.sourceSystem(List.of())).isEqualTo("Monitoring: none");
    assertThat(RecoveryTexts.sourceSystem(List.of("x".repeat(300)))).hasSize(200);
  }

  @Test
  @DisplayName("DC-TX-10 the audit summaries of recovery_verified")
  void auditSummaries() {
    assertThat(RecoveryTexts.auditSummary(true, CHECK))
        .isEqualTo("Recovered: 'Subscriber DB services recovered'.");
    assertThat(RecoveryTexts.auditSummary(false, CHECK))
        .isEqualTo("Not recovered: 'Subscriber DB services recovered'.");
  }
}
