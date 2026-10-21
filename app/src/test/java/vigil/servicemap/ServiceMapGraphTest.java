package vigil.servicemap;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vigil.servicemap.api.AffectedService;
import vigil.servicemap.api.ComponentView;
import vigil.servicemap.api.Criticality;
import vigil.servicemap.api.IdentityKey;
import vigil.servicemap.api.Relation;
import vigil.servicemap.api.RelationKind;
import vigil.servicemap.api.ServiceView;
import vigil.servicemap.api.SourceKind;

class ServiceMapGraphTest {

  static final List<String> STORM =
      List.of(
          "srv-db-01",
          "db-subscriber",
          "api-balance",
          "api-bundle",
          "ussd-gw",
          "sms-queue",
          "ussd-probe");

  private static final ServiceMapGraph GRAPH = dataGraph();

  /** The graph of the data file as generation 1. */
  static ServiceMapGraph dataGraph() {
    ServiceMapFile.Read read =
        ServiceMapFile.read(ServiceMapFileTest.data().getBytes(StandardCharsets.UTF_8));
    assertThat(read.problems()).isEmpty();
    return ServiceMapGraph.of(1, read.content());
  }

  private static String match(SourceKind kind, IdentityKey... pairs) {
    return GRAPH.match(kind, List.of(pairs)).componentKey();
  }

  private static IdentityKey pair(String name, String value) {
    return new IdentityKey(name, value);
  }

  private static Relation relation(RelationKind kind, String... path) {
    return new Relation(kind, List.of(path));
  }

  @Test
  @DisplayName(
      "DM-R-05 the first pair that is an identity key of the source kind gives the component")
  void matching() {
    assertThat(
            match(SourceKind.ZABBIX, pair("component", "db-subscriber"), pair("host", "lab-db-01")))
        .isEqualTo("db-subscriber");
    assertThat(match(SourceKind.ZABBIX, pair("component", "nope"), pair("host", "lab-db-01")))
        .isEqualTo("srv-db-01");
    assertThat(match(SourceKind.ALERTMANAGER, pair("host", "lab-db-01"))).isNull();
    assertThat(
            match(
                SourceKind.ALERTMANAGER,
                pair("component", "ussd-probe"),
                pair("instance", "lab-probe-01:9200")))
        .isEqualTo("ussd-probe");
    assertThat(match(SourceKind.ZABBIX, pair("component", "DB-SUBSCRIBER"))).isNull();
    assertThat(match(SourceKind.ZABBIX)).isNull();
    assertThat(GRAPH.match(SourceKind.ZABBIX, List.of()).generation()).isEqualTo(1);
  }

  @Test
  @DisplayName("DM-R-04 the pairs are taken in the order the caller gives them")
  void pairOrder() {
    assertThat(
            match(SourceKind.ZABBIX, pair("host", "lab-db-01"), pair("component", "db-subscriber")))
        .isEqualTo("srv-db-01");
  }

  @Test
  @DisplayName("DM-R-07 the relation with the shortest path, first by key order")
  void relations() {
    assertThat(GRAPH.relation("api-balance", "srv-db-01"))
        .isEqualTo(relation(RelationKind.DEPENDS_ON, "api-balance", "db-subscriber", "srv-db-01"));
    assertThat(GRAPH.relation("ussd-probe", "srv-db-01"))
        .isEqualTo(
            relation(
                RelationKind.DEPENDS_ON,
                "ussd-probe",
                "ussd-gw",
                "api-balance",
                "db-subscriber",
                "srv-db-01"));
    assertThat(GRAPH.relation("srv-db-01", "sms-queue"))
        .isEqualTo(
            relation(RelationKind.DEPENDED_ON_BY, "srv-db-01", "db-subscriber", "sms-queue"));
    assertThat(GRAPH.relation("api-balance", "api-bundle")).isEqualTo(relation(RelationKind.NONE));
    assertThat(GRAPH.relation("db-subscriber", "db-subscriber"))
        .isEqualTo(relation(RelationKind.SAME_COMPONENT, "db-subscriber"));
    assertThat(GRAPH.relation("nope", "nope"))
        .isEqualTo(relation(RelationKind.SAME_COMPONENT, "nope"));
    assertThat(GRAPH.relation("nope", "srv-db-01")).isEqualTo(relation(RelationKind.NONE));
  }

