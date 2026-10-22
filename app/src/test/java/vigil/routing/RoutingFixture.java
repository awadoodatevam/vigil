package vigil.routing;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vigil.identity.api.IdentityService;
import vigil.identity.api.Permission;
import vigil.incidents.api.AffectedServiceCopy;
import vigil.incidents.api.AlertCopy;
import vigil.incidents.api.FinalReason;
import vigil.incidents.api.GroupingReason;
import vigil.incidents.api.HypothesisCopy;
import vigil.incidents.api.IdentityPairCopy;
import vigil.incidents.api.IncidentService;
import vigil.incidents.api.MembershipView;
import vigil.incidents.api.Severity;
import vigil.routing.api.ConditionPart;
import vigil.routing.api.LevelPart;
import vigil.routing.api.OwnerMode;
import vigil.routing.api.RoutingCondition;
import vigil.routing.api.RoutingField;
import vigil.routing.api.RoutingImport;
import vigil.routing.api.RoutingOperator;
import vigil.routing.api.RoutingPart;
import vigil.routing.api.RoutingRule;
import vigil.routing.api.RoutingService;
import vigil.routing.api.RulePart;
import vigil.servicemap.api.ServiceMapService;
import vigil.shared.Actor;
import vigil.shared.Ids;
import vigil.shared.Key;
import vigil.shared.TenantId;
import vigil.testsupport.TestTenants;
import vigil.testsupport.VigilDatabase;
import vigil.testsupport.VigilTestApp;

/**
 * The routing fixture of P-15 spec Section 7: a tenant with the fixture's teams and users, {@code
 * old-team} inactive, the fixture's service map, routing part 11 of the demo data imported, and
 * helpers that create incidents from {@code vigil-correlation}.
 */
public final class RoutingFixture {

  public static final Actor CORRELATION = Actor.module("vigil-correlation");
  public static final Actor WORKFLOWS = Actor.module("vigil-workflows");

  /** The fixture's service map file (exact). */
  static final String SERVICE_MAP =
      """
      services:
        - {service_key: svc-pay,   name: "Payments",    criticality: critical, owner_team_key: vas-service-owners, component_keys: [db-pay]}
        - {service_key: svc-care,  name: "Care portal", criticality: high,     owner_team_key: customer-care,      component_keys: [app-care]}
        - {service_key: svc-rep,   name: "Reports",                            owner_team_key: it-apps,            component_keys: [app-rep]}
        - {service_key: svc-old,   name: "Legacy",      criticality: critical, owner_team_key: old-team,           component_keys: [app-rep]}
        - {service_key: svc-none,  name: "Unowned",     criticality: critical,                                     component_keys: [app-rep]}
        - {service_key: svc-ghost, name: "Ghost owned", criticality: critical, owner_team_key: ghost-team,         component_keys: [app-rep]}
      components:
        - {component_key: srv-pay,  name: "Payments DB server", kind: server,      criticality: critical, owner_team_key: db-ops}
        - {component_key: db-pay,   name: "Payments database",  kind: database,    criticality: critical, owner_team_key: db-ops, runs_on_key: srv-pay}
        - {component_key: app-care, name: "Care portal app",    kind: application, criticality: high,     owner_team_key: customer-care}
        - {component_key: app-rep,  name: "Report job",         kind: application, criticality: low,      owner_team_key: it-apps}
        - {component_key: srv-lone, name: "Lonely server",      kind: server}
        - {component_key: srv-old,  name: "Old server",         kind: server,      criticality: medium,   owner_team_key: old-team}
      identity_keys:
        - {component_key: db-pay, source_kind: zabbix, name: component, value: db-pay}
      dependencies:
        - {from_component_key: app-care, to_component_key: db-pay}
      """;

