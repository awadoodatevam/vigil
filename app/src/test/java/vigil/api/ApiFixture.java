package vigil.api;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import vigil.correlation.CorrelationFixture;
import vigil.engine.EngineFixture;
import vigil.engine.TestHandlers;
import vigil.identity.api.IdentityService;
import vigil.identity.api.Permission;
import vigil.identity.api.UserView;
import vigil.incidents.api.DataCategory;
import vigil.incidents.api.EscalationCause;
import vigil.incidents.api.EvidenceKind;
import vigil.incidents.api.IncidentService;
import vigil.incidents.api.IncidentState;
import vigil.incidents.api.IncidentView;
import vigil.incidents.api.NotifiedPurpose;
import vigil.intake.IntakeFixture;
import vigil.intake.IntakeFixture.Recording;
import vigil.intake.api.AlertView;
import vigil.intake.api.IntakeService;
import vigil.intake.api.MonitoringKind;
import vigil.shared.Actor;
import vigil.shared.Ids;
import vigil.shared.Key;
import vigil.shared.TenantId;
import vigil.testsupport.ApiClient;
import vigil.testsupport.ApiClient.Request;
import vigil.testsupport.ApiClient.Response;
import vigil.testsupport.MutableClock;
import vigil.testsupport.TestSecrets;
import vigil.testsupport.VigilDatabase;
import vigil.testsupport.VigilTestApp;

/**
 * The API fixture of P-25 spec Section 6.13: the application of {@code engine/EngineWakeIT} (test
 * clock, test step handlers, WireMock connection tests) with one tenant that has the demo sources,
 * service map, routing, incident and correlation settings, its correlation worker and timer poll,
 * and the users {@code presenter}, {@code noc-l1} and {@code dbops-engineer}; helpers for the storm
 * and the workspace records.
 */
public final class ApiFixture implements AutoCloseable {

  public static final String HOST = "api.test";
  private static final Actor ROUTING = Actor.module("vigil-routing");
  private static final Actor NOTIFICATIONS = Actor.module("vigil-notifications");
  private static final Actor DIAGNOSTICS = Actor.module("vigil-diagnostics");
  private static final Duration DECIDED_WAIT = Duration.ofSeconds(60);
  private static final String SMS_QUEUE = "0009-zabbix-lab.json";
  private static final String API_BALANCE = "0017-alertmanager-lab.json";
  private static final AtomicInteger IDENTITIES = new AtomicInteger();

  /** The test clock bean. */
  static class ApiTestBeans {

    static volatile MutableClock clock;

    @Bean
    @Primary
    Clock testClock() {
      return clock;
    }
  }

  public final VigilTestApp app;
  public final WireMockServer wiremock;
  public final MutableClock clock;
  public final JdbcClient postgres;
  public final EngineFixture engine;
  public final TenantId tenant;
  public final TestHandlers handlers;
  public final ApiClient client;
  public final TransactionTemplate tx;
  private final IncidentService incidents;

  /** The storm's result: the storm incident, the SL-F-3 report incident and the merged ones. */
  public record Storm(UUID storm, UUID report, List<UUID> merged) {}

