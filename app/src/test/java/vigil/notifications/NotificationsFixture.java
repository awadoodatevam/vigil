package vigil.notifications;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import vigil.identity.api.IdentityService;
import vigil.identity.api.Permission;
import vigil.incidents.api.IncidentService;
import vigil.incidents.api.NotifiedPurpose;
import vigil.incidents.api.Severity;
import vigil.notifications.api.NotificationsImport;
import vigil.notifications.api.NotificationsPart;
import vigil.notifications.api.NotificationsPart.AddressPart;
import vigil.routing.RoutingFixture;
import vigil.routing.api.RoutingImport;
import vigil.shared.Actor;
import vigil.shared.Ids;
import vigil.shared.Key;
import vigil.shared.StepCall;
import vigil.shared.TenantId;
import vigil.testsupport.TestSecrets;
import vigil.testsupport.TestTenants;
import vigil.testsupport.VigilTestApp;
import vigil.ticketing.TicketingFixture;

/**
 * The notifications fixture of P-22 spec Section 6.12: the tenant of {@link TicketingFixture} with
 * the identity of DD-F-01, the {@code teams-demo} and {@code email-demo} instances and the ten
 * credential references of DD-F-07 as rows, the delivery addresses of part 15, the routing rules
 * and escalation policy of part 11; helpers that make an incident ready for messages, acknowledge
 * it, add a ticket link and build step calls.
 */
public final class NotificationsFixture {

  /** The nine Teams addresses of DD-F-07: subject kind, subject key, readable channel name. */
  public static final List<String[]> TEAMS_ADDRESSES =
      List.<String[]>of(
          new String[] {"team", "noc-l1", "Techfinity Demo / NOC L1"},
          new String[] {"team", "db-ops", "Techfinity Demo / Database Operations"},
          new String[] {"team", "vas-service-owners", "Techfinity Demo / VAS Service Owners"},
          new String[] {"team", "it-apps", "Techfinity Demo / IT Applications"},
          new String[] {"team", "noc-shift-lead", "Techfinity Demo / NOC Shift Lead"},
          new String[] {"team", "noc-management", "Techfinity Demo / NOC Management"},
          new String[] {"team", "customer-care", "Techfinity Demo / Customer Care"},
          new String[] {"user", "dbops-engineer", "Techfinity Demo / Approvals — Usman"},
          new String[] {"user", "dbops-lead", "Techfinity Demo / Approvals — Farah"});

  public static final String FROM = "techfinity-demo@vigil.test";
  public static final List<String[]> ALERTS =
      List.<String[]>of(
          new String[] {"disk_full", "Disk full", "srv-db-01", "Disk full on srv-db-01"},
          new String[] {
            "db_write_errors",
            "Database write errors",
            "db-subscriber",
            "Write errors on db-subscriber"
          });
  private static final Actor ROUTING = Actor.module("vigil-routing");
  private static final Actor NOTIFICATIONS = Actor.module("vigil-notifications");

  public final VigilTestApp app;
  public final TenantId tenant;
  public final TicketingFixture ticketing;
  public final UUID teamsInstanceId;
  public final UUID emailInstanceId;
  public final String host;
  private final IncidentService incidents;

  private NotificationsFixture(
      TicketingFixture ticketing, String host, UUID teamsInstanceId, UUID emailInstanceId) {
    this.app = ticketing.app;
    this.host = host;
    this.tenant = ticketing.tenant;
    this.ticketing = ticketing;
    this.teamsInstanceId = teamsInstanceId;
    this.emailInstanceId = emailInstanceId;
    this.incidents = app.context().getBean(IncidentService.class);
  }

  /** The secret name of a Teams address: {@code teams-team-{key}} or {@code teams-user-{key}}. */
  public static String secretName(String subjectKind, String subjectKey) {
    return "teams-" + subjectKind + "-" + subjectKey;
  }

  /**
   * The secrets of the app, generated now: those of {@link TicketingFixture#secrets()}, each Teams
   * webhook at {@code {teams}/teams/{key}} with a generated {@code sig}, and {@code smtp}.
   */
  public static Map<String, String> secrets(String teams, String smtpUser, String smtpPassword) {
    Map<String, String> secrets = new LinkedHashMap<>(TicketingFixture.secrets());
    for (String[] address : TEAMS_ADDRESSES) {
      secrets.put(
          secretName(address[0], address[1]),
          teams
              + "/teams/"
              + address[1]
              + "?api-version=1&sp=%2Ftriggers%2Fmanual%2Frun&sig="
              + TestSecrets.randomToken());
    }
    secrets.put(
        "smtp", "{\"username\":\"" + smtpUser + "\",\"password\":\"" + smtpPassword + "\"}");
    return secrets;
  }

