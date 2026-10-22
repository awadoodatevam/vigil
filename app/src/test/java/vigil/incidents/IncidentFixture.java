package vigil.incidents;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.event.TransactionalEventListener;
import vigil.identity.api.IdentityService;
import vigil.identity.api.Permission;
import vigil.incidents.api.AffectedServiceCopy;
import vigil.incidents.api.AlertCopy;
import vigil.incidents.api.DataCategory;
import vigil.incidents.api.EvidenceKind;
import vigil.incidents.api.FinalReason;
import vigil.incidents.api.GroupingReason;
import vigil.incidents.api.HypothesisCopy;
import vigil.incidents.api.IdentityPairCopy;
import vigil.incidents.api.IncidentAcknowledged;
import vigil.incidents.api.IncidentChanged;
import vigil.incidents.api.IncidentCreated;
import vigil.incidents.api.IncidentImport;
import vigil.incidents.api.IncidentService;
import vigil.incidents.api.IncidentSettingsPart;
import vigil.incidents.api.IncidentStateChanged;
import vigil.incidents.api.MembershipView;
import vigil.shared.Actor;
import vigil.shared.Ids;
import vigil.shared.Key;
import vigil.shared.TenantId;
import vigil.testsupport.TestTenants;
import vigil.testsupport.VigilTestApp;

/**
 * The incident fixture of P-14 spec Section 7: a {@code ready} tenant with a host alias, its teams
 * and users, the incident settings imported through {@link IncidentImport} with its closure work
 * scheduled, helpers that build alert copies and make the requests, and the event recorder.
 */
final class IncidentFixture {

  static final Duration WAIT = Duration.ofSeconds(5);
  static final Actor CORRELATION = Actor.module("vigil-correlation");
  static final Actor ROUTING = Actor.module("vigil-routing");
  static final Actor NOTIFICATIONS = Actor.module("vigil-notifications");
  static final Actor INVESTIGATION = Actor.module("vigil-investigation");
  static final Actor RECOVERY = Actor.module("vigil-recovery");
  static final Actor DIAGNOSTICS = Actor.module("vigil-diagnostics");
  static final HypothesisCopy SRV_DB_01 =
      new HypothesisCopy(1, "srv-db-01", "Subscriber DB server", "server", "critical");
  static final List<AffectedServiceCopy> STORM_SERVICES =
      List.of(
          new AffectedServiceCopy(
              "svc-balance-check", "Balance check", null, List.of("api-balance", "db-subscriber")),
          new AffectedServiceCopy(
              "svc-bundle-purchase", "Bundle purchase", null, List.of("db-subscriber")));

  final VigilTestApp app;
  final TenantId tenant;
  final IncidentService incidents;
  private int alerts;

  private IncidentFixture(VigilTestApp app, TenantId tenant) {
    this.app = app;
    this.tenant = tenant;
    this.incidents = app.context().getBean(IncidentService.class);
  }

  /** The fixture on a new tenant with {@code host}: reopen window 300 s, closure check 1 s. */
  static IncidentFixture create(VigilTestApp app, String name, String host) {
    return create(app, name, host, "1");
  }

  /** The fixture with the closure check interval {@code interval} (seconds, as text). */
  static IncidentFixture create(VigilTestApp app, String name, String host, String interval) {
    TenantId tenant =
        new TestTenants(app)
            .tenant(name)
            .host(host)
            .team("db-ops", "Database Operations")
            .team("noc-l1", "NOC Level 1")
            .team("it-apps", "IT Applications")
            .user("dbops-engineer", "DB Ops Engineer", Permission.ACKNOWLEDGE)
            .user("noc-l1", "NOC Operator", Permission.ACKNOWLEDGE)
            .user("viewer", "Viewer", Permission.VIEW)
            .user("outsider", "Outsider", Permission.ACKNOWLEDGE)
            .member("db-ops", "dbops-engineer")
            .member("noc-l1", "noc-l1")
            .member("db-ops", "viewer")
            .member("it-apps", "outsider")
            .create()
            .id();
    importSettings(app, tenant, "300", interval);
    ClosureWork work = work(app);
    work.matchActiveTenants();
    await(() -> work.scheduledTenants().contains(tenant));
    return new IncidentFixture(app, tenant);
  }

