package vigil.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.AopTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vigil.actions.api.ApprovalDecided;
import vigil.engine.EngineFixture;
import vigil.engine.TestContents;
import vigil.engine.TestHandlers;
import vigil.engine.api.RunFinished;
import vigil.engine.api.RunMode;
import vigil.engine.api.RunStarted;
import vigil.engine.api.StepChanged;
import vigil.engine.api.StepExecutionState;
import vigil.identity.api.IdentityService;
import vigil.identity.api.Permission;
import vigil.incidents.api.IncidentChanged;
import vigil.incidents.api.IncidentService;
import vigil.intake.api.AlertReceived;
import vigil.intake.api.AlertStateChanged;
import vigil.intake.api.AlertStatus;
import vigil.intake.api.Health;
import vigil.intake.api.IntakeService;
import vigil.intake.api.SourceHealthChanged;
import vigil.shared.Key;
import vigil.shared.StepCall.StepType;
import vigil.shared.TenantId;
import vigil.shared.Waiting;
import vigil.tenancy.api.TenantSettingsChanged;
import vigil.testsupport.ApiClient;
import vigil.testsupport.DoubleDelivery;
import vigil.testsupport.TableClasses;
import vigil.testsupport.TestTenants;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LiveProducersIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final long SECOND = TimeUnit.SECONDS.toNanos(1);
  private static final String HOST_B = "b.api.test";

  private ApiFixture fixture;
  private ApplicationEventPublisher publisher;
  private TestTenants.Tenant tenantB;
  private UUID incident;
  private UUID alert;
  private UUID source;
  private UUID run;
  private UUID stepExecution;
  private final UUID approvalRequest = UUID.randomUUID();

  /** One row of P-25 spec Section 6.3: an event, its notices and the topics to open. */
  private record Row(String name, Object event, List<String> notices) {}

  /** A notice read from a stream: its topic, id and arrival. */
  private record Notice(String topic, String id, long arrivedNanos) {}

  @BeforeAll
  void start() {
    fixture = new ApiFixture();
    publisher = fixture.app.context();
    tenantB =
        new TestTenants(fixture.app)
            .tenant("Tenant B")
            .host(HOST_B)
            .user("viewer", "Viewer", Permission.VIEW)
            .create();
    incident = fixture.openIncident();
    alert =
        fixture
            .app
            .context()
            .getBean(IntakeService.class)
            .latestAlerts(fixture.tenant, 1)
            .getFirst()
            .id();
    source =
        fixture.app.context().getBean(IntakeService.class).sources(fixture.tenant).getFirst().id();
    fixture.handlers.answer(
        StepType.DIAGNOSTIC,
        c -> new Waiting(Set.of(), fixture.clock.instant().plusSeconds(600), "{}"));
    run = fixture.liveRun(incident, TestContents.linear("diagnostic"));
    EngineFixture.await(
        () ->
            fixture.engine.executions(run).stream()
                .anyMatch(e -> e.state() == StepExecutionState.WAITING));
    stepExecution = fixture.engine.executions(run).getLast().id();
    fixture.engine.awaitPublications();
  }

  @AfterAll
  void stop() {
    fixture.close();
  }

  private List<Row> rows() {
    TenantId t = fixture.tenant;
    return List.of(
        new Row("AlertReceived", new AlertReceived(t, alert, 1), List.of("alerts " + alert)),
        new Row(
            "AlertStateChanged",
            new AlertStateChanged(t, alert, AlertStatus.FIRING),
            List.of("alerts " + alert)),
        new Row(
            "SourceHealthChanged",
            new SourceHealthChanged(t, source, Health.HEALTHY),
            List.of("sources " + source)),
        new Row(
            "IncidentChanged",
            new IncidentChanged(t, incident),
            List.of("incidents " + incident, "incident:" + incident + " " + incident)),
        new Row("RunStarted", new RunStarted(t, run, incident, RunMode.LIVE, null), runNotices()),
        new Row(
            "StepChanged",
            new StepChanged(
                t, run, incident, RunMode.LIVE, null, stepExecution, StepExecutionState.WAITING),
            runNotices()),
        new Row("RunFinished", new RunFinished(t, run, incident, RunMode.LIVE, null), runNotices()),
        new Row(
            "ApprovalDecided",
            new ApprovalDecided(t, approvalRequest, UUID.randomUUID(), UUID.randomUUID()),
            List.of("approval:" + approvalRequest + " " + approvalRequest)),
        new Row(
            "TenantSettingsChanged", new TenantSettingsChanged(t), List.of("tenant " + t.value())));
  }

  private List<String> runNotices() {
    return List.of(
        "run:" + run + " " + run, "incident:" + incident + " " + run, "incidents " + run);
  }

  /** Every topic of the rows. */
  private String topics() {
    return String.join(
        ",",
        "incidents",
        "incident:" + incident,
        "run:" + run,
        "alerts",
        "sources",
        "approval:" + approvalRequest,
        "tenant");
  }

  private ApiClient.Stream open(String host, String username, String password, String topics) {
    String session = fixture.client.login(host, username, password).cookieValue("vigil_session");
    ApiClient.Stream stream =
        fixture
            .client
            .get("/api/v1/live?topics=" + topics)
            .host(host)
            .cookie("vigil_session", session)
            .open();
    assertThat(stream.status()).isEqualTo(200);
    assertThat(stream.nextLine(Duration.ofSeconds(5)).orElseThrow().text())
        .isEqualTo("retry: 1000\n");
    assertThat(stream.nextLine(Duration.ofSeconds(5)).orElseThrow().text()).isEqualTo("\n");
    return stream;
  }

  private ApiClient.Stream openA(String topics) {
    return open(
        ApiFixture.HOST,
        "presenter",
        fixture.engine.correlation.identity.password("presenter"),
        topics);
  }

  /** The next notice within {@code timeout}, heartbeats skipped; empty when none arrived. */
  private static Optional<Notice> next(ApiClient.Stream stream, Duration timeout) {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (true) {
      long left = deadline - System.nanoTime();
      if (left <= 0) {
        return Optional.empty();
      }
      Optional<ApiClient.Line> line = stream.nextLine(Duration.ofNanos(left));
      if (line.isEmpty()) {
        return Optional.empty();
      }
      if (line.get().text().startsWith("data: ")) {
        JsonNode data = JSON.readTree(line.get().text().substring(6));
        return Optional.of(
            new Notice(
                data.get("topic").stringValue(),
                data.get("id").stringValue(),
                line.get().arrivedNanos()));
      }
    }
  }

  /** Reads notices until each expected one has arrived, within 1 s of {@code since} each. */
  private static void expect(ApiClient.Stream stream, List<String> expected, long since) {
    List<String> arrived = new ArrayList<>();
    while (!arrived.containsAll(expected)) {
      Optional<Notice> notice = next(stream, Duration.ofSeconds(2));
      assertThat(notice).as("notices %s arrived, of %s", arrived, expected).isPresent();
      String text = notice.get().topic() + " " + notice.get().id();
      if (expected.contains(text) && !arrived.contains(text)) {
        assertThat(notice.get().arrivedNanos() - since).as("delay of %s", text).isLessThan(SECOND);
        arrived.add(text);
      }
    }
  }

  private static void expectNone(ApiClient.Stream stream, Duration wait) {
    assertThat(next(stream, wait)).isEmpty();
  }

  private void publishInTransaction(Object event) {
    fixture.tx.executeWithoutResult(status -> publisher.publishEvent(event));
  }

  @Test
  @Order(1)
  @DisplayName("AR-I-04 each event's notices reach the tenant's streams within 1 s of the commit")
  void producers() {
    try (ApiClient.Stream a = openA(topics());
        ApiClient.Stream b = open(HOST_B, "viewer", tenantB.password("viewer"), topics())) {
      for (Row row : rows()) {
        publishInTransaction(row.event());
        long committed = System.nanoTime();
        expect(a, row.notices(), committed);
      }
      UUID testRun = UUID.randomUUID();
      UUID replay = UUID.randomUUID();
      for (Object event :
          List.of(
              new RunStarted(fixture.tenant, testRun, incident, RunMode.TEST, replay),
              new StepChanged(
                  fixture.tenant,
                  testRun,
                  incident,
                  RunMode.TEST,
                  replay,
                  UUID.randomUUID(),
                  StepExecutionState.RUNNING),
              new RunFinished(fixture.tenant, testRun, incident, RunMode.TEST, replay))) {
        publishInTransaction(event);
      }
      fixture.engine.awaitPublications();
      expectNone(a, Duration.ofSeconds(1));
      expectNone(b, Duration.ofSeconds(1));
    }
  }

  @Test
  @Order(2)
  @DisplayName("AR-R-19 the producers send nothing for a tenant that is not active")
  void dormantTenant() {
    TenantId dormant = new TestTenants(fixture.app).tenant("Dormant").create().id();
    LiveHub hub = fixture.app.context().getBean(LiveHub.class);
    Set<LiveTopic> topics = Set.of(LiveTopic.parse("incidents"), LiveTopic.parse("tenant"));
    LiveHub.Stream stream = hub.open(dormant, topics);
    try {
      UUID id = UUID.randomUUID();
      publishInTransaction(new IncidentChanged(dormant, id));
      publishInTransaction(new TenantSettingsChanged(dormant));
      publishInTransaction(new RunStarted(dormant, id, id, RunMode.LIVE, null));
      fixture.engine.awaitPublications();
      assertThat(stream.next(TimeUnit.SECONDS.toNanos(1))).isNull();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    } finally {
      hub.close(stream);
    }
  }

  @Test
  @Order(3)
  @DisplayName("PM-M23-04 an acknowledgement and a step change reach open screens within 1 s")
  void realChanges() {
    UUID open = fixture.openIncident();
    fixture.notified(open, List.of("db-ops"));
    fixture.engine.awaitPublications();
    UUID user =
        fixture
            .app
            .context()
            .getBean(IdentityService.class)
            .userByUsername(fixture.tenant, new Key("dbops-engineer"))
            .orElseThrow()
            .id();
    try (ApiClient.Stream stream = openA("incidents,incident:" + open)) {
      fixture.app.context().getBean(IncidentService.class).acknowledge(fixture.tenant, open, user);
      long committed = System.nanoTime();
      expect(stream, List.of("incident:" + open + " " + open, "incidents " + open), committed);
    }

    UUID other = fixture.openIncident();
    TestHandlers handlers = fixture.handlers;
    handlers.reset();
    handlers.block(StepType.DIAGNOSTIC);
    UUID blocked = fixture.liveRun(other, TestContents.linear("diagnostic"));
    handlers.awaitBlocked(StepType.DIAGNOSTIC, blocked);
    fixture.engine.awaitPublications();
    try (ApiClient.Stream stream = openA("run:" + blocked)) {
      handlers.release(StepType.DIAGNOSTIC);
      long released = System.nanoTime();
      expect(stream, List.of("run:" + blocked + " " + blocked), released);
    }
  }

  @Test
  @Order(4)
  @DisplayName(
      "AR-Q-05 each producer delivered twice changes no record and sends its notices again")
  void doubleDelivery() {
    LiveProducers producers =
        AopTestUtils.getUltimateTargetObject(fixture.app.context().getBean(LiveProducers.class));
    List<Row> rows = rows();
    Map<Class<?>, Consumer<Object>> listeners =
        Map.of(
            AlertReceived.class, e -> producers.on((AlertReceived) e),
            AlertStateChanged.class, e -> producers.on((AlertStateChanged) e),
            SourceHealthChanged.class, e -> producers.on((SourceHealthChanged) e),
            IncidentChanged.class, e -> producers.on((IncidentChanged) e),
            RunStarted.class, e -> producers.on((RunStarted) e),
            StepChanged.class, e -> producers.on((StepChanged) e),
            RunFinished.class, e -> producers.on((RunFinished) e),
            ApprovalDecided.class, e -> producers.on((ApprovalDecided) e),
            TenantSettingsChanged.class, e -> producers.on((TenantSettingsChanged) e));
    assertThat(listeners).hasSize(rows.size());
    try (ApiClient.Stream stream = openA(topics())) {
      fixture.engine.awaitPublications();
      for (Row row : rows) {
        Consumer<Object> listener = listeners.get(row.event().getClass());
        for (String schema : TableClasses.SCHEMAS) {
          DoubleDelivery.assertSameResult(
              fixture.app.database().postgres(),
              schema,
              fixture.tx,
              () -> listener.accept(row.event()));
        }
        List<String> arrived = new ArrayList<>();
        int expected = row.notices().size() * 2 * TableClasses.SCHEMAS.size();
        while (arrived.size() < expected) {
          Notice notice = next(stream, Duration.ofSeconds(2)).orElseThrow();
          arrived.add(notice.topic() + " " + notice.id());
        }
        for (String notice : row.notices()) {
          assertThat(arrived.stream().filter(notice::equals).count())
              .as("%s: %s", row.name(), notice)
              .isEqualTo(2L * TableClasses.SCHEMAS.size());
        }
      }
    }
  }
}