  public ApiFixture() {
    wiremock = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort());
    wiremock.start();
    IntakeFixture.stub(wiremock);
    clock = new MutableClock(Instant.now().truncatedTo(ChronoUnit.MILLIS));
    ApiTestBeans.clock = clock;
    VigilDatabase database = VigilDatabase.fresh();
    postgres = database.postgres();
    TestSecrets secrets = IntakeFixture.secrets(database, "");
    app =
        VigilTestApp.start(
            database, secrets.folder(), List.of(), ApiTestBeans.class, TestHandlers.Beans.class);
    app.awaitStarted();
    engine =
        EngineFixture.create(
            app,
            secrets,
            "API tenant",
            HOST,
            "http://127.0.0.1:" + wiremock.port(),
            "",
            users ->
                users
                    .user("presenter", "Demo Presenter", Permission.VIEW)
                    .user("noc-l1", "Hira Baig (NOC L1)", Permission.ACKNOWLEDGE)
                    .user(
                        "dbops-engineer",
                        "Usman Tariq (DB Ops)",
                        Permission.VIEW,
                        Permission.ACKNOWLEDGE)
                    .member("noc-l1", "noc-l1")
                    .member("db-ops", "dbops-engineer"));
    tenant = engine.tenant;
    handlers = app.context().getBean(TestHandlers.class);
    client = new ApiClient(app);
    tx = new TransactionTemplate(app.context().getBean(PlatformTransactionManager.class));
    incidents = app.context().getBean(IncidentService.class);
  }

  @Override
  public void close() {
    app.close();
    wiremock.stop();
  }

  /** The request with a new logged-in session of the user, as {@code PermissionIT.as} does. */
  public Request as(String username, Request request) {
    Response login = client.login(HOST, username, engine.correlation.identity.password(username));
    assertThat(login.status()).isEqualTo(200);
    String csrf = login.cookieValue("vigil_csrf");
    return request
        .host(HOST)
        .cookie("vigil_session", login.cookieValue("vigil_session"))
        .cookie("vigil_csrf", csrf)
        .header("X-Vigil-CSRF", csrf);
  }

  /**
   * The storm: {@code sms-queue} and {@code api-balance} first, then the other recordings in
   * recorded order; every alert decided, the clock 41 s on, every incident not merged open.
   */
  public Storm storm() {
    CorrelationFixture correlation = engine.correlation;
    List<Recording> storm = CorrelationFixture.storm();
    List<Recording> ordered = new ArrayList<>();
    ordered.add(IntakeFixture.recording(SMS_QUEUE));
    ordered.add(IntakeFixture.recording(API_BALANCE));
    storm.stream()
        .filter(r -> !r.name().equals(SMS_QUEUE) && !r.name().equals(API_BALANCE))
        .forEach(ordered::add);
    assertThat(ordered).hasSize(127);
    for (Recording recording : ordered) {
      correlation.post(recording);
    }
    correlation.awaitDecided(DECIDED_WAIT);
    clock.advance(Duration.ofSeconds(41));
    Set<IncidentState> notMerged = EnumSet.complementOf(EnumSet.of(IncidentState.MERGED));
    for (IncidentView incident : incidents.incidents(tenant, notMerged)) {
      correlation.awaitState(incident.id(), IncidentState.OPEN);
    }
    engine.awaitPublications();
    List<IncidentView> open = incidents.incidents(tenant, notMerged);
    UUID stormIncident =
        open.stream()
            .filter(i -> "srv-db-01".equals(i.hypothesisComponentKey()))
            .findFirst()
            .orElseThrow()
            .id();
    UUID report =
        open.stream()
            .map(IncidentView::id)
            .filter(id -> !id.equals(stormIncident))
            .findFirst()
            .orElseThrow();
    List<UUID> merged =
        incidents.incidents(tenant, EnumSet.of(IncidentState.MERGED)).stream()
            .map(IncidentView::id)
            .toList();
    assertThat(merged).as("merged incidents of the storm").isNotEmpty();
    return new Storm(stormIncident, report, merged);
  }

  /** Posts the recorded resolve of the storm alert. */
  public void resolveAtSource(AlertView alert) {
    Recording resolve =
        IntakeFixture.recordings().stream()
            .filter(recording -> isResolveOf(recording, alert))
            .findFirst()
            .orElseThrow();
    engine.correlation.post(resolve);
  }

  private static boolean isResolveOf(Recording recording, AlertView alert) {
    JsonNode body = recording.tree();
    if (alert.sourceKind() == MonitoringKind.ZABBIX) {
      return recording.sourceKey().equals(IntakeFixture.ZABBIX)
          && body.get("event_value").stringValue().equals("0")
          && body.get("event_id").stringValue().equals(alert.alertKey());
    }
    if (!recording.sourceKey().equals(IntakeFixture.ALERTMANAGER)) {
      return false;
    }
    JsonNode entry = body.get("alerts").get(0);
    return entry.get("status").stringValue().equals("resolved")
        && entry.get("fingerprint").stringValue().equals(alert.alertKey());
  }

  /** The user's team recorded as notified by vigil-notifications, then the acknowledgement. */
  public void acknowledge(UUID incident, String username) {
    UserView user =
        app.context()
            .getBean(IdentityService.class)
            .userByUsername(tenant, new Key(username))
            .orElseThrow();
    incidents.recordNotifiedTeam(
        tenant,
        NOTIFICATIONS,
        incident,
        user.teamKeys().iterator().next(),
        NotifiedPurpose.INCIDENT_NOTIFICATION);
    incidents.acknowledge(tenant, incident, user.id());
  }

  /** Escalation level 1 to {@code noc-shift-lead}, recorded by vigil-routing. */
  public void escalate(UUID incident) {
    incidents.recordEscalation(
        tenant,
        ROUTING,
        incident,
        1,
        Set.of(new Key("noc-shift-lead")),
        EscalationCause.ESCALATE_NOW,
        null,
        1);
  }

  /** An {@code open} ticket link {@code NOC-1} as {@code postgres}. */
  public void ticketLink(UUID incident) {
    Timestamp now = Timestamp.from(Instant.now());
    postgres
        .sql(
            "INSERT INTO ticketing.ticket_link (id, tenant_id, created_at, updated_at,"
                + " incident_id, connector_instance_id, external_key, url, state) VALUES (:id,"
                + " :t, :now, :now, :incident, :instance, 'NOC-1', :url, 'open')")
        .param("id", Ids.newId())
        .param("t", tenant.value())
        .param("now", now)
        .param("incident", incident)
        .param("instance", Ids.newId())
        .param("url", "http://127.0.0.1:" + wiremock.port() + "/browse/NOC-1")
        .update();
  }

  /** A {@code diagnostic_result} entry with content as {@code DiagnosticStep} writes it. */
  public UUID diagnostic(UUID incident) {
    String content =
        "{\"diagnostic_key\":\"disk_usage\",\"diagnostic_name\":\"Disk usage of /data\","
            + "\"component_key\":\"srv-db-01\",\"target_key\":\"lab-db-01\","
            + "\"operation_key\":\"disk-usage\",\"operation_run_id\":\""
            + Ids.newId()
            + "\",\"values\":[{\"name\":\"use_pct\",\"value\":\"100\"},"
            + "{\"name\":\"free_gib\",\"value\":\"0.1\"}]}";
    return incidents.addEvidence(
        tenant,
        DIAGNOSTICS,
        incident,
        EvidenceKind.DIAGNOSTIC_RESULT,
        Ids.newId(),
        "lab-db-01 via runner, operation disk-usage",
        clock.instant(),
        "/data is 100% full (0.1 GiB free)",
        content,
        DataCategory.DIAGNOSTIC_OUTPUT);
  }

  /** An evidence entry of another kind as its producer. */
  public UUID evidence(UUID incident, EvidenceKind kind, String producer) {
    return incidents.addEvidence(
        tenant,
        Actor.module(producer),
        incident,
        kind,
        Ids.newId(),
        producer,
        clock.instant(),
        "Entry of " + producer,
        "{}",
        DataCategory.INCIDENT_RECORD);
  }

  /** Resolved as vigil-recovery, then {@code closed} as {@code postgres}. */
  public void close(UUID incident) {
    engine.correlation.resolve(incident);
    postgres
        .sql(
            "UPDATE incidents.incident SET state = 'closed', closed_at = now()"
                + " WHERE tenant_id = :t AND id = :i")
        .param("t", tenant.value())
        .param("i", incident)
        .update();
  }

  /** A forming incident on a new unknown identity. */
  public UUID formingIncident() {
    return engine.formingIncident(identity(), "i-1", "NodeFilesystemFull");
  }

  /** An open incident on a new unknown identity (the clock 41 s on). */
  public UUID openIncident() {
    return engine.openIncident(clock, identity(), "i-1", "NodeFilesystemFull");
  }

  private static String identity() {
    return "nowhere-api-" + IDENTITIES.incrementAndGet();
  }

  /** A live run of episode 1. */
  public UUID liveRun(UUID incident, String contentJson) {
    return engine
        .engine
        .startLiveRun(tenant, EngineFixture.WORKFLOWS, incident, 1, Ids.newId(), contentJson, false)
        .orElseThrow();
  }

  /** A live run of episode 2 that cancels the unfinished live runs first. */
  public UUID reopenRun(UUID incident, String contentJson) {
    return engine
        .engine
        .startLiveRun(tenant, EngineFixture.WORKFLOWS, incident, 2, Ids.newId(), contentJson, true)
        .orElseThrow();
  }

  /** A {@code test} run row of the incident, inserted as {@code postgres}. */
  public UUID testRun(UUID incident) {
    UUID id = Ids.newId();
    Timestamp now = Timestamp.from(clock.instant());
    postgres
        .sql(
            "INSERT INTO engine.workflow_run (id, tenant_id, created_at, updated_at,"
                + " incident_id, mode, episode, content, test_replay_id, state, wake_pending,"
                + " started_at) VALUES (:id, :t, :now, :now, :incident, 'test', NULL,"
                + " CAST('{}' AS jsonb), :replay, 'running', false, :now)")
        .param("id", id)
        .param("t", tenant.value())
        .param("now", now)
        .param("incident", incident)
        .param("replay", Ids.newId())
        .update();
    return id;
  }

  /**
   * {@code sms-queue}, then {@code api-balance}, then {@code db-subscriber} without moving the
   * clock: the third alert merges the first two incidents (DM-R-10 (c)); the absorbed incident.
   */
  public UUID mergedIncident() {
    CorrelationFixture correlation = engine.correlation;
    IntakeService intake = app.context().getBean(IntakeService.class);
    AlertView first = correlation.postZabbix("sms-queue", "lab-vas-01", "SMS queue growing");
    correlation.awaitDecided();
    AlertView second =
        correlation.postAlertmanager("api-balance", "api-balance-1", "BalanceApiErrors");
    correlation.awaitDecided();
    correlation.postZabbix("db-subscriber", "lab-db-01", "Database write errors");
    correlation.awaitDecided();
    engine.awaitPublications();
    List<UUID> absorbed = new ArrayList<>();
    for (AlertView alert : List.of(first, second)) {
      UUID original =
          postgres
              .sql(
                  "SELECT incident_id FROM incidents.incident_alert_membership"
                      + " WHERE tenant_id = :t AND alert_id = :a ORDER BY joined_at, id LIMIT 1")
              .param("t", tenant.value())
              .param("a", alert.id())
              .query(UUID.class)
              .single();
      if (incidents.incident(tenant, original).orElseThrow().state() == IncidentState.MERGED) {
        absorbed.add(original);
      }
    }
    assertThat(intake.alerts(tenant, List.of(first.id(), second.id()))).hasSize(2);
    assertThat(absorbed).as("an incident absorbed by the merge").hasSize(1);
    return absorbed.getFirst();
  }

  /** Each team recorded as notified by vigil-notifications at the current clock time. */
  public void notified(UUID incident, List<String> teamKeys) {
    for (String teamKey : teamKeys) {
      incidents.recordNotifiedTeam(
          tenant, NOTIFICATIONS, incident, new Key(teamKey), NotifiedPurpose.INCIDENT_NOTIFICATION);
    }
  }
}