  static void importSettings(VigilTestApp app, TenantId tenant, String window, String interval) {
    app.context()
        .getBean(IncidentImport.class)
        .importPart(tenant, new IncidentSettingsPart(window, interval), Instant.now(), Map.of());
  }

  static ClosureWork work(VigilTestApp app) {
    return app.context().getBean(ClosureWork.class);
  }

  UUID userId(String username) {
    return app.context()
        .getBean(IdentityService.class)
        .userByUsername(tenant, new Key(username))
        .orElseThrow()
        .id();
  }

  // Alert copies and grouping reasons.

  /** An alert copy of source kind zabbix with a new alert id and a new alert key. */
  AlertCopy alert(String type, String typeName, String component, Instant firedAt) {
    alerts++;
    return alert("k" + alerts + "-" + UUID.randomUUID(), type, typeName, component, firedAt);
  }

  static AlertCopy alert(
      String key, String type, String typeName, String component, Instant firedAt) {
    return new AlertCopy(
        Ids.newId(),
        "zabbix",
        key,
        type,
        typeName,
        typeName + " on " + component,
        component,
        new IdentityPairCopy("component", component),
        firedAt);
  }

  static GroupingReason created() {
    return new GroupingReason("created_incident", null, null, null, null);
  }

  static GroupingReason joined(UUID relatedAlertId) {
    return new GroupingReason("joined", relatedAlertId, "same_component", List.of("c"), 10L);
  }

  static GroupingReason reopened(UUID relatedAlertId) {
    return new GroupingReason("reopened", relatedAlertId, "same_component", List.of("c"), 20L);
  }

  // Requests.

  UUID create(AlertCopy alert) {
    return incidents.create(tenant, CORRELATION, Ids.newId(), alert, created());
  }

  void add(UUID incidentId, AlertCopy alert) {
    incidents.addAlert(tenant, CORRELATION, incidentId, Ids.newId(), alert, joined(Ids.newId()));
  }

  /**
   * A storm-like incident: created with an alert of {@code srv-db-01} fired at {@code t0} − 60 s,
   * joined by alerts of {@code db-subscriber} (− 50 s, − 40 s) and {@code api-balance} (− 30 s),
   * then given the storm-like hypothesis and impact.
   */
  UUID stormLike(Instant t0) {
    UUID id = create(alert("disk_full", "Disk full", "srv-db-01", t0.minusSeconds(60)));
    add(
        id,
        alert("db_write_errors", "Database write errors", "db-subscriber", t0.minusSeconds(50)));
    add(
        id,
        alert("db_write_errors", "Database write errors", "db-subscriber", t0.minusSeconds(40)));
    add(
        id,
        alert(
            "balance_api_errors", "Balance-check API errors", "api-balance", t0.minusSeconds(30)));
    incidents.setHypothesisAndImpact(
        tenant, CORRELATION, id, SRV_DB_01, Ids.newId(), STORM_SERVICES);
    return id;
  }

  /** Opens the incident with {@code only_alert} as each member's final reason. */
  void open(UUID incidentId) {
    Map<UUID, FinalReason> reasons = new LinkedHashMap<>();
    for (MembershipView member : incidents.members(tenant, incidentId)) {
      reasons.put(member.alertId(), new FinalReason(null, FinalReason.ONLY_ALERT, null, null));
    }
    incidents.open(tenant, CORRELATION, incidentId, reasons);
  }

