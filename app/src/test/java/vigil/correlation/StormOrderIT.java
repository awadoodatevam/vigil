package vigil.correlation;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.JsonNode;
import vigil.correlation.api.CorrelationService;
import vigil.incidents.api.AffectedServiceCopy;
import vigil.incidents.api.IncidentService;
import vigil.incidents.api.IncidentState;
import vigil.incidents.api.IncidentView;
import vigil.incidents.api.MembershipView;
import vigil.incidents.api.Severity;
import vigil.intake.IntakeFixture;
import vigil.intake.IntakeFixture.Recording;
import vigil.testsupport.MutableClock;
import vigil.testsupport.TestSecrets;
import vigil.testsupport.VigilDatabase;
import vigil.testsupport.VigilTestApp;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StormOrderIT {

  private static final MutableClock CLOCK = new MutableClock(Instant.parse("2026-10-22T10:00:00Z"));
  private static final int ORDERS = 20;
  private static final Duration DECIDED_WAIT = Duration.ofSeconds(60);

  private WireMockServer wiremock;
  private VigilTestApp app;
  private TestSecrets secrets;
  private String address;
  private final List<Run> runs = new ArrayList<>();

  /** One order's result: the storm incident and the SL-F-3 incident with their members. */
  private record Run(
      int seed,
      List<IncidentView> notMerged,
      IncidentView storm,
      IncidentView report,
      List<MembershipView> stormMembers,
      List<MembershipView> reportMembers,
      Map<String, String> stormCounts,
      Map<String, List<Object>> finalReasons) {}

  /** The test clock. */
  static class TestBeans {

    @Bean
    @Primary
    Clock testClock() {
      return CLOCK;
    }
  }

  @BeforeAll
  void start() {
    wiremock = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort());
    wiremock.start();
    IntakeFixture.stub(wiremock);
    VigilDatabase database = VigilDatabase.fresh();
    secrets =
        IntakeFixture.secrets(
            database,
            IntStream.rangeClosed(1, ORDERS).mapToObj(n -> "-" + n).toArray(String[]::new));
    app = VigilTestApp.start(database, secrets.folder(), List.of(), TestBeans.class);
    app.awaitStarted();
    address = "http://127.0.0.1:" + wiremock.port();
  }

  @AfterAll
  void stop() {
    app.close();
    wiremock.stop();
  }

  /** The storm fed in the order of {@code new Random(seed)} to a new tenant. */
  private Run run(int seed) {
    CorrelationFixture fixture =
        CorrelationFixture.create(
            app, secrets, "storm-" + seed, "storm-" + seed + ".test", address, "-" + seed);
    List<Recording> storm = new ArrayList<>(CorrelationFixture.storm());
    assertThat(storm).hasSize(127);
    Collections.shuffle(storm, new Random(seed));
    for (Recording recording : storm) {
      fixture.post(recording);
    }
    fixture.awaitDecided(DECIDED_WAIT);
    CLOCK.advance(Duration.ofSeconds(41));
    IncidentService incidents = fixture.incidents;
    List<IncidentView> notMerged =
        incidents.incidents(fixture.tenant, EnumSet.complementOf(EnumSet.of(IncidentState.MERGED)));
    for (IncidentView incident : notMerged) {
      fixture.awaitState(incident.id(), IncidentState.OPEN);
    }
    notMerged =
        incidents.incidents(fixture.tenant, EnumSet.complementOf(EnumSet.of(IncidentState.MERGED)));
    IncidentView stormIncident =
        notMerged.stream()
            .filter(i -> "srv-db-01".equals(i.hypothesisComponentKey()))
            .findFirst()
            .orElse(null);
    IncidentView report =
        notMerged.stream().filter(i -> i != stormIncident).findFirst().orElse(null);
    List<MembershipView> stormMembers =
        stormIncident == null ? List.of() : incidents.members(fixture.tenant, stormIncident.id());
    List<MembershipView> reportMembers =
        report == null ? List.of() : incidents.members(fixture.tenant, report.id());
    Map<String, String> counts = new HashMap<>();
    if (stormIncident != null) {
      fixture
          .app
          .context()
          .getBean(CorrelationService.class)
          .latestHypothesisComputation(fixture.tenant, stormIncident.id())
          .orElseThrow()
          .dependentCounts()
          .forEach((key, count) -> counts.put(key, count.toString()));
    }
    Map<UUID, String> keys = new HashMap<>();
    List<MembershipView> all = new ArrayList<>(stormMembers);
    all.addAll(reportMembers);
    all.forEach(m -> keys.put(m.alertId(), m.alert().alertKey()));
    Map<String, List<Object>> reasons = new HashMap<>();
    for (MembershipView member : all) {
      reasons.put(member.alert().alertKey(), reason(member.finalReasonJson(), keys));
    }
    return new Run(
        seed, notMerged, stormIncident, report, stormMembers, reportMembers, counts, reasons);
  }

  /** The final reason with the related alert's key in place of its id. */
  private static List<Object> reason(String json, Map<UUID, String> keys) {
    JsonNode node = CorrelationFixture.JSON.readTree(json);
    if (node.get("relation").stringValue().equals("only_alert")) {
      return List.of("only_alert");
    }
    List<String> path = new ArrayList<>();
    node.get("relation_path").forEach(key -> path.add(key.stringValue()));
    return List.of(
        keys.get(UUID.fromString(node.get("related_alert_id").stringValue())),
        node.get("relation").stringValue(),
        path,
        node.get("time_delta").longValue());
  }

  @Test
  @Order(1)
  @DisplayName("PM-M08-06 the same grouping, hypothesis and impact for the storm in 20 orders")
  void sameResultInEveryOrder() {
    for (int seed = 1; seed <= ORDERS; seed++) {
      runs.add(run(seed));
    }
    for (Run run : runs) {
      assertThat(run.notMerged()).as("order %s", run.seed()).hasSize(2);
      assertThat(run.storm()).as("order %s", run.seed()).isNotNull();
      assertThat(run.stormMembers()).as("order %s", run.seed()).hasSize(126);
      assertThat(run.storm().hypothesisComponentKey()).isEqualTo("srv-db-01");
      assertThat(run.stormCounts()).containsEntry("srv-db-01", "6");
      assertThat(run.storm().affectedServices().stream().map(AffectedServiceCopy::serviceKey))
          .containsExactly("svc-balance-check", "svc-bundle-purchase", "svc-sms-notifications");
      assertThat(run.reportMembers()).as("order %s", run.seed()).hasSize(1);
    }
  }

  @Test
  @Order(2)
  @DisplayName("SL-A1-02 the storm grouped into one incident with its assignment; SL-F-3 separate")
  void stormIncident() {
    for (Run run : runs) {
      assertThat(run.storm().state()).isEqualTo(IncidentState.OPEN);
      assertThat(run.storm().severity()).isEqualTo(Severity.P1);
      assertThat(run.storm().ownerTeamKey()).isEqualTo("db-ops");
      assertThat(run.storm().informedTeamKeys()).containsExactly("vas-service-owners");
      assertThat(run.report().state()).isEqualTo(IncidentState.OPEN);
      assertThat(run.report().severity()).isEqualTo(Severity.P4);
      assertThat(run.report().ownerTeamKey()).isEqualTo("it-apps");
      assertThat(run.report().informedTeamKeys()).isEmpty();
    }
  }

  @Test
  @Order(3)
  @DisplayName("DM-R-13 the final grouping of the storm does not depend on arrival order")
  void finalReasonsIndependentOfOrder() {
    Map<String, List<Object>> first = runs.getFirst().finalReasons();
    assertThat(first).hasSize(127);
    for (Run run : runs) {
      assertThat(run.finalReasons()).as("order %s", run.seed()).isEqualTo(first);
    }
  }

  @Test
  @Order(4)
  @DisplayName("DM-R-14 the SL-F-3 incident stays separate from the storm in every order")
  void reportSeparate() {
    for (Run run : runs) {
      MembershipView report = run.reportMembers().getFirst();
      assertThat(report.alert().componentKey()).isEqualTo("report-export");
      assertThat(report.alert().title()).isEqualTo("Report export job failed");
      assertThat(run.stormMembers())
          .noneMatch(member -> member.alertId().equals(report.alertId()))
          .noneMatch(member -> "report-export".equals(member.alert().componentKey()));
    }
  }

  @Test
  @Order(5)
  @DisplayName("DD-K-04 the storm is one connected group: no storm member is only_alert")
  void connectedGroup() {
    for (Run run : runs) {
      for (MembershipView member : run.stormMembers()) {
        assertThat(run.finalReasons().get(member.alert().alertKey()))
            .isNotEqualTo(List.of("only_alert"));
      }
    }
  }

  @Test
  @Order(6)
  @DisplayName("DD-K-06 the storm impact and both titles")
  void titles() {
    for (Run run : runs) {
      assertThat(run.storm().title())
          .isEqualTo("Subscriber DB server: Disk full — 3 services affected");
      assertThat(run.report().title())
          .isEqualTo("Report export job: Report export job failed — 1 service affected");
      assertThat(run.storm().affectedServices().stream().map(AffectedServiceCopy::name))
          .containsExactly("Balance Check (USSD)", "Bundle Purchase", "SMS Notifications");
    }
  }
}