  /** The identity of DD-F-01: the nine teams, five users, their memberships and permissions. */
  public static TestTenants.Builder identity(TestTenants.Builder builder) {
    return builder
        .team("noc-l1", "NOC L1")
        .team("db-ops", "Database Operations")
        .team("it-apps", "IT Applications")
        .team("it-infra", "IT Infrastructure")
        .team("vas-ops", "VAS Operations")
        .team("vas-service-owners", "VAS Service Owners")
        .team("noc-shift-lead", "NOC Shift Lead")
        .team("noc-management", "NOC Management")
        .team("customer-care", "Customer Care")
        .user("presenter", "Demo Presenter", Permission.VIEW)
        .user("noc-l1", "Hira Baig (NOC L1)", Permission.ACKNOWLEDGE)
        .user(
            "dbops-engineer",
            "Usman Tariq (DB Ops)",
            Permission.ACKNOWLEDGE,
            Permission.APPROVE_ACTION)
        .user(
            "dbops-lead",
            "Farah Naqvi (DB Ops Lead)",
            Permission.ACKNOWLEDGE,
            Permission.APPROVE_ACTION)
        .user(
            "admin",
            "Platform Administrator",
            Permission.VIEW,
            Permission.MANAGE_SETUP,
            Permission.MANAGE_ROUTING,
            Permission.MANAGE_AI_SETTINGS,
            Permission.RERUN_AI_INVESTIGATION,
            Permission.EDIT_WORKFLOW,
            Permission.RUN_TEST_REPLAY,
            Permission.PUBLISH_WORKFLOW)
        .member("noc-l1", "noc-l1")
        .member("db-ops", "dbops-engineer")
        .member("db-ops", "dbops-lead");
  }

  /**
   * A tenant (with an alias when {@code host} is not {@code null}) whose Jira is {@code jira} and
   * whose email server is {@code smtp://127.0.0.1:{smtpPort}} without STARTTLS.
   */
  public static NotificationsFixture create(
      VigilTestApp app, String name, String host, String jira, int smtpPort) {
    TicketingFixture ticketing =
        TicketingFixture.create(app, name, host, jira, NotificationsFixture::identity);
    JdbcClient postgres = ticketing.postgres();
    TenantId tenant = ticketing.tenant;
    Map<String, UUID> handles = new LinkedHashMap<>();
    for (String[] address : TEAMS_ADDRESSES) {
      handles.put(
          "cred-teams-" + address[1],
          credential(postgres, tenant, "webhook_url", secretName(address[0], address[1])));
    }
    UUID smtp = credential(postgres, tenant, "smtp", "smtp");
    UUID teams =
        instance(
            postgres,
            tenant,
            "Microsoft Teams — Techfinity Demo",
            "ms_teams",
            "https://teams.microsoft.com",
            "{}",
            null);
    UUID email =
        instance(
            postgres,
            tenant,
            "Email — demo domain",
            "email",
            "smtp://127.0.0.1:" + smtpPort,
            "{\"from_address\":\"" + FROM + "\",\"starttls\":false}",
            smtp);
    app.context()
        .getBean(NotificationsImport.class)
        .importPart(tenant, demoPart(), Instant.now(), handles);
    app.context()
        .getBean(RoutingImport.class)
        .importPart(tenant, RoutingFixture.demoPart(), Instant.now(), Map.of());
    return new NotificationsFixture(ticketing, host, teams, email);
  }

  /** The eighteen delivery addresses of DD-F-07 with the test values {@code {key}@vigil.test}. */
  public static NotificationsPart demoPart() {
    List<AddressPart> addresses = new ArrayList<>();
    for (String[] address : TEAMS_ADDRESSES) {
      addresses.add(
          new AddressPart(address[0], address[1], "teams", address[2], "cred-teams-" + address[1]));
      addresses.add(
          new AddressPart(address[0], address[1], "email", address[1] + "@vigil.test", null));
    }
    return new NotificationsPart(List.copyOf(addresses));
  }

  private static UUID credential(
      JdbcClient postgres, TenantId tenant, String purpose, String secret) {
    UUID id = Ids.newId();
    postgres
        .sql(
            "INSERT INTO connectors.credential_reference (id, tenant_id, created_at, updated_at,"
                + " purpose, locator) VALUES (:id, :t, now(), now(), :purpose, :locator)")
        .param("id", id)
        .param("t", tenant.value())
        .param("purpose", purpose)
        .param("locator", "secret:" + secret)
        .update();
    return id;
  }