  /** The components of the file: name, kind and criticality ({@code null} when empty). */
  private static final Map<String, List<String>> COMPONENTS =
      Map.of(
          "srv-pay", List.of("Payments DB server", "server", "critical"),
          "db-pay", List.of("Payments database", "database", "critical"),
          "app-care", List.of("Care portal app", "application", "high"),
          "app-rep", List.of("Report job", "application", "low"),
          "srv-lone", List.of("Lonely server", "server", ""),
          "srv-old", List.of("Old server", "server", "medium"));

  /** The services of the file: name and criticality ({@code ""} when empty). */
  private static final Map<String, List<String>> SERVICES =
      Map.of(
          "svc-pay", List.of("Payments", "critical"),
          "svc-care", List.of("Care portal", "high"),
          "svc-rep", List.of("Reports", ""),
          "svc-old", List.of("Legacy", "critical"),
          "svc-none", List.of("Unowned", "critical"),
          "svc-ghost", List.of("Ghost owned", "critical"));

  public final VigilTestApp app;
  public final TenantId tenant;
  public final IncidentService incidents;
  public final RoutingService routing;

  private RoutingFixture(VigilTestApp app, TenantId tenant) {
    this.app = app;
    this.tenant = tenant;
    this.incidents = app.context().getBean(IncidentService.class);
    this.routing = app.context().getBean(RoutingService.class);
  }

  /** The fixture on a new tenant named {@code name}. */
  public static RoutingFixture create(VigilTestApp app, VigilDatabase database, String name) {
    TenantId tenant =
        teams(new TestTenants(app).tenant(name))
            .user("admin", "Admin", Permission.VIEW, Permission.MANAGE_ROUTING)
            .user("viewer", "Viewer", Permission.VIEW)
            .create()
            .id();
    database
        .postgres()
        .sql(
            "UPDATE identity.team SET active = false"
                + " WHERE tenant_id = :t AND team_key = 'old-team'")
        .param("t", tenant.value())
        .update();
    app.context()
        .getBean(ServiceMapService.class)
        .importFile(
            tenant,
            "service-map.yaml",
            SERVICE_MAP.getBytes(StandardCharsets.UTF_8),
            Actor.module("vigil-setup"));
    app.context()
        .getBean(RoutingImport.class)
        .importPart(tenant, demoPart(), Instant.now(), Map.of());
    return new RoutingFixture(app, tenant);
  }

  /** The fixture's eight teams added to a tenant to be built. */
  public static TestTenants.Builder teams(TestTenants.Builder builder) {
    return builder
        .team("db-ops", "Database Operations")
        .team("noc-l1", "NOC L1")
        .team("it-apps", "IT Applications")
        .team("vas-service-owners", "VAS Service Owners")
        .team("customer-care", "Customer Care")
        .team("noc-shift-lead", "NOC Shift Lead")
        .team("noc-management", "NOC Management")
        .team("old-team", "Old Team");
  }

  /** The three rules and two levels of {@code 05-routing-escalation.yaml} as written there. */
  public static RoutingPart demoPart() {
    return new RoutingPart(
        List.of(
            rulePart("critical-service", "Critical service affected", "critical", "P1", "true"),
            rulePart("high-service", "High-criticality service affected", "high", "P2", "true"),
            new RulePart(
                "default",
                "Default",
                List.of(),
                "P4",
                "hypothesis_component_owner",
                "noc-l1",
                List.of(),
                "false")),
        List.of(
            new LevelPart("1", List.of("noc-shift-lead")),
            new LevelPart("2", List.of("noc-management"))));
  }

  private static RulePart rulePart(
      String key, String name, String criticality, String severity, String inform) {
    return new RulePart(
        key,
        name,
        List.of(new ConditionPart("highest_service_criticality", "at_least", List.of(criticality))),
        severity,
        "hypothesis_component_owner",
        "noc-l1",
        List.of(),
        inform);
  }

  /** The same three rules as {@code RoutingRule}s. */
  public static List<RoutingRule> demoRules() {
    return List.of(
        demoRule("critical-service", "Critical service affected", "critical", Severity.P1, true),
        demoRule("high-service", "High-criticality service affected", "high", Severity.P2, true),
        new RoutingRule(
            "default",
            "Default",
            List.of(),
            Severity.P4,
            OwnerMode.HYPOTHESIS_COMPONENT_OWNER,
            "noc-l1",
            List.of(),
            false));
  }

