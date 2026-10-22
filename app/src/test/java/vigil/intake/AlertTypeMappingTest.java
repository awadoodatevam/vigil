package vigil.intake;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vigil.intake.AlertTypeRule.Mapping;
import vigil.intake.api.MonitoringKind;

class AlertTypeMappingTest {

  private static final MonitoringKind ZABBIX = MonitoringKind.ZABBIX;

  private static Mapping zabbix(
      long order, String field, String operator, String value, String type) {
    return new Mapping(ZABBIX, order, field, operator, value, type);
  }

  private static String classify(List<Mapping> mappings, Map<String, String> fields) {
    return AlertTypeRule.classify(ZABBIX, mappings, fields);
  }

  @Test
  @DisplayName("DM-E17-R1 the first true mapping in order gives the type, else unclassified")
  void firstTrueMappingInOrder() {
    List<Mapping> outOfOrder =
        List.of(
            zabbix(2, "event_name", "starts_with", "Disk", "second"),
            zabbix(1, "event_name", "starts_with", "Disk", "first"));
    assertThat(classify(outOfOrder, Map.of("event_name", "Disk full"))).isEqualTo("first");

    Map<String, String> entry = Map.of("event_name", "Disk full on /data");
    assertThat(
            classify(List.of(zabbix(1, "event_name", "equals", "Disk full on /data", "t")), entry))
        .isEqualTo("t");
    assertThat(classify(List.of(zabbix(1, "event_name", "equals", "Disk full", "t")), entry))
        .isEqualTo("unclassified");
    assertThat(classify(List.of(zabbix(1, "event_name", "starts_with", "Disk full", "t")), entry))
        .isEqualTo("t");
    assertThat(classify(List.of(zabbix(1, "event_name", "starts_with", "full", "t")), entry))
        .isEqualTo("unclassified");
    assertThat(classify(List.of(zabbix(1, "event_name", "contains", "full on", "t")), entry))
        .isEqualTo("t");
    assertThat(classify(List.of(zabbix(1, "event_name", "contains", "/tmp", "t")), entry))
        .isEqualTo("unclassified");
    assertThat(
            classify(
                List.of(zabbix(1, "event_name", "starts_with", "Disk full", "t")),
                Map.of("event_name", "disk full")))
        .as("case-sensitive")
        .isEqualTo("unclassified");

    List<Mapping> missingFieldFirst =
        List.of(
            zabbix(1, "tag.service", "equals", "x", "missing"),
            zabbix(2, "event_name", "contains", "Disk", "next"));
    assertThat(classify(missingFieldFirst, entry)).isEqualTo("next");

    assertThat(classify(List.of(), entry)).isEqualTo("unclassified");

    List<Mapping> otherKind =
        List.of(
            new Mapping(MonitoringKind.ALERTMANAGER, 1, "event_name", "contains", "Disk", "am"),
            zabbix(2, "event_name", "contains", "Disk", "zabbix"));
    assertThat(classify(otherKind, entry)).isEqualTo("zabbix");

    assertThat(
            classify(
                List.of(zabbix(1, "tag.component", "equals", "db-subscriber", "db")),
                Map.of("event_name", "x", "tag.component", "db-subscriber")))
        .isEqualTo("db");
  }
}
