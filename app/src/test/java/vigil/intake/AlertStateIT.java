package vigil.intake;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.LongStream;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.node.ObjectNode;
import vigil.intake.IntakeFixture.Recording;
import vigil.intake.api.AlertReceived;
import vigil.intake.api.AlertStateChanged;
import vigil.intake.api.AlertStatus;
import vigil.intake.api.AlertView;
import vigil.intake.api.IdentityPair;
import vigil.intake.api.IntakeService;
import vigil.intake.api.MonitoringKind;
import vigil.shared.Ids;
import vigil.shared.TenantId;
import vigil.testsupport.MutableClock;
import vigil.testsupport.TestSecrets;
import vigil.testsupport.VigilDatabase;
import vigil.testsupport.VigilTestApp;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AlertStateIT {

  private static final MutableClock CLOCK = new MutableClock(Instant.parse("2026-10-21T10:00:00Z"));

  private WireMockServer wiremock;
  private VigilDatabase database;
  private TestSecrets secrets;
  private VigilTestApp app;
  private JdbcClient postgres;
  private IntakeService intake;
  private IntakeFixture.Events events;
  private TenantId tenantA;
  private TenantId tenantB;

  private Recording firing;
  private Recording resolved;
  private UUID id;
  private UUID id2;
  private AlertView created;
  private AlertView afterRepeat;
  private AlertView afterResolve;
  private AlertView afterOrphan;
  private AlertView second;
  private AlertView noSourceTime;
  private Instant firstAt;
  private Instant resolveAt;
  private Instant noSourceTimeAt;
  private Map<String, String> applications;

  /** The test clock and the event recorder. */
  static class TestBeans {

    @Bean
    @Primary
    Clock testClock() {
      return CLOCK;
    }

    @Bean
    IntakeFixture.Events intakeEvents() {
      return new IntakeFixture.Events();
    }
  }

  @BeforeAll
  void start() {
    wiremock = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort());
    wiremock.start();
    IntakeFixture.stub(wiremock);
    database = VigilDatabase.fresh();
    secrets = IntakeFixture.secrets(database, "", "-b");
    app = VigilTestApp.start(database, secrets.folder(), List.of(), TestBeans.class);
    app.awaitStarted();
    postgres = database.postgres();
    intake = app.context().getBean(IntakeService.class);
    events = app.context().getBean(IntakeFixture.Events.class);
    String address = "http://127.0.0.1:" + wiremock.port();
    tenantA = IntakeFixture.create(app, "Tenant A", "a.state.test", address, "");
    tenantB = IntakeFixture.create(app, "Tenant B", "b.state.test", address, "-b");
    IntakeFixture.awaitFirstTests(intake, tenantA);
    IntakeFixture.awaitFirstTests(intake, tenantB);
    firing = IntakeFixture.recording("0001-zabbix-lab.json");
    resolved =
        IntakeFixture.recordings().stream()
            .filter(r -> r.sourceKey().equals(IntakeFixture.ZABBIX))
            .filter(r -> r.tree().get("event_id").stringValue().equals("36"))
            .filter(r -> r.tree().get("event_value").stringValue().equals("0"))
            .findFirst()
            .orElseThrow();
  }

  @AfterAll
  void stop() {
    app.close();
    wiremock.stop();
  }

  private int zabbix(TenantId tenant, String suffix, byte[] body) {
    return intake.receiveWebhook(
        tenant,
        IntakeFixture.ZABBIX,
        secrets.value("webhook-zabbix" + suffix),
        "application/json",
        body);
  }

  /** The application of the latest normalised alert of tenant A. */
  private String lastApplication() {
    return postgres
        .sql(
            "SELECT application FROM intake.normalised_alert WHERE tenant_id = :t"
                + " ORDER BY id DESC LIMIT 1")
        .param("t", tenantA.value())
        .query(String.class)
        .single();
  }

  private List<Map<String, Object>> stateChanges(UUID alertId) {
    return postgres
        .sql(
            "SELECT from_status, to_status, at, source_time, raw_alert_id FROM"
                + " intake.alert_state_change WHERE tenant_id = :t AND alert_id = :id ORDER BY id")
        .param("t", tenantA.value())
        .param("id", alertId)
        .query()
        .listOfRows();
  }

  private AlertView view(UUID alertId) {
    return intake.alert(tenantA, alertId).orElseThrow();
  }

  @Test
  @Order(1)
  @DisplayName("DM-E21-R1 the four cases of applying an entry")
  void fourCases() {
    firstAt = CLOCK.advance(Duration.ofSeconds(1));
    assertThat(zabbix(tenantA, "", firing.bytes())).isEqualTo(1);
    assertThat(lastApplication()).isEqualTo("created_alert");
    created = intake.alertsAfter(tenantA, 0, 100).getLast();
    id = created.id();
    assertThat(stateChanges(id)).hasSize(1);

    CLOCK.advance(Duration.ofSeconds(1));
    zabbix(tenantA, "", firing.bytes());
    assertThat(lastApplication()).isEqualTo("repeat_firing");
    afterRepeat = view(id);
    assertThat(stateChanges(id)).hasSize(1);

    resolveAt = CLOCK.advance(Duration.ofSeconds(1));
    zabbix(tenantA, "", resolved.bytes());
    assertThat(lastApplication()).isEqualTo("resolved_alert");
    afterResolve = view(id);
    assertThat(stateChanges(id)).hasSize(2);

    CLOCK.advance(Duration.ofSeconds(1));
    zabbix(tenantA, "", resolved.bytes());
    assertThat(lastApplication()).isEqualTo("orphan_resolved");
    afterOrphan = view(id);
    assertThat(stateChanges(id)).hasSize(2);

    CLOCK.advance(Duration.ofSeconds(1));
    zabbix(tenantA, "", firing.bytes());
    assertThat(lastApplication()).isEqualTo("created_alert");
    second = intake.alertsAfter(tenantA, created.sequence(), 100).getFirst();
    id2 = second.id();

    ObjectNode own = (ObjectNode) firing.tree();
    own.put("event_id", "9001");
    own.remove("event_date");
    noSourceTimeAt = CLOCK.advance(Duration.ofSeconds(1));
    zabbix(tenantA, "", IntakeFixture.JSON.writeValueAsBytes(own));
    noSourceTime = intake.alertsAfter(tenantA, second.sequence(), 100).getFirst();
    applications =
        Map.of(
            "a",
            "created_alert",
            "b",
            "repeat_firing",
            "c",
            "resolved_alert",
            "d",
            "orphan_resolved");

    assertThat(created.status()).isEqualTo(AlertStatus.FIRING);
    assertThat(afterRepeat.entryCount()).isEqualTo(2);
    assertThat(afterResolve.status()).isEqualTo(AlertStatus.RESOLVED);
    assertThat(afterOrphan).isEqualTo(afterResolve);
    assertThat(second.sequence()).isEqualTo(created.sequence() + 1);
    assertThat(noSourceTime.firedAt()).isEqualTo(noSourceTimeAt);
  }

  @Test
  @Order(2)
  @DisplayName("DM-E-21 the alert: its fields from the first firing entry and its sequence")
  void alertFields() {
    assertThat(created.sequence()).isEqualTo(1);
    assertThat(created.sourceKind()).isEqualTo(MonitoringKind.ZABBIX);
    assertThat(created.alertKey()).isEqualTo("36");
    assertThat(created.alertType()).isEqualTo("db_write_errors");
    assertThat(created.identityPairs())
        .containsExactly(
            new IdentityPair("component", "db-subscriber"), new IdentityPair("host", "lab-db-01"));
    assertThat(created.title()).isEqualTo("Database write errors on schema bundle_catalog");
    assertThat(created.firedAt()).isEqualTo(Instant.parse("2026-10-15T19:00:43Z"));
    assertThat(created.firstReceivedAt()).isEqualTo(firstAt);
    assertThat(created.resolvedAt()).isNull();
    assertThat(created.resolveReceivedAt()).isNull();
    assertThat(created.entryCount()).isEqualTo(1);
    assertThat(afterResolve.resolvedAt()).isEqualTo(Instant.parse(resolvedTime(resolved)));
    assertThat(afterResolve.resolveReceivedAt()).isEqualTo(resolveAt);
    assertThat(afterResolve.entryCount()).isEqualTo(3);
    assertThat(afterResolve.firedAt()).isEqualTo(created.firedAt());
  }

  private static String resolvedTime(Recording recording) {
    String date = recording.tree().get("recovery_date").stringValue().replace('.', '-');
    return date + "T" + recording.tree().get("recovery_time").stringValue() + "Z";
  }

  @Test
  @Order(3)
  @DisplayName("DM-E-22 the alert state changes: creation and resolution")
  void stateChangeRecords() {
    List<Map<String, Object>> changes = stateChanges(id);
    assertThat(changes).hasSize(2);
    assertThat(changes.get(0).get("from_status")).isNull();
    assertThat(changes.get(0).get("to_status")).isEqualTo("firing");
    assertThat(((Timestamp) changes.get(0).get("at")).toInstant()).isEqualTo(firstAt);
    assertThat(((Timestamp) changes.get(0).get("source_time")).toInstant())
        .isEqualTo(created.firedAt());
    assertThat(changes.get(0).get("raw_alert_id")).isNotNull();
    assertThat(changes.get(1).get("from_status")).isEqualTo("firing");
    assertThat(changes.get(1).get("to_status")).isEqualTo("resolved");
    assertThat(((Timestamp) changes.get(1).get("at")).toInstant()).isEqualTo(resolveAt);
    assertThat(((Timestamp) changes.get(1).get("source_time")).toInstant())
        .isEqualTo(afterResolve.resolvedAt());
    assertThat(stateChanges(id2)).hasSize(1);
  }

  @Test
  @Order(4)
  @DisplayName("DM-SM-04 alert transitions: firing, then resolved, which is final")
  void transitions() {
    assertThat(List.of(created.status(), afterRepeat.status(), afterResolve.status()))
        .containsExactly(AlertStatus.FIRING, AlertStatus.FIRING, AlertStatus.RESOLVED);
    assertThat(afterOrphan.status()).isEqualTo(AlertStatus.RESOLVED);
    assertThat(view(id).status()).isEqualTo(AlertStatus.RESOLVED);
    assertThat(second.status()).isEqualTo(AlertStatus.FIRING);
    assertThat(second.id()).isNotEqualTo(id);
  }

  @Test
  @Order(5)
  @DisplayName("PM-M06-05 firing and resolved state come from the source's own notifications")
  void stateFromNotifications() {
    assertThat(applications.values())
        .containsExactlyInAnyOrder(
            "created_alert", "repeat_firing", "resolved_alert", "orphan_resolved");
    assertThat(afterResolve.resolvedAt()).isNotEqualTo(afterResolve.resolveReceivedAt());
    assertThat(noSourceTime.firedAt()).isEqualTo(noSourceTime.firstReceivedAt());
  }

  @Test
  @Order(6)
  @DisplayName("DM-E21-R2 at most one firing alert per source and alert key")
  void oneFiringAlert() {
    assertThatThrownBy(
            () ->
                postgres
                    .sql(
                        "INSERT INTO intake.alert (id, tenant_id, created_at, updated_at,"
                            + " source_id, source_kind, alert_key, alert_type, identity_pairs,"
                            + " title, status, fired_at, first_received_at, entry_count, sequence)"
                            + " SELECT :id, tenant_id, created_at, updated_at, source_id,"
                            + " source_kind, alert_key, alert_type, identity_pairs, title, status,"
                            + " fired_at, first_received_at, 1, 999 FROM intake.alert"
                            + " WHERE id = :firing")
                    .param("id", Ids.newId())
                    .param("firing", id2)
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("alert_firing_idx");
  }

  @Test
  @Order(7)
  @DisplayName("AR-E-01 AlertReceived with the alert id and its sequence")
  void alertReceived() {
    assertThat(alertEvents().stream().filter(e -> e instanceof AlertReceived).toList())
        .containsExactly(new AlertReceived(tenantA, id, 1), new AlertReceived(tenantA, id2, 2));
  }

  @Test
  @Order(8)
  @DisplayName("AR-E-02 AlertStateChanged with the alert id and status, in order")
  void alertStateChanged() {
    assertThat(alertEvents())
        .containsExactly(
            new AlertReceived(tenantA, id, 1),
            new AlertStateChanged(tenantA, id, AlertStatus.FIRING),
            new AlertStateChanged(tenantA, id, AlertStatus.RESOLVED),
            new AlertReceived(tenantA, id2, 2),
            new AlertStateChanged(tenantA, id2, AlertStatus.FIRING));
  }

  private List<Object> alertEvents() {
    return events.of(tenantA).stream()
        .filter(
            event ->
                switch (event) {
                  case AlertReceived e -> e.alertId().equals(id) || e.alertId().equals(id2);
                  case AlertStateChanged e -> e.alertId().equals(id) || e.alertId().equals(id2);
                  default -> false;
                })
        .toList();
  }

  @Test
  @Order(9)
  @DisplayName("PM-I-07 the alerts after a sequence, in order, at most 100")
  void alertsAfter() {
    Recording alertmanager =
        IntakeFixture.recordings().stream()
            .filter(r -> r.sourceKey().equals(IntakeFixture.ALERTMANAGER))
            .filter(r -> r.tree().get("alerts").get(0).get("status").stringValue().equals("firing"))
            .findFirst()
            .orElseThrow();
    for (int i = 0; i < 150; i++) {
      intake.receiveWebhook(
          tenantB,
          IntakeFixture.ALERTMANAGER,
          secrets.value("webhook-alertmanager-b"),
          "application/json",
          IntakeFixture.withFingerprint(alertmanager, "page-" + i));
    }
    assertThat(intake.alertsAfter(tenantB, 0, 100).stream().map(AlertView::sequence))
        .containsExactlyElementsOf(LongStream.rangeClosed(1, 100).boxed().toList());
    List<AlertView> next = intake.alertsAfter(tenantB, 100, 100);
    assertThat(next.stream().map(AlertView::sequence))
        .containsExactlyElementsOf(LongStream.rangeClosed(101, 150).boxed().toList());
    assertThat(intake.alertsAfter(tenantB, 150, 1)).isEmpty();
    assertThat(intake.alert(tenantB, next.getFirst().id())).contains(next.getFirst());
    assertThat(intake.alert(tenantB, id)).isEmpty();
    assertThat(intake.alertsAfter(tenantB, 0, 100)).noneMatch(alert -> alert.id().equals(id2));
    assertThat(intake.alertsAfter(tenantA, 0, 100))
        .noneMatch(alert -> alert.alertKey().startsWith("page-"));
    assertThatThrownBy(() -> intake.alertsAfter(tenantB, 0, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> intake.alertsAfter(tenantB, 0, 101))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> intake.alertsAfter(tenantB, -1, 10))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
