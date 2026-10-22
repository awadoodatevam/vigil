package vigil.incidents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import vigil.incidents.api.AffectedServiceCopy;
import vigil.incidents.api.AiStatus;
import vigil.incidents.api.AlertCopy;
import vigil.incidents.api.DataCategory;
import vigil.incidents.api.EscalationCause;
import vigil.incidents.api.EvidenceKind;
import vigil.incidents.api.IncidentChanged;
import vigil.incidents.api.IncidentCreated;
import vigil.incidents.api.IncidentService;
import vigil.incidents.api.IncidentState;
import vigil.incidents.api.IncidentStateChanged;
import vigil.incidents.api.IncidentView;
import vigil.incidents.api.MembershipView;
import vigil.incidents.api.NotifiedPurpose;
import vigil.incidents.api.Severity;
import vigil.incidents.api.TimelineEntryType;
import vigil.incidents.api.TimelineView;
import vigil.incidents.api.TransitionView;
import vigil.shared.Actor;
import vigil.shared.Ids;
import vigil.shared.Key;
import vigil.shared.TenantId;
import vigil.tenancy.api.TenantAliasChanged;
import vigil.tenancy.api.TenantService;
import vigil.testsupport.DoubleDelivery;
import vigil.testsupport.MutableClock;
import vigil.testsupport.TestTenants;
import vigil.testsupport.VigilDatabase;
import vigil.testsupport.VigilTestApp;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IncidentLifecycleIT {

  private static final MutableClock CLOCK = new MutableClock(Instant.parse("2026-10-22T10:00:00Z"));
  private static final List<Duration> DELAYS = new CopyOnWriteArrayList<>();
  private static final String HOST_A = "a.incidents.test";
  private static final String HOST_B = "b.incidents.test";
  private static final String HOST_C = "c.incidents.test";
  private static final String HOST_D = "d.incidents.test";
  private static final String HOST_G = "g.incidents.test";
  private static final Actor ENGINE = Actor.module("vigil-engine");
  private static final Set<IncidentState> ALL = EnumSet.allOf(IncidentState.class);

  private VigilDatabase database;
  private VigilTestApp app;
  private JdbcClient postgres;
  private IncidentService incidents;
  private IncidentFixture a;
  private IncidentFixture b;
  private Instant t0;

  private AlertCopy firstAlert;
  private UUID first;
  private IncidentView afterCreate;
  private IncidentView afterAdds;
  private IncidentView afterHypothesis;
  private IncidentView afterRepeat;
  private List<Object> createEvents;
  private int timelineBeforeRepeat;
  private int timelineAfterRepeat;
  private List<Object> repeatEvents;
  private UUID secondComputation;

  private UUID x;
  private UUID y;
  private UUID forming;
  private UUID v1;
  private UUID v2;
  private IncidentView afterFirstResolve;
  private IncidentView afterReopen;
  private final List<String> refusals = new ArrayList<>();

  private Instant s;
  private UUID survivor;
  private UUID two;
  private UUID one;
  private AlertCopy twoSecond;
  private List<Object> mergeEvents;

  /** The test clock, the event recorder and the recorder of fixed-delay tasks. */
  static class TestBeans {

    @Bean
    @Primary
    Clock testClock() {
      return CLOCK;
    }

    @Bean
    IncidentFixture.Events incidentEvents() {
      return new IncidentFixture.Events();
    }

    @Bean
    static BeanPostProcessor delayRecordingSchedulerPostProcessor() {
      return new BeanPostProcessor() {
        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
          if (!(bean instanceof ThreadPoolTaskScheduler scheduler)) {
            return bean;
          }
          ProxyFactory proxy = new ProxyFactory(scheduler);
          proxy.setProxyTargetClass(true);
          proxy.addAdvice(
              (MethodInterceptor)
                  invocation -> {
                    Object[] args = invocation.getArguments();
                    if (invocation.getMethod().getName().equals("scheduleWithFixedDelay")
                        && args.length == 3
                        && args[2] instanceof Duration delay) {
                      DELAYS.add(delay);
                    }
                    return invocation.proceed();
                  });
          return proxy.getProxy();
        }
      };
    }
  }

  @BeforeAll
  void start() {
    database = VigilDatabase.fresh();
    start(database);
    postgres = database.postgres();
    a = IncidentFixture.create(app, "Tenant A", HOST_A);
    b = IncidentFixture.create(app, "Tenant B", HOST_B);
    t0 = CLOCK.instant();
  }

  private void start(VigilDatabase on) {
    app = VigilTestApp.start(on, TestBeans.class);
    app.awaitStarted();
    incidents = app.context().getBean(IncidentService.class);
  }

  @AfterAll
  void stop() {
    app.close();
  }

  private IncidentView view(UUID id) {
    return incidents.incident(a.tenant, id).orElseThrow();
  }

  private static JsonNode tree(String json) {
    return IncidentJson.read(json);
  }

  /** The request is refused with an {@code IllegalStateException} and changes nothing. */
  private void refused(Runnable request, String message) {
    Map<String, Long> rows = IncidentFixture.rows(postgres, a.tenant);
    int events = IncidentFixture.Events.RECEIVED.size();
    assertThatThrownBy(request::run).isInstanceOf(IllegalStateException.class).hasMessage(message);
    assertThat(IncidentFixture.rows(postgres, a.tenant)).isEqualTo(rows);
    assertThat(IncidentFixture.Events.RECEIVED).hasSize(events);
    refusals.add(message);
  }

  /** The request is refused with an {@code IllegalArgumentException} and changes nothing. */
  private void refusedArgument(Runnable request, String message) {
    Map<String, Long> rows = IncidentFixture.rows(postgres, a.tenant);
    int events = IncidentFixture.Events.RECEIVED.size();
    assertThatThrownBy(request::run)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(message);
    assertThat(IncidentFixture.rows(postgres, a.tenant)).isEqualTo(rows);
    assertThat(IncidentFixture.Events.RECEIVED).hasSize(events);
  }

  @Test
  @Order(1)
  @DisplayName("PM-M09-01 incidents and memberships created on request; the title from its pattern")
  void createdOnRequest() {
    firstAlert = a.alert("disk_full", "Disk full", "srv-db-01", t0.minusSeconds(60));
    first = a.create(firstAlert);
    afterCreate = view(first);
    createEvents = List.copyOf(IncidentFixture.Events.of(first));
    a.add(
        first,
        a.alert("db_write_errors", "Database write errors", "db-subscriber", t0.minusSeconds(50)));
    a.add(
        first,
        a.alert("db_write_errors", "Database write errors", "db-subscriber", t0.minusSeconds(40)));
    a.add(
        first,
        a.alert(
            "balance_api_errors", "Balance-check API errors", "api-balance", t0.minusSeconds(30)));
    afterAdds = view(first);
    incidents.setHypothesisAndImpact(
        a.tenant,
        IncidentFixture.CORRELATION,
        first,
        IncidentFixture.SRV_DB_01,
        Ids.newId(),
        IncidentFixture.STORM_SERVICES);
    afterHypothesis = view(first);
    timelineBeforeRepeat = incidents.timeline(a.tenant, first).size();
    int eventsBefore = IncidentFixture.Events.of(first).size();
    secondComputation = Ids.newId();
    incidents.setHypothesisAndImpact(
        a.tenant,
        IncidentFixture.CORRELATION,
        first,
        IncidentFixture.SRV_DB_01,
        secondComputation,
        IncidentFixture.STORM_SERVICES);
    afterRepeat = view(first);
    timelineAfterRepeat = incidents.timeline(a.tenant, first).size();
    List<Object> all = IncidentFixture.Events.of(first);
    repeatEvents = List.copyOf(all.subList(eventsBefore, all.size()));

    assertThat(afterCreate.state()).isEqualTo(IncidentState.FORMING);
    assertThat(afterCreate.title()).isEqualTo("srv-db-01: Disk full");
    assertThat(afterAdds.alertCount()).isEqualTo(4);
    assertThat(afterAdds.title()).isEqualTo("srv-db-01: Disk full");
    assertThat(afterHypothesis.title())
        .isEqualTo("Subscriber DB server: Disk full — 2 services affected");
    assertThat(incidents.members(a.tenant, first)).hasSize(4);
    assertThat(
            incidents.timeline(a.tenant, first).stream()
                .map(TimelineView::text)
                .toList()
                .subList(4, 7))
        .containsExactly(
            "Hypothesis: Subscriber DB server.",
            "Customer impact: Balance check, Bundle purchase.",
            "Title: Subscriber DB server: Disk full — 2 services affected");
  }

  @Test
  @Order(2)
  @DisplayName("DM-E-32 the incident: its fields when created, joined and given a hypothesis")
  void incidentFields() {
    assertThat(afterCreate.sequence()).isEqualTo(1);
    assertThat(afterCreate.number()).isNull();
    assertThat(afterCreate.stateChangedAt()).isEqualTo(t0);
    assertThat(afterCreate.alertCount()).isEqualTo(1);
    assertThat(afterCreate.alertTypes()).containsExactly("disk_full");
    assertThat(afterCreate.alertSources()).containsExactly("zabbix");
    assertThat(afterCreate.firstAlertFiredAt()).isEqualTo(t0.minusSeconds(60));
    assertThat(afterCreate.lastAlertJoinedAt()).isEqualTo(t0);
    assertThat(afterCreate.affectedServices()).isEmpty();
    assertThat(afterCreate.informedTeamKeys()).isEmpty();
    assertThat(afterCreate.hypothesis()).isNull();
    assertThat(afterCreate.severity()).isNull();
    assertThat(afterCreate.assignmentFrozen()).isFalse();
    assertThat(afterCreate.escalationLevel()).isZero();
    assertThat(afterCreate.aiStatus()).isEqualTo(AiStatus.NOT_RUN);
    assertThat(afterCreate.reopenCount()).isZero();
    assertThat(afterCreate.createdAt()).isEqualTo(t0);
    assertThat(afterAdds.alertCount()).isEqualTo(4);
    assertThat(afterAdds.firstAlertFiredAt()).isEqualTo(t0.minusSeconds(60));
    assertThat(afterAdds.alertTypes())
        .containsExactly("balance_api_errors", "db_write_errors", "disk_full");
    assertThat(afterAdds.alertSources()).containsExactly("zabbix");
    assertThat(afterHypothesis.hypothesisComponentKey()).isEqualTo("srv-db-01");
    assertThat(afterHypothesis.hypothesis()).isEqualTo(IncidentFixture.SRV_DB_01);
    assertThat(afterHypothesis.affectedServices().stream().map(AffectedServiceCopy::serviceKey))
        .containsExactly("svc-balance-check", "svc-bundle-purchase");
    assertThat(afterHypothesis.affectedServices().getFirst().viaComponentKeys())
        .containsExactly("api-balance", "db-subscriber");
    Map<String, Object> row =
        postgres
            .sql(
                "SELECT hypothesis_snapshot::text AS s, affected_services::text AS a"
                    + " FROM incidents.incident WHERE id = :id")
            .param("id", first)
            .query()
            .singleRow();
    assertThat(tree((String) row.get("s")))
        .isEqualTo(
            tree(
                "{\"generation\": 1, \"name\": \"Subscriber DB server\", \"kind\": \"server\","
                    + " \"criticality\": \"critical\"}"));
    assertThat(tree((String) row.get("a")))
        .isEqualTo(
            tree(
                "[{\"service_key\": \"svc-balance-check\", \"name\": \"Balance check\","
                    + " \"criticality\": null, \"via_component_keys\": [\"api-balance\","
                    + " \"db-subscriber\"]}, {\"service_key\": \"svc-bundle-purchase\", \"name\":"
                    + " \"Bundle purchase\", \"criticality\": null, \"via_component_keys\":"
                    + " [\"db-subscriber\"]}]"));
  }

  @Test
  @Order(3)
  @DisplayName("DM-E-34 the membership: the alert copy and the grouping reason")
  void membership() {
    List<MembershipView> members = incidents.members(a.tenant, first);
    MembershipView created = members.getFirst();
    assertThat(created.alertId()).isEqualTo(firstAlert.alertId());
    assertThat(created.joinedAt()).isEqualTo(t0);
    assertThat(created.endedAt()).isNull();
    assertThat(created.finalReasonJson()).isNull();
    String snapshot =
        postgres
            .sql(
                "SELECT alert_snapshot::text FROM incidents.incident_alert_membership"
                    + " WHERE id = :id")
            .param("id", created.id())
            .query(String.class)
            .single();
    JsonNode node = tree(snapshot);
    assertThat(List.copyOf(node.propertyNames()))
        .containsExactlyInAnyOrder(
            "source_kind",
            "alert_key",
            "alert_type",
            "alert_type_name",
            "title",
            "component_key",
            "first_identity_pair",
            "fired_at");
    assertThat(node.get("fired_at").stringValue()).isEqualTo("2026-10-22T09:59:00.000Z");
    assertThat(node.get("first_identity_pair"))
        .isEqualTo(tree("{\"name\": \"component\", \"value\": \"srv-db-01\"}"));
    assertThat(tree(created.reasonJson()))
        .isEqualTo(
            tree(
                "{\"decision\": \"created_incident\", \"related_alert_id\": null, \"relation\":"
                    + " null, \"relation_path\": null, \"time_delta\": null}"));
    JsonNode joined = tree(members.get(1).reasonJson());
    assertThat(joined.get("decision").stringValue()).isEqualTo("joined");
    assertThat(joined.get("relation").stringValue()).isEqualTo("same_component");
    assertThat(joined.get("relation_path")).isEqualTo(tree("[\"c\"]"));
    assertThat(joined.get("time_delta").longValue()).isEqualTo(10);
  }

  @Test
  @Order(4)
  @DisplayName("BP-A-27 the alert copy; a hypothesis-only change publishes without an entry")
  void alertCopyAndHypothesisOnly() {
    assertThat(incidents.members(a.tenant, first).getFirst().alert()).isEqualTo(firstAlert);
    assertThat(timelineAfterRepeat).isEqualTo(timelineBeforeRepeat);
    assertThat(repeatEvents).containsExactly(new IncidentChanged(a.tenant, first));
    assertThat(afterRepeat.hypothesisComputationId()).isEqualTo(secondComputation);
    assertThat(afterRepeat.title()).isEqualTo(afterHypothesis.title());
  }

  @Test
  @Order(5)
  @DisplayName("AR-E-05 IncidentCreated and IncidentChanged at creation, no IncidentStateChanged")
  void createdEvents() {
    assertThat(createEvents)
        .containsExactly(
            new IncidentCreated(a.tenant, first), new IncidentChanged(a.tenant, first));
  }

  @Test
  @Order(6)
  @DisplayName("DM-E-33 the creation's state transition DM-SM05-T1")
  void creationTransition() {
    TransitionView created = incidents.transitions(a.tenant, first).getFirst();
    assertThat(created.transitionId()).isEqualTo("DM-SM05-T1");
    assertThat(created.fromState()).isNull();
    assertThat(created.toState()).isEqualTo(IncidentState.FORMING);
    assertThat(created.at()).isEqualTo(t0);
    assertThat(created.actor()).isEqualTo(IncidentFixture.CORRELATION);
    assertThat(created.reason())
        .isEqualTo("Incident created from alert 'Disk full on srv-db-01' (Zabbix).");
  }

  @Test
  @Order(7)
  @DisplayName("DM-V-01 the actors of timeline entries and audit events are the requests' actors")
  void actors() {
    assertThat(incidents.timeline(a.tenant, first))
        .allSatisfy(entry -> assertThat(entry.actor()).isEqualTo(IncidentFixture.CORRELATION));
    List<Map<String, Object>> audit =
        postgres
            .sql(
                "SELECT actor_kind, actor_user_id, actor_module FROM audit.audit_event"
                    + " WHERE tenant_id = :t AND incident_id = :id")
            .param("t", a.tenant.value())
            .param("id", first)
            .query()
            .listOfRows();
    assertThat(audit)
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.get("actor_kind")).isEqualTo("module");
              assertThat(row.get("actor_user_id")).isNull();
              assertThat(row.get("actor_module")).isEqualTo("vigil-correlation");
            });
  }

  @Test
  @Order(8)
  @DisplayName("SL-B-12 explicit lifecycle states: open, resolved, reopened and resolved again")
  void lifecycle() {
    a.open(first);
    IncidentView opened = view(first);
    assertThat(opened.state()).isEqualTo(IncidentState.OPEN);
    assertThat(opened.number()).isEqualTo(1);
    assertThat(opened.stableAt()).isEqualTo(t0);
    assertThat(opened.assignmentFrozen()).isTrue();
    assertThat(incidents.members(a.tenant, first))
        .allSatisfy(
            m ->
                assertThat(tree(m.finalReasonJson()))
                    .isEqualTo(tree("{\"relation\": \"only_alert\"}")));
    x = a.create(a.alert("cpu_high", "CPU high", "srv-app-01", t0));
    y = a.create(a.alert("cpu_high", "CPU high", "srv-app-02", t0));
    assertThat(view(x).state()).isEqualTo(IncidentState.FORMING);
    assertThat(view(y).state()).isEqualTo(IncidentState.FORMING);
    a.open(y);
    a.open(x);
    v1 = a.verification(first);
    incidents.resolve(a.tenant, IncidentFixture.RECOVERY, first, v1);
    afterFirstResolve = view(first);
    assertThat(afterFirstResolve.state()).isEqualTo(IncidentState.RESOLVED);
    assertThat(afterFirstResolve.resolvedAt()).isEqualTo(t0);
    AlertCopy late =
        a.alert("db_write_errors", "Database write errors", "db-subscriber", t0.minusSeconds(10));
    incidents.reopen(
        a.tenant,
        IncidentFixture.CORRELATION,
        first,
        Ids.newId(),
        late,
        IncidentFixture.reopened(firstAlert.alertId()));
    afterReopen = view(first);
    assertThat(afterReopen.state()).isEqualTo(IncidentState.OPEN);
    assertThat(afterReopen.title()).isEqualTo(afterHypothesis.title());
    assertThat(afterReopen.reopenCount()).isEqualTo(1);
    assertThat(afterReopen.number()).isEqualTo(1);
    assertThat(afterReopen.resolvedAt()).isNull();
    assertThat(afterReopen.resolvedByVerificationId()).isNull();
    assertThat(afterReopen.acknowledgedAt()).isNull();
    assertThat(afterReopen.acknowledgedBy()).isNull();
    assertThat(afterReopen.escalationLevel()).isZero();
    assertThat(afterReopen.assignmentFrozen()).isTrue();
    assertThat(afterReopen.alertCount()).isEqualTo(5);
    MembershipView reopening = incidents.members(a.tenant, first).getLast();
    assertThat(reopening.alertId()).isEqualTo(late.alertId());
    assertThat(tree(reopening.finalReasonJson()))
        .isEqualTo(
            tree(
                "{\"related_alert_id\": \""
                    + firstAlert.alertId()
                    + "\", \"relation\": \"same_component\", \"relation_path\": [\"c\"],"
                    + " \"time_delta\": 20}"));
    v2 = a.resolve(first);
    assertThat(view(first).state()).isEqualTo(IncidentState.RESOLVED);

    forming = a.create(a.alert("cpu_high", "CPU high", "srv-web-01", t0));
    refused(() -> a.open(x), "open is not allowed in state open");
    refused(
        () -> incidents.resolve(a.tenant, IncidentFixture.RECOVERY, forming, Ids.newId()),
        "resolve is not allowed in state forming");
    UUID unknown = Ids.newId();
    refused(
        () -> incidents.resolve(a.tenant, IncidentFixture.RECOVERY, y, unknown),
        "verification " + unknown + " is not evidence of this incident");
    refused(
        () ->
            incidents.reopen(
                a.tenant,
                IncidentFixture.CORRELATION,
                x,
                Ids.newId(),
                a.alert("cpu_high", "CPU high", "srv-app-01", t0),
                IncidentFixture.reopened(Ids.newId())),
        "reopen is not allowed in state open");
    refused(
        () -> incidents.merge(a.tenant, IncidentFixture.CORRELATION, x, List.of(forming)),
        "merge is not allowed in state open");
    refused(
        () -> a.add(first, a.alert("disk_full", "Disk full", "srv-db-01", t0)),
        "addAlert is not allowed in state resolved");
    refused(
        () ->
            incidents.setAssignment(
                a.tenant,
                IncidentFixture.ROUTING,
                x,
                Severity.P2,
                new Key("db-ops"),
                Set.of(),
                Ids.newId(),
                "Default"),
        "setAssignment is not allowed in state open");
  }

  @Test
  @Order(9)
  @DisplayName("PM-M09-02 only the transitions of DM-SM-05, each recorded with time and actor")
  void transitionsRecorded() {
    List<TransitionView> transitions = incidents.transitions(a.tenant, first);
    assertThat(transitions.stream().map(t -> t.transitionId() + " " + t.actor().module()))
        .containsExactly(
            "DM-SM05-T1 vigil-correlation",
            "DM-SM05-T2 vigil-correlation",
            "DM-SM05-T4 vigil-recovery",
            "DM-SM05-T5 vigil-correlation",
            "DM-SM05-T4 vigil-recovery");
    assertThat(transitions).allSatisfy(t -> assertThat(t.at()).isEqualTo(t0));
    assertThat(transitions.get(2).reason()).isEqualTo("Resolved: recovery verified.");
    assertThat(transitions.get(3).reason())
        .isEqualTo("Reopened by alert 'Database write errors on db-subscriber' (Zabbix).");
  }

  @Test
  @Order(10)
  @DisplayName("DM-SM-05 every transition from forming, open and resolved, and the refused ones")
  void stateMachine() {
    assertThat(
            incidents.transitions(a.tenant, first).stream()
                .map(t -> t.fromState() + " " + t.toState()))
        .containsExactly(
            "null FORMING", "FORMING OPEN", "OPEN RESOLVED", "RESOLVED OPEN", "OPEN RESOLVED");
    assertThat(refusals)
        .containsExactly(
            "open is not allowed in state open",
            "resolve is not allowed in state forming",
            refusals.get(2),
            "reopen is not allowed in state open",
            "merge is not allowed in state open",
            "addAlert is not allowed in state resolved",
            "setAssignment is not allowed in state open");
    assertThat(view(x).state()).isEqualTo(IncidentState.OPEN);
    assertThat(view(forming).state()).isEqualTo(IncidentState.FORMING);
  }

  @Test
  @Order(11)
  @DisplayName("DM-E32-R3 numbers in the order incidents leave forming; a forming one has none")
  void numbers() {
    assertThat(view(first).number()).isEqualTo(1);
    assertThat(view(y).number()).isEqualTo(2);
    assertThat(view(x).number()).isEqualTo(3);
    assertThat(view(x).sequence()).isLessThan(view(y).sequence());
    assertThat(view(forming).number()).isNull();
  }

  @Test
  @Order(12)
  @DisplayName("AR-E-07 IncidentStateChanged with from and to, for each change after creation")
  void stateChangedEvents() {
    assertThat(
            IncidentFixture.Events.of(first).stream()
                .filter(IncidentStateChanged.class::isInstance)
                .toList())
        .containsExactly(
            new IncidentStateChanged(a.tenant, first, IncidentState.FORMING, IncidentState.OPEN),
            new IncidentStateChanged(a.tenant, first, IncidentState.OPEN, IncidentState.RESOLVED),
            new IncidentStateChanged(a.tenant, first, IncidentState.RESOLVED, IncidentState.OPEN),
            new IncidentStateChanged(a.tenant, first, IncidentState.OPEN, IncidentState.RESOLVED));
  }

  @Test
  @Order(13)
  @DisplayName("AR-E-08 IncidentChanged after each change, in order with the other events")
  void changedEvents() {
    IncidentChanged changed = new IncidentChanged(a.tenant, first);
    List<Object> expected = new ArrayList<>();
    expected.add(new IncidentCreated(a.tenant, first));
    for (int i = 0; i < 6; i++) {
      expected.add(changed);
    }
    expected.add(
        new IncidentStateChanged(a.tenant, first, IncidentState.FORMING, IncidentState.OPEN));
    expected.add(changed);
    expected.add(changed);
    expected.add(
        new IncidentStateChanged(a.tenant, first, IncidentState.OPEN, IncidentState.RESOLVED));
    expected.add(changed);
    expected.add(
        new IncidentStateChanged(a.tenant, first, IncidentState.RESOLVED, IncidentState.OPEN));
    expected.add(changed);
    expected.add(changed);
    expected.add(
        new IncidentStateChanged(a.tenant, first, IncidentState.OPEN, IncidentState.RESOLVED));
    expected.add(changed);
    assertThat(IncidentFixture.Events.of(first)).isEqualTo(expected);
  }

  @Test
  @Order(14)
  @DisplayName("PM-M09-12 forming incidents merged on request of vigil-correlation")
  void merge() {
    s = CLOCK.instant();
    survivor = a.create(a.alert("queue_growth", "Queue growth", "mq-01", s));
    CLOCK.advance(Duration.ofSeconds(1));
    two = a.create(a.alert("queue_growth", "Queue growth", "mq-02", s));
    CLOCK.advance(Duration.ofSeconds(1));
    twoSecond = a.alert("queue_growth", "Queue growth", "mq-02", s);
    a.add(two, twoSecond);
    CLOCK.advance(Duration.ofSeconds(1));
    one = a.create(a.alert("queue_growth", "Queue growth", "mq-03", s));
    int before = IncidentFixture.Events.RECEIVED.size();
    incidents.merge(a.tenant, IncidentFixture.CORRELATION, survivor, List.of(two, one));
    mergeEvents =
        List.copyOf(
            IncidentFixture.Events.RECEIVED.subList(
                before, IncidentFixture.Events.RECEIVED.size()));
    a.open(survivor);
    for (UUID absorbed : List.of(two, one)) {
      IncidentView view = view(absorbed);
      assertThat(view.state()).isEqualTo(IncidentState.MERGED);
      assertThat(view.mergedIntoIncidentId()).isEqualTo(survivor);
      assertThat(view.number()).isNull();
    }
    assertThat(view(survivor).lastAlertJoinedAt()).isEqualTo(s.plusSeconds(3));
    assertThat(view(survivor).alertCount()).isEqualTo(4);
    assertThat(view(survivor).number()).isNotNull();
    refused(
        () -> a.add(two, a.alert("queue_growth", "Queue growth", "mq-02", s)),
        "addAlert is not allowed in state merged");
    refused(() -> a.open(one), "open is not allowed in state merged");
    assertThat(
            incidents.timeline(a.tenant, survivor).stream()
                .filter(e -> e.entryType() == TimelineEntryType.MERGED_IN)
                .map(TimelineView::text))
        .containsExactly(
            "2 alerts merged in from another group.", "1 alert merged in from another group.");
  }

  @Test
  @Order(15)
  @DisplayName("DM-R-12 the incident side of a merge: memberships moved with the original reason")
  void mergeRecords() {
    List<MembershipView> members = incidents.members(a.tenant, survivor);
    assertThat(members).hasSize(4);
    List<MembershipView> moved = members.subList(1, 4);
    assertThat(moved).allSatisfy(m -> assertThat(m.joinedAt()).isEqualTo(s.plusSeconds(3)));
    JsonNode reason = tree(moved.get(1).reasonJson());
    assertThat(reason.get("decision").stringValue()).isEqualTo("moved_by_merge");
    assertThat(reason.get("from_incident_id").stringValue()).isEqualTo(two.toString());
    assertThat(reason.get("original_reason").get("decision").stringValue()).isEqualTo("joined");
    assertThat(moved.get(1).alert()).isEqualTo(twoSecond);
    assertThat(tree(moved.get(2).reasonJson()).get("original_reason").get("decision").stringValue())
        .isEqualTo("created_incident");
    for (UUID absorbed : List.of(two, one)) {
      TransitionView merged = incidents.transitions(a.tenant, absorbed).getLast();
      assertThat(merged.transitionId()).isEqualTo("DM-SM05-T3");
      assertThat(merged.toState()).isEqualTo(IncidentState.MERGED);
      assertThat(merged.reason()).isEqualTo("Merged into another group.");
    }
    assertThat(mergeEvents)
        .containsExactly(
            new IncidentStateChanged(a.tenant, two, IncidentState.FORMING, IncidentState.MERGED),
            new IncidentChanged(a.tenant, two),
            new IncidentStateChanged(a.tenant, one, IncidentState.FORMING, IncidentState.MERGED),
            new IncidentChanged(a.tenant, one),
            new IncidentChanged(a.tenant, survivor));
  }

  @Test
  @Order(16)
  @DisplayName("DM-SM-16 a membership goes from active to ended only by a merge")
  void membershipEnded() {
    assertThat(incidents.members(a.tenant, two)).isEmpty();
    assertThat(incidents.memberships(a.tenant, two))
        .hasSize(2)
        .allSatisfy(m -> assertThat(m.endedAt()).isEqualTo(s.plusSeconds(3)));
    List<UUID> withEnded =
        postgres
            .sql(
                "SELECT DISTINCT incident_id FROM incidents.incident_alert_membership"
                    + " WHERE tenant_id = :t AND ended_at IS NOT NULL")
            .param("t", a.tenant.value())
            .query(UUID.class)
            .list();
    assertThat(withEnded).containsExactlyInAnyOrder(two, one);
  }

  @Test
  @Order(17)
  @DisplayName("DM-E34-R1 at most one active membership per alert")
  void oneActiveMembership() {
    refused(
        () -> a.create(twoSecond),
        "alert " + twoSecond.alertId() + " is already a member of an incident");
    refused(
        () -> a.add(forming, twoSecond),
        "alert " + twoSecond.alertId() + " is already a member of an incident");
    assertThat(incidents.incidentOfAlert(a.tenant, twoSecond.alertId())).contains(survivor);
    assertThat(
            IncidentFixture.count(
                postgres,
                "SELECT count(*) FROM (SELECT alert_id FROM incidents.incident_alert_membership"
                    + " WHERE tenant_id = :t AND ended_at IS NULL GROUP BY alert_id"
                    + " HAVING count(*) > 1) m",
                a.tenant))
        .isZero();
  }

  @Test
  @Order(18)
  @DisplayName("PM-M09-06 resolved only on request of vigil-recovery with a verification id")
  void resolvedWithVerification() {
    assertThat(afterFirstResolve.resolvedByVerificationId()).isEqualTo(v1);
    assertThat(view(first).resolvedByVerificationId()).isEqualTo(v2);
  }

  @Test
  @Order(19)
  @DisplayName("DM-E32-R1 each request is accepted only from its module")
  void requestingModule() {
    for (Actor actor : List.of(ENGINE, Actor.user(a.userId("noc-l1")))) {
      refusedArgument(
          () ->
              incidents.create(
                  a.tenant,
                  actor,
                  Ids.newId(),
                  a.alert("cpu_high", "CPU high", "srv-x", t0),
                  IncidentFixture.created()),
          "create is accepted only from vigil-correlation");
      refusedArgument(
          () ->
              incidents.addAlert(
                  a.tenant,
                  actor,
                  forming,
                  Ids.newId(),
                  a.alert("cpu_high", "CPU high", "srv-x", t0),
                  IncidentFixture.joined(Ids.newId())),
          "addAlert is accepted only from vigil-correlation");
      refusedArgument(
          () -> incidents.merge(a.tenant, actor, forming, List.of(x)),
          "merge is accepted only from vigil-correlation");
      refusedArgument(
          () ->
              incidents.setHypothesisAndImpact(
                  a.tenant, actor, forming, IncidentFixture.SRV_DB_01, Ids.newId(), List.of()),
          "setHypothesisAndImpact is accepted only from vigil-correlation");
      refusedArgument(
          () -> incidents.open(a.tenant, actor, forming, Map.of()),
          "open is accepted only from vigil-correlation");
      refusedArgument(
          () ->
              incidents.reopen(
                  a.tenant,
                  actor,
                  first,
                  Ids.newId(),
                  a.alert("cpu_high", "CPU high", "srv-x", t0),
                  IncidentFixture.reopened(Ids.newId())),
          "reopen is accepted only from vigil-correlation");
      refusedArgument(
          () ->
              incidents.setAssignment(
                  a.tenant,
                  actor,
                  forming,
                  Severity.P1,
                  new Key("db-ops"),
                  Set.of(),
                  Ids.newId(),
                  "Default"),
          "setAssignment is accepted only from vigil-routing");
      refusedArgument(
          () ->
              incidents.recordEscalation(
                  a.tenant,
                  actor,
                  x,
                  1,
                  Set.of(new Key("noc-l1")),
                  EscalationCause.ESCALATE_NOW,
                  null,
                  1),
          "recordEscalation is accepted only from vigil-routing");
      refusedArgument(
          () ->
              incidents.recordNotifiedTeam(
                  a.tenant, actor, x, new Key("db-ops"), NotifiedPurpose.INCIDENT_NOTIFICATION),
          "recordNotifiedTeam is accepted only from vigil-notifications");
      refusedArgument(
          () ->
              incidents.addEvidence(
                  a.tenant,
                  actor,
                  x,
                  EvidenceKind.DIAGNOSTIC_RESULT,
                  Ids.newId(),
                  "lab-db-01 via runner, operation disk_usage",
                  t0,
                  "Disk usage 100%",
                  "{}",
                  DataCategory.DIAGNOSTIC_OUTPUT),
          "addEvidence is accepted only from vigil-diagnostics");
      refusedArgument(
          () -> incidents.setAiStatus(a.tenant, actor, x, AiStatus.AVAILABLE, null, Ids.newId()),
          "setAiStatus is accepted only from vigil-investigation");
      refusedArgument(
          () -> incidents.resolve(a.tenant, actor, x, Ids.newId()),
          "resolve is accepted only from vigil-recovery");
    }
  }

  @Test
  @Order(20)
  @DisplayName("PM-M09-09 the timeline of every change of the first incident")
  void timeline() {
    List<TimelineView> timeline = incidents.timeline(a.tenant, first);
    assertThat(timeline.stream().map(e -> e.entryType().key()))
        .containsExactly(
            "created",
            "alert_joined",
            "alert_joined",
            "alert_joined",
            "hypothesis_changed",
            "impact_changed",
            "title_changed",
            "state_changed",
            "evidence_added",
            "state_changed",
            "state_changed",
            "evidence_added",
            "state_changed");
    assertThat(
            IncidentFixture.Events.of(first).stream()
                .filter(IncidentChanged.class::isInstance)
                .count())
        .isEqualTo(12);
  }

  @Test
  @Order(21)
  @DisplayName("DM-E-39 the timeline entries: texts, actors and references")
  void timelineEntries() {
    List<TimelineView> timeline = incidents.timeline(a.tenant, first);
    String evidence =
        "Evidence: Recovered: 'Subscriber DB' held for 2 min with every monitoring source"
            + " healthy.";
    assertThat(timeline.stream().map(TimelineView::text))
        .containsExactly(
            "Incident created from alert 'Disk full on srv-db-01' (Zabbix).",
            "Alert 'Database write errors on db-subscriber' (Zabbix) joined.",
            "Alert 'Database write errors on db-subscriber' (Zabbix) joined.",
            "Alert 'Balance-check API errors on api-balance' (Zabbix) joined.",
            "Hypothesis: Subscriber DB server.",
            "Customer impact: Balance check, Bundle purchase.",
            "Title: Subscriber DB server: Disk full — 2 services affected",
            "Opened as INC-0001: the alert group is stable.",
            evidence,
            "Resolved: recovery verified.",
            "Reopened by alert 'Database write errors on db-subscriber' (Zabbix).",
            evidence,
            "Resolved: recovery verified.");
    assertThat(timeline.stream().map(e -> e.actor().module()))
        .containsExactly(
            "vigil-correlation",
            "vigil-correlation",
            "vigil-correlation",
            "vigil-correlation",
            "vigil-correlation",
            "vigil-correlation",
            "vigil-correlation",
            "vigil-correlation",
            "vigil-recovery",
            "vigil-recovery",
            "vigil-correlation",
            "vigil-recovery",
            "vigil-recovery");
    assertThat(timeline.get(1).refType()).isEqualTo("IncidentAlertMembership");
    assertThat(timeline.get(1).refId()).isEqualTo(incidents.members(a.tenant, first).get(1).id());
    assertThat(timeline.get(8).refType()).isEqualTo("EvidenceEntry");
    assertThat(timeline.get(8).refId())
        .isEqualTo(incidents.evidence(a.tenant, first).getFirst().id());
    assertThat(timeline).allSatisfy(e -> assertThat(e.at()).isEqualTo(t0));
  }

  @Test
  @Order(22)
  @DisplayName("DM-E08-R3 incident_created and incident_state_changed, each once; per tenant")
  void auditEvents() {
    List<Map<String, Object>> events =
        postgres
            .sql(
                "SELECT event_type, subject_type, subject_id, incident_id, summary,"
                    + " details::text AS details FROM audit.audit_event WHERE tenant_id = :t"
                    + " ORDER BY sequence")
            .param("t", a.tenant.value())
            .query()
            .listOfRows();
    assertThat(events.stream().map(e -> (String) e.get("event_type")).distinct())
        .containsExactlyInAnyOrder("incident_created", "incident_state_changed");
    List<IncidentView> all = incidents.incidents(a.tenant, ALL);
    for (IncidentView incident : all) {
      List<Map<String, Object>> own =
          events.stream().filter(e -> incident.id().equals(e.get("incident_id"))).toList();
      List<TransitionView> transitions = incidents.transitions(a.tenant, incident.id());
      assertThat(own.getFirst().get("event_type")).isEqualTo("incident_created");
      assertThat(own.getFirst().get("subject_type")).isEqualTo("Incident");
      assertThat(own.getFirst().get("subject_id")).isEqualTo(incident.id());
      assertThat(own.getFirst().get("summary")).isEqualTo(transitions.getFirst().reason());
      List<Map<String, Object>> changes = own.subList(1, own.size());
      assertThat(changes).hasSize(transitions.size() - 1);
      for (int i = 0; i < changes.size(); i++) {
        TransitionView t = transitions.get(i + 1);
        Map<String, Object> change = changes.get(i);
        assertThat(change.get("event_type")).isEqualTo("incident_state_changed");
        assertThat(change.get("summary"))
            .isEqualTo(
                IncidentTexts.state(t.fromState())
                    + " → "
                    + IncidentTexts.state(t.toState())
                    + ".");
        assertThat(tree((String) change.get("details")))
            .isEqualTo(
                tree(
                    "{\"from\": \""
                        + t.fromState().key()
                        + "\", \"to\": \""
                        + t.toState().key()
                        + "\"}"));
      }
    }
    assertThat(events.stream().filter(e -> e.get("event_type").equals("incident_created")))
        .hasSize(all.size());
    assertThat(incidents.incidents(b.tenant, ALL)).isEmpty();
    assertThat(incidents.incident(b.tenant, first)).isEmpty();
    assertThat(incidents.incidentOfAlert(b.tenant, firstAlert.alertId())).isEmpty();
    assertThat(incidents.timeline(b.tenant, first)).isEmpty();
  }

  @Test
  @Order(23)
  @DisplayName("DM-R-23 resolved incidents are closed after the reopen window")
  void closure() {
    IncidentFixture c = IncidentFixture.create(app, "Tenant C", HOST_C, "3");
    UUID incident = c.create(c.alert("disk_full", "Disk full", "srv-db-01", CLOCK.instant()));
    c.open(incident);
    c.resolve(incident);
    Instant t1 = CLOCK.instant();
    CLOCK.set(t1.plusSeconds(300));
    IncidentFixture.sleep(Duration.ofSeconds(4));
    assertThat(c.incidents.incident(c.tenant, incident).orElseThrow().state())
        .isEqualTo(IncidentState.RESOLVED);
    CLOCK.advance(Duration.ofMillis(1));
    IncidentFixture.await(
        () ->
            c.incidents.incident(c.tenant, incident).orElseThrow().state() == IncidentState.CLOSED);
    IncidentView closed = c.incidents.incident(c.tenant, incident).orElseThrow();
    assertThat(closed.closedAt()).isEqualTo(t1.plusSeconds(300).plusMillis(1));
    TransitionView transition = c.incidents.transitions(c.tenant, incident).getLast();
    assertThat(transition.transitionId()).isEqualTo("DM-SM05-T6");
    assertThat(transition.actor()).isEqualTo(Actor.module("vigil-incidents"));
    assertThat(transition.reason()).isEqualTo("Closed: the reopen window has passed.");
    assertThat(
            postgres
                .sql(
                    "SELECT summary FROM audit.audit_event WHERE tenant_id = :t"
                        + " AND incident_id = :id AND event_type = 'incident_state_changed'"
                        + " AND actor_module = 'vigil-incidents'")
                .param("t", c.tenant.value())
                .param("id", incident)
                .query(String.class)
                .list())
        .containsExactly("Resolved → Closed.");
    IncidentFixture.await(
        () -> incidents.incidents(a.tenant, Set.of(IncidentState.RESOLVED)).isEmpty());
    assertThat(view(first).state()).isEqualTo(IncidentState.CLOSED);
    assertThatThrownBy(
            () ->
                c.incidents.reopen(
                    c.tenant,
                    IncidentFixture.CORRELATION,
                    incident,
                    Ids.newId(),
                    c.alert("disk_full", "Disk full", "srv-db-01", CLOCK.instant()),
                    IncidentFixture.reopened(Ids.newId())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("reopen is not allowed in state closed");
  }

  @Test
  @Order(24)
  @DisplayName("AR-R-23 the closure check runs every closure_check_interval per tenant")
  void closureInterval() {
    assertThat(DELAYS).contains(Duration.ofSeconds(3));
  }

  @Test
  @Order(25)
  @DisplayName("DM-R-70 closure only for active tenants")
  void activeTenantsOnly() {
    IncidentFixture d = IncidentFixture.create(app, "Tenant D", HOST_D);
    UUID incident = d.create(d.alert("disk_full", "Disk full", "srv-db-01", CLOCK.instant()));
    d.open(incident);
    d.resolve(incident);
    TestTenants.Tenant e = new TestTenants(app).tenant("Tenant E").create();
    IncidentFixture.importSettings(app, e.id(), "300", "2");
    ClosureWork work = IncidentFixture.work(app);
    work.matchActiveTenants();
    assertThat(work.scheduledTenants()).contains(d.tenant).doesNotContain(e.id());
    app.context().getBean(TenantService.class).setAlias(HOST_D, e.id(), "installation token");
    IncidentFixture.await(
        () ->
            work.scheduledTenants().contains(e.id())
                && !work.scheduledTenants().contains(d.tenant));
    CLOCK.advance(Duration.ofSeconds(301));
    IncidentFixture.sleep(Duration.ofSeconds(2));
    assertThat(d.incidents.incident(d.tenant, incident).orElseThrow().state())
        .isEqualTo(IncidentState.RESOLVED);
    TenantId g = new TestTenants(app).tenant("Tenant G").host(HOST_G).create().id();
    work.matchActiveTenants();
    assertThat(work.scheduledTenants()).doesNotContain(g).contains(a.tenant, e.id());
  }

  @Test
  @Order(26)
  @DisplayName("AR-R-19 closure work started and stopped by TenantAliasChanged")
  void startedAndStopped() {
    assertThat(DELAYS).contains(Duration.ofSeconds(2), Duration.ofSeconds(3));
  }

  @Test
  @Order(27)
  @DisplayName("AR-Q-05 a second delivery of TenantAliasChanged to incidents gives the same result")
  void doubleDelivery() {
    IncidentsAliasListener listener =
        AopTestUtils.getUltimateTargetObject(app.context().getBean(IncidentsAliasListener.class));
    TransactionTemplate tx =
        new TransactionTemplate(app.context().getBean(PlatformTransactionManager.class));
    List<Set<TenantId>> scheduled = new CopyOnWriteArrayList<>();
    ClosureWork work = IncidentFixture.work(app);
    DoubleDelivery.assertSameResult(
        postgres,
        "incidents",
        tx,
        () -> {
          listener.on(new TenantAliasChanged(HOST_A, a.tenant, null));
          scheduled.add(work.scheduledTenants());
        });
    assertThat(scheduled).hasSize(2);
    assertThat(scheduled.get(1)).isEqualTo(scheduled.get(0)).contains(a.tenant);
  }

  @Test
  @Order(28)
  @DisplayName("AR-R-24 restart step (6) starts the closure work")
  void restartStep6() {
    app.close();
    start(database);
    IncidentFixture.await(() -> IncidentFixture.work(app).scheduledTenants().contains(a.tenant));
  }
}
