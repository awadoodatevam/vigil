package vigil.incidents;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vigil.incidents.api.AlertCopy;
import vigil.incidents.api.HypothesisCopy;
import vigil.incidents.api.IdentityPairCopy;

class IncidentTitleTest {

  private static final Instant T0 = Instant.parse("2026-10-22T10:00:00Z");
  private static final HypothesisCopy SRV_DB_01 =
      new HypothesisCopy(1, "srv-db-01", "Subscriber DB server", "server", "critical");

  private static AlertCopy alert(
      String key, String type, String typeName, String component, Instant firedAt) {
    return new AlertCopy(
        UUID.randomUUID(),
        "zabbix",
        key,
        type,
        typeName,
        typeName + " on " + component,
        component,
        new IdentityPairCopy("component", component),
        firedAt);
  }

  /** The members of the storm-like incident, in member order. */
  private static List<AlertCopy> storm() {
    return List.of(
        alert("1", "disk_full", "Disk full", "srv-db-01", T0.minusSeconds(60)),
        alert(
            "2", "db_write_errors", "Database write errors", "db-subscriber", T0.minusSeconds(50)),
        alert(
            "3", "db_write_errors", "Database write errors", "db-subscriber", T0.minusSeconds(40)),
        alert(
            "4",
            "balance_api_errors",
            "Balance-check API errors",
            "api-balance",
            T0.minusSeconds(30)));
  }

  @Test
  @DisplayName("DM-R-20 with a hypothesis: the primary alert type of the hypothesis component")
  void withHypothesis() {
    assertThat(IncidentTitle.title(storm(), SRV_DB_01, 2))
        .isEqualTo("Subscriber DB server: Disk full — 2 services affected");
    assertThat(IncidentTitle.title(storm(), SRV_DB_01, 1))
        .isEqualTo("Subscriber DB server: Disk full — 1 service affected");
    assertThat(IncidentTitle.title(storm(), SRV_DB_01, 0))
        .isEqualTo("Subscriber DB server: Disk full");
  }

  @Test
  @DisplayName("DM-R-20 two types tied on the hypothesis component: the type key first")
  void tiedTypes() {
    List<AlertCopy> members =
        List.of(
            alert("1", "disk_full", "Disk full", "srv-db-01", T0),
            alert("2", "cpu_high", "CPU high", "srv-db-01", T0),
            alert("3", "db_write_errors", "Database write errors", "db-subscriber", T0),
            alert("4", "db_write_errors", "Database write errors", "db-subscriber", T0));
    assertThat(IncidentTitle.title(members, SRV_DB_01, 0))
        .isEqualTo("Subscriber DB server: CPU high");
  }

  @Test
  @DisplayName("DM-R-20 no member on the hypothesis component: the most frequent type of all")
  void noMemberOnComponent() {
    List<AlertCopy> members = storm().subList(1, 4);
    assertThat(IncidentTitle.title(members, SRV_DB_01, 2))
        .isEqualTo("Subscriber DB server: Database write errors — 2 services affected");
  }

  @Test
  @DisplayName("DM-R-20 without a hypothesis: the first identity value of the earliest member")
  void withoutHypothesis() {
    List<AlertCopy> members =
        List.of(
            alert("1", "db_write_errors", "Database write errors", "db-subscriber", T0),
            alert("2", "disk_full", "Disk full", "srv-db-01", T0.minusSeconds(5)));
    assertThat(IncidentTitle.title(members, null, 0)).isEqualTo("srv-db-01: Disk full");
  }

  @Test
  @DisplayName("DM-R-20 the same fired_at: the source kind first, then the alert key")
  void tiesOfTheEarliest() {
    AlertCopy zabbix = alert("a", "disk_full", "Disk full", "srv-db-01", T0);
    AlertCopy alertmanager =
        new AlertCopy(
            UUID.randomUUID(),
            "alertmanager",
            "z",
            "ussd_timeouts",
            "USSD timeouts",
            "t",
            "app-ussd",
            new IdentityPairCopy("instance", "app-ussd"),
            T0);
    assertThat(IncidentTitle.title(List.of(zabbix, alertmanager), null, 0))
        .isEqualTo("app-ussd: USSD timeouts");
    AlertCopy second = alert("b", "cpu_high", "CPU high", "srv-app-01", T0);
    assertThat(IncidentTitle.title(List.of(second, zabbix), null, 0))
        .isEqualTo("srv-db-01: Disk full");
  }

  @Test
  @DisplayName("DM-R-20 a member without identity pair: its alert title")
  void withoutIdentityPair() {
    AlertCopy alert =
        new AlertCopy(
            UUID.randomUUID(),
            "zabbix",
            "1",
            "disk_full",
            "Disk full",
            "Free space below 1 GiB",
            null,
            null,
            T0);
    assertThat(IncidentTitle.title(List.of(alert), null, 0))
        .isEqualTo("Free space below 1 GiB: Disk full");
  }

  @Test
  @DisplayName("DM-R-20 a title longer than 200 characters is cut to 200")
  void cut() {
    AlertCopy alert =
        new AlertCopy(
            UUID.randomUUID(),
            "zabbix",
            "1",
            "disk_full",
            "Disk full",
            "t".repeat(239),
            null,
            null,
            T0);
    String title = "t".repeat(239) + ": Disk full";
    assertThat(title).hasSize(250);
    assertThat(IncidentTitle.title(List.of(alert), null, 0)).isEqualTo(title.substring(0, 200));
  }
}
