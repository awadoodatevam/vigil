package vigil.ticketing;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;
import org.springframework.jdbc.core.simple.JdbcClient;
import vigil.aigateway.AiFixture;
import vigil.incidents.api.AlertCopy;
import vigil.incidents.api.DataCategory;
import vigil.incidents.api.EvidenceKind;
import vigil.incidents.api.GroupingReason;
import vigil.incidents.api.IdentityPairCopy;
import vigil.incidents.api.IncidentService;
import vigil.investigation.InvestigationFixture;
import vigil.setup.KnowledgeParts;
import vigil.shared.Actor;
import vigil.shared.Ids;
import vigil.shared.Key;
import vigil.shared.StepCall;
import vigil.shared.TenantId;
import vigil.testsupport.TestSecrets;
import vigil.testsupport.VigilTestApp;

/**
 * The ticketing fixture of P-21 spec Section 6.10: the tenant of {@link InvestigationFixture} with
 * the {@code jira} credential reference and the {@code jira-demo} instance (DD-F-07) as rows, the
 * tenant settings of DD-F-01, and helpers that open, resolve and reopen incidents and build Ticket
 * step calls.
 */
final class TicketingFixture {

  static final String LABEL = "Demo environment — fictional data";
  static final String SETTINGS =
      "{\"project_key\":\"NOC\",\"issue_type\":\"Task\",\"resolved_status\":\"Done\","
          + "\"reopened_status\":\"To Do\",\"label_prefix\":\"vigil-\"}";
  static final List<String[]> ALERTS =
      List.<String[]>of(
          new String[] {"disk_full", "Disk full", "srv-db-01", "Disk full on srv-db-01"},
          new String[] {
            "db_write_errors",
            "Database write errors",
            "db-subscriber",
            "Write errors on db-subscriber"
          });
  static final String RECOVERY_SUMMARY = "Recovery verified: every condition held for 60 s.";
  static final String RECOVERY_SOURCE = "vigil-recovery, check db-disk-recovered";
  static final String RECOVERY_CONTENT =
      "{\"definition_snapshot\":{\"observation_window_seconds\":60},"
          + "\"window_started_at\":\"2026-11-11T09:12:00Z\","
          + "\"decided_at\":\"2026-11-11T09:13:00Z\","
          + "\"last_evaluation\":{\"conditions\":["
          + "{\"detail\":\"No 'Disk full' alert firing on Subscriber DB server\"},"
          + "{\"detail\":\"No 'Database write errors' alert firing on Subscriber database\"}]}}";
  static final Instant REOPEN_RECEIVED = Instant.parse("2026-11-11T10:20:30.456Z");
  private static final Actor CORRELATION = Actor.module("vigil-correlation");
  private static final Actor RECOVERY = Actor.module("vigil-recovery");
  private static final AtomicLong SEQUENCE = new AtomicLong(1_000_000);

  final VigilTestApp app;
  final TenantId tenant;
  final InvestigationFixture investigation;
  final UUID jiraInstanceId;
  private final IncidentService incidents;

  private TicketingFixture(InvestigationFixture investigation, UUID jiraInstanceId) {
    this.app = investigation.app;
    this.tenant = investigation.tenant;
    this.investigation = investigation;
    this.jiraInstanceId = jiraInstanceId;
    this.incidents = app.context().getBean(IncidentService.class);
  }

  /** The secrets of the app: the AI keys and {@code jira}, generated at test start. */
  static Map<String, String> secrets() {
    Map<String, String> secrets = new LinkedHashMap<>(AiFixture.secrets());
    secrets.put(
        "jira",
        "{\"email\":\"noc-"
            + TestSecrets.randomToken().substring(0, 8).toLowerCase()
            + "@vigil.test\",\"api_token\":\""
            + TestSecrets.randomToken()
            + "\",\"expires_on\":\"2099-12-31\"}");
    return secrets;
  }

  /** A tenant (with an alias when {@code host} is not {@code null}) whose Jira is {@code jira}. */
  static TicketingFixture create(VigilTestApp app, String name, String host, String jira) {
    InvestigationFixture investigation =
        InvestigationFixture.create(
            app, name, host, Map.of(), UnaryOperator.identity(), KnowledgeParts.demoPart());
    JdbcClient postgres = investigation.postgres();
    TenantId tenant = investigation.tenant;
    Timestamp now = Timestamp.from(Instant.now());
    UUID credential = Ids.newId();
    postgres
        .sql(
            "INSERT INTO connectors.credential_reference (id, tenant_id, created_at, updated_at,"
                + " purpose, locator) VALUES (:id, :t, :now, :now, 'external_api', 'secret:jira')")
        .param("id", credential)
        .param("t", tenant.value())
        .param("now", now)
        .update();
    UUID instance = Ids.newId();
    postgres
        .sql(
            "INSERT INTO connectors.connector_instance (id, tenant_id, created_at, updated_at,"
                + " name, kind, address, settings, credential_ref_id, active, updated_by_kind,"
                + " updated_by_module) VALUES (:id, :t, :now, :now, 'Jira Cloud — demo project',"
                + " 'jira_cloud', :address, CAST(:settings AS jsonb), :credential, true,"
                + " 'module', 'vigil-setup')")
        .param("id", instance)
        .param("t", tenant.value())
        .param("now", now)
        .param("address", jira)
        .param("settings", SETTINGS)
        .param("credential", credential)
        .update();
    TicketingFixture fixture = new TicketingFixture(investigation, instance);
    fixture.label(LABEL);
    postgres
        .sql(
            "UPDATE tenancy.tenant_settings SET product_display_name = 'Techfinity'"
                + " WHERE tenant_id = :t")
        .param("t", tenant.value())
        .update();
    return fixture;
  }