  private static UUID instance(
      JdbcClient postgres,
      TenantId tenant,
      String name,
      String kind,
      String address,
      String settings,
      UUID credential) {
    UUID id = Ids.newId();
    postgres
        .sql(
            "INSERT INTO connectors.connector_instance (id, tenant_id, created_at, updated_at,"
                + " name, kind, address, settings, credential_ref_id, active, updated_by_kind,"
                + " updated_by_module) VALUES (:id, :t, now(), now(), :name, :kind, :address,"
                + " CAST(:settings AS jsonb), :credential, true, 'module', 'vigil-setup')")
        .param("id", id)
        .param("t", tenant.value())
        .param("name", name)
        .param("kind", kind)
        .param("address", address)
        .param("settings", settings)
        .param("credential", credential)
        .update();
    return id;
  }

  /** Sets the instance active or not. */
  public void active(UUID instanceId, boolean active) {
    postgres()
        .sql("UPDATE connectors.connector_instance SET active = :a WHERE id = :id")
        .param("a", active)
        .param("id", instanceId)
        .update();
  }

  /**
   * An open incident ready for messages: the two alerts, the hypothesis srv-db-01, severity P1,
   * owner {@code db-ops}, informed {@code vas-service-owners} and {@code customer-care} (set by
   * vigil-routing while it is forming) and one diagnostic entry.
   */
  public UUID openIncident() {
    return openIncident("db-ops", Set.of("vas-service-owners", "customer-care"));
  }

  /** An open incident with this owner and these informed teams. */
  public UUID openIncident(String owner, Set<String> informed) {
    UUID incident =
        ticketing.investigation.openIncident(
            ALERTS,
            "srv-db-01",
            id ->
                incidents.setAssignment(
                    tenant,
                    ROUTING,
                    id,
                    Severity.P1,
                    new Key(owner),
                    informed.stream().map(Key::new).collect(Collectors.toSet()),
                    Ids.newId(),
                    "Critical service affected"));
    ticketing.investigation.addDiagnostic(
        incident,
        "Disk usage of /data",
        "/data is 100% full (15.9 of 15.9 GiB used, 0.1 GiB free)");
    return incident;
  }

  /**
   * Acknowledges the incident as {@code noc-l1}: {@code noc-l1} recorded as notified first, as
   * vigil-notifications, then the acknowledgement.
   */
  public void acknowledge(UUID incident) {
    incidents.recordNotifiedTeam(
        tenant, NOTIFICATIONS, incident, new Key("noc-l1"), NotifiedPurpose.INCIDENT_NOTIFICATION);
    UUID user = userId("noc-l1");
    incidents.acknowledge(tenant, incident, user);
  }

  public UUID userId(String username) {
    return app.context()
        .getBean(IdentityService.class)
        .userByUsername(tenant, new Key(username))
        .orElseThrow()
        .id();
  }

  /** An {@code open} ticket link {@code NOC-1} of the fixture's Jira instance for the incident. */
  public void ticketLink(UUID incident, String jira) {
    Timestamp now = Timestamp.from(Instant.now());
    postgres()
        .sql(
            "INSERT INTO ticketing.ticket_link (id, tenant_id, created_at, updated_at,"
                + " incident_id, connector_instance_id, external_key, url, state) VALUES (:id,"
                + " :t, :now, :now, :incident, :instance, 'NOC-1', :url, 'open')")
        .param("id", Ids.newId())
        .param("t", tenant.value())
        .param("now", now)
        .param("incident", incident)
        .param("instance", ticketing.jiraInstanceId)
        .param("url", jira + "/browse/NOC-1")
        .update();
  }

  /** The incident's Acknowledge link (AR-I-101). */
  public String acknowledgeLink(UUID incident) {
    return "https://" + host + "/m/acknowledge/" + incident;
  }

  /** A Notification step call with the parameters. */
  public StepCall notification(UUID incident, UUID stepExecution, String parameters) {
    return call(incident, stepExecution, StepCall.StepType.NOTIFICATION, parameters, null, "start");
  }

  /** An Escalation step call with the parameters, the step state and the wake reason. */
  public StepCall escalation(
      UUID incident, UUID stepExecution, String parameters, String stepState, String wake) {
    return call(incident, stepExecution, StepCall.StepType.ESCALATION, parameters, stepState, wake);
  }

  private StepCall call(
      UUID incident,
      UUID stepExecution,
      StepCall.StepType type,
      String parameters,
      String stepState,
      String wake) {
    return new StepCall(
        tenant,
        Ids.newId(),
        incident,
        stepExecution,
        new Key(type == StepCall.StepType.NOTIFICATION ? "s-notify" : "s-escalate"),
        type,
        parameters,
        1,
        stepState,
        new StepCall.WakeReason(wake),
        null,
        null);
  }

  public JdbcClient postgres() {
    return app.database().postgres();
  }
}