  @Test
  @DisplayName("DM-E28-R2 runs_on is a dependency in the answers")
  void runsOnIsADependency() {
    assertThat(GRAPH.relation("api-balance", "srv-api-01"))
        .isEqualTo(relation(RelationKind.DEPENDS_ON, "api-balance", "srv-api-01"));
  }

  @Test
  @DisplayName("DD-K-04 the storm components are one related group; report-export is in none")
  void stormGroup() {
    Set<String> reached = new HashSet<>(List.of(STORM.getFirst()));
    Deque<String> queue = new ArrayDeque<>(reached);
    while (!queue.isEmpty()) {
      String current = queue.poll();
      for (String other : STORM) {
        if (GRAPH.relation(current, other).kind() != RelationKind.NONE && reached.add(other)) {
          queue.add(other);
        }
      }
    }
    assertThat(reached).containsExactlyInAnyOrderElementsOf(STORM);
    for (String storm : STORM) {
      assertThat(GRAPH.relation("report-export", storm).kind()).isEqualTo(RelationKind.NONE);
    }
  }

  @Test
  @DisplayName("PM-M07-03 the direct and indirect dependents of a component")
  void dependents() {
    assertThat(GRAPH.dependents("srv-db-01"))
        .containsExactly(
            "api-balance", "api-bundle", "db-subscriber", "sms-queue", "ussd-gw", "ussd-probe");
    assertThat(GRAPH.dependents("ussd-probe")).isEmpty();
    assertThat(GRAPH.dependents("nope")).isEmpty();
  }

  @Test
  @DisplayName("PM-M07-04 the services affected by a set of components (DD-K-06)")
  void affectedServices() {
    assertThat(GRAPH.affectedServices(STORM))
        .containsExactly(
            new AffectedService(
                "svc-balance-check",
                "Balance Check (USSD)",
                List.of("api-balance", "db-subscriber", "ussd-gw")),
            new AffectedService(
                "svc-bundle-purchase",
                "Bundle Purchase",
                List.of("api-bundle", "db-subscriber", "ussd-gw")),
            new AffectedService(
                "svc-sms-notifications", "SMS Notifications", List.of("sms-queue")));
    assertThat(GRAPH.affectedServices(List.of("report-export")))
        .containsExactly(
            new AffectedService(
                "svc-mgmt-reports", "Management Reports", List.of("report-export")));
    assertThat(GRAPH.affectedServices(List.of("srv-db-01"))).isEmpty();
  }

  @Test
  @DisplayName("DM-E26-R1 a server's own target, else the target of the server it runs on")
  void targets() {
    assertThat(GRAPH.target("srv-db-01")).contains("lab-db-01");
    assertThat(GRAPH.target("db-subscriber")).contains("lab-db-01");
    assertThat(GRAPH.target("api-balance")).isEmpty();
    assertThat(GRAPH.target("nope")).isEmpty();
  }

  @Test
  @DisplayName("PM-M07-05 owner team and criticality of a component or service")
  void ownersAndCriticality() {
    ComponentView server = GRAPH.component("srv-db-01").orElseThrow();
    assertThat(server.ownerTeamKey()).isEqualTo("db-ops");
    assertThat(server.criticality()).isEqualTo(Criticality.CRITICAL);
    ComponentView reporting = GRAPH.component("srv-rpt-01").orElseThrow();
    assertThat(reporting.ownerTeamKey()).isNull();
    assertThat(reporting.criticality()).isEqualTo(Criticality.LOW);
    ServiceView reports = GRAPH.service("svc-mgmt-reports").orElseThrow();
    assertThat(reports.ownerTeamKey()).isEqualTo("it-apps");
    assertThat(reports.criticality()).isNull();
  }
}