  /** Sets {@code environment_label} to {@code NULL}: a Jira title without prefix. */
  TicketingFixture withoutLabel() {
    label(null);
    return this;
  }

  private void label(String label) {
    postgres()
        .sql("UPDATE tenancy.tenant_settings SET environment_label = :l WHERE tenant_id = :t")
        .param("l", label)
        .param("t", tenant.value())
        .update();
  }

  /** Sets the {@code jira-demo} instance active or not. */
  void jiraActive(boolean active) {
    postgres()
        .sql("UPDATE connectors.connector_instance SET active = :a WHERE id = :id")
        .param("a", active)
        .param("id", jiraInstanceId)
        .update();
  }

  /** An open incident with the two alerts, the hypothesis srv-db-01 and one diagnostic entry. */
  UUID openIncident() {
    UUID incident = investigation.openIncident(ALERTS, "srv-db-01");
    investigation.addDiagnostic(
        incident,
        "Disk usage of /data",
        "/data is 100% full (15.9 of 15.9 GiB used, 0.1 GiB free)");
    return incident;
  }

  /** A recovery verification entry (Section 6.6.3 content) as vigil-recovery; returns its id. */
  UUID addRecovery(UUID incident, String content) {
    UUID verification = Ids.newId();
    incidents.addEvidence(
        tenant,
        RECOVERY,
        incident,
        EvidenceKind.RECOVERY_VERIFICATION,
        verification,
        RECOVERY_SOURCE,
        Instant.now(),
        RECOVERY_SUMMARY,
        content,
        DataCategory.INCIDENT_RECORD);
    return verification;
  }

  /** Resolves the incident as vigil-recovery with a recovery entry; returns the verification. */
  UUID resolve(UUID incident) {
    UUID verification = addRecovery(incident, RECOVERY_CONTENT);
    incidents.resolve(tenant, RECOVERY, incident, verification);
    return verification;
  }

  /**
   * Reopens the resolved incident as vigil-correlation with a new alert whose row is in {@code
   * intake.alert} ({@code withAlertRow}) or not; returns the alert's id.
   */
  UUID reopen(UUID incident, boolean withAlertRow) {
    UUID alertId = Ids.newId();
    String title = "Disk full on srv-db-01";
    if (withAlertRow) {
      Timestamp received = Timestamp.from(REOPEN_RECEIVED);
      postgres()
          .sql(
              "INSERT INTO intake.alert (id, tenant_id, created_at, updated_at, source_id,"
                  + " source_kind, alert_key, alert_type, identity_pairs, title, status, fired_at,"
                  + " first_received_at, entry_count, sequence) VALUES (:id, :t, :r, :r, :source,"
                  + " 'zabbix', :key, 'disk_full', CAST('[]' AS jsonb), :title, 'firing', :r, :r,"
                  + " 1, :seq)")
          .param("id", alertId)
          .param("t", tenant.value())
          .param("r", received)
          .param("source", Ids.newId())
          .param("key", "k-" + alertId)
          .param("title", title)
          .param("seq", SEQUENCE.incrementAndGet())
          .update();
    }
    UUID related = incidents.members(tenant, incident).getFirst().alertId();
    incidents.reopen(
        tenant,
        CORRELATION,
        incident,
        Ids.newId(),
        new AlertCopy(
            alertId,
            "zabbix",
            "k-" + alertId,
            "disk_full",
            "Disk full",
            title,
            "srv-db-01",
            new IdentityPairCopy("component", "srv-db-01"),
            REOPEN_RECEIVED),
        new GroupingReason("reopened", related, "same_component", List.of("srv-db-01"), 5L));
    return alertId;
  }

  /** A Ticket step call with the operation. */
  StepCall call(UUID incident, UUID stepExecution, String operation) {
    return call(incident, stepExecution, "{\"operation\": \"" + operation + "\"}", 1);
  }

  StepCall call(UUID incident, UUID stepExecution, String parameters, long evaluationCount) {
    return new StepCall(
        tenant,
        Ids.newId(),
        incident,
        stepExecution,
        new Key("s-ticket"),
        StepCall.StepType.TICKET,
        parameters,
        evaluationCount,
        null,
        new StepCall.WakeReason("start"),
        null,
        null);
  }

  JdbcClient postgres() {
    return app.database().postgres();
  }
}