  /** An evidence entry of kind {@code recovery_verification}; returns the verification id. */
  UUID verification(UUID incidentId) {
    UUID verificationId = Ids.newId();
    incidents.addEvidence(
        tenant,
        RECOVERY,
        incidentId,
        EvidenceKind.RECOVERY_VERIFICATION,
        verificationId,
        "Monitoring: Zabbix lab",
        Instant.now(),
        "Recovered: 'Subscriber DB' held for 2 min with every monitoring source healthy.",
        "{\"outcome\": \"recovered\"}",
        DataCategory.INCIDENT_RECORD);
    return verificationId;
  }

  /** Resolves the open incident with a new verification's evidence entry. */
  UUID resolve(UUID incidentId) {
    UUID verificationId = verification(incidentId);
    incidents.resolve(tenant, RECOVERY, incidentId, verificationId);
    return verificationId;
  }

  void notified(UUID incidentId, String teamKey) {
    incidents.recordNotifiedTeam(
        tenant,
        NOTIFICATIONS,
        incidentId,
        new Key(teamKey),
        vigil.incidents.api.NotifiedPurpose.INCIDENT_NOTIFICATION);
  }

  // Records read as postgres.

  static long count(JdbcClient postgres, String sql, TenantId tenant) {
    return postgres.sql(sql).param("t", tenant.value()).query(Long.class).single();
  }

  /** The number of rows of each table of {@code incidents} of the tenant. */
  static Map<String, Long> rows(JdbcClient postgres, TenantId tenant) {
    Map<String, Long> rows = new LinkedHashMap<>();
    for (String table :
        List.of(
            "incident",
            "incident_state_transition",
            "incident_alert_membership",
            "acknowledgement",
            "incident_escalation",
            "notified_team",
            "evidence_entry",
            "timeline_entry",
            "incident_settings")) {
      rows.put(
          table,
          count(
              postgres,
              "SELECT count(*) FROM incidents." + table + " WHERE tenant_id = :t",
              tenant));
    }
    rows.put(
        "audit",
        count(postgres, "SELECT count(*) FROM audit.audit_event WHERE tenant_id = :t", tenant));
    return rows;
  }

  static void await(BooleanSupplier condition) {
    long deadline = System.nanoTime() + WAIT.toNanos();
    while (!condition.getAsBoolean()) {
      assertThat(System.nanoTime()).as("condition met within %s", WAIT).isLessThan(deadline);
      sleep(Duration.ofMillis(50));
    }
  }

  static void sleep(Duration duration) {
    try {
      Thread.sleep(duration);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  /** Records the four incident events after their transaction committed, across app restarts. */
  static class Events {

    static final List<Object> RECEIVED = new CopyOnWriteArrayList<>();

    @TransactionalEventListener
    void on(IncidentCreated event) {
      RECEIVED.add(event);
    }

    @TransactionalEventListener
    void on(IncidentAcknowledged event) {
      RECEIVED.add(event);
    }

    @TransactionalEventListener
    void on(IncidentStateChanged event) {
      RECEIVED.add(event);
    }

    @TransactionalEventListener
    void on(IncidentChanged event) {
      RECEIVED.add(event);
    }

    /** The events of one incident, in order. */
    static List<Object> of(UUID incidentId) {
      List<Object> events = new ArrayList<>();
      for (Object event : RECEIVED) {
        UUID id =
            switch (event) {
              case IncidentCreated e -> e.incidentId();
              case IncidentAcknowledged e -> e.incidentId();
              case IncidentStateChanged e -> e.incidentId();
              case IncidentChanged e -> e.incidentId();
              default -> null;
            };
        if (incidentId.equals(id)) {
          events.add(event);
        }
      }
      return events;
    }

    /** The events of one tenant, in order. */
    static List<Object> ofTenant(TenantId tenant) {
      return RECEIVED.stream()
          .filter(
              event ->
                  switch (event) {
                    case IncidentCreated e -> e.tenantId().equals(tenant);
                    case IncidentAcknowledged e -> e.tenantId().equals(tenant);
                    case IncidentStateChanged e -> e.tenantId().equals(tenant);
                    case IncidentChanged e -> e.tenantId().equals(tenant);
                    default -> false;
                  })
          .toList();
    }
  }
}