  private static RoutingRule demoRule(
      String key, String name, String criticality, Severity severity, boolean inform) {
    return new RoutingRule(
        key,
        name,
        List.of(
            new RoutingCondition(
                RoutingField.HIGHEST_SERVICE_CRITICALITY,
                RoutingOperator.AT_LEAST,
                List.of(criticality))),
        severity,
        OwnerMode.HYPOTHESIS_COMPONENT_OWNER,
        "noc-l1",
        List.of(),
        inform);
  }

  /**
   * An incident with the hypothesis {@code hypothesisKey} ({@code null} for none) and the affected
   * services, copied from the fixture's file, via the hypothesis component or {@code app-rep}.
   */
  public UUID incident(String hypothesisKey, List<String> serviceKeys) {
    HypothesisCopy hypothesis = null;
    if (hypothesisKey != null) {
      List<String> component = COMPONENTS.get(hypothesisKey);
      hypothesis =
          new HypothesisCopy(
              1,
              hypothesisKey,
              component.get(0),
              component.get(1),
              component.get(2).isEmpty() ? null : component.get(2));
    }
    String via = hypothesisKey == null ? "app-rep" : hypothesisKey;
    List<AffectedServiceCopy> services =
        serviceKeys.stream()
            .map(
                key ->
                    new AffectedServiceCopy(
                        key,
                        SERVICES.get(key).get(0),
                        SERVICES.get(key).get(1).isEmpty() ? null : SERVICES.get(key).get(1),
                        List.of(via)))
            .toList();
    return incidentWith(hypothesis, services);
  }

  /** An incident with the given hypothesis copy ({@code null} for none) and service copies. */
  public UUID incidentWith(HypothesisCopy hypothesis, List<AffectedServiceCopy> services) {
    String component = hypothesis == null ? "srv-lone" : hypothesis.componentKey();
    AlertCopy alert =
        new AlertCopy(
            Ids.newId(),
            "zabbix",
            "k-" + UUID.randomUUID(),
            "disk_full",
            "Disk full",
            "Disk full on " + component,
            component,
            new IdentityPairCopy("component", component),
            app.context().getBean(Clock.class).instant());
    UUID id =
        incidents.create(
            tenant,
            CORRELATION,
            Ids.newId(),
            alert,
            new GroupingReason("created_incident", null, null, null, null));
    if (hypothesis != null || !services.isEmpty()) {
      incidents.setHypothesisAndImpact(
          tenant, CORRELATION, id, hypothesis, hypothesis == null ? null : Ids.newId(), services);
    }
    return id;
  }

  /** Opens the incident with {@code only_alert} as each active member's final reason. */
  public void open(UUID incidentId) {
    Map<UUID, FinalReason> reasons = new LinkedHashMap<>();
    for (MembershipView member : incidents.members(tenant, incidentId)) {
      reasons.put(member.alertId(), new FinalReason(null, FinalReason.ONLY_ALERT, null, null));
    }
    incidents.open(tenant, CORRELATION, incidentId, reasons);
  }

  /** The current revision's rules. */
  public List<RoutingRule> rules() {
    return routing.ruleSet(tenant).orElseThrow().rules();
  }

  public UUID userId(String username) {
    return app.context()
        .getBean(IdentityService.class)
        .userByUsername(tenant, new Key(username))
        .orElseThrow()
        .id();
  }

  /** The tenant's audit events of {@code type} counted as postgres, with an optional condition. */
  public static long auditEvents(JdbcClient postgres, TenantId tenant, String type, String where) {
    return postgres
        .sql(
            "SELECT count(*) FROM audit.audit_event WHERE tenant_id = :t AND event_type = :type"
                + (where == null ? "" : " AND " + where))
        .param("t", tenant.value())
        .param("type", type)
        .query(Long.class)
        .single();
  }
}
