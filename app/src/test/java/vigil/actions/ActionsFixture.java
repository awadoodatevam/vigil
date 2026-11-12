package vigil.actions;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.yaml.YAMLMapper;
import vigil.actions.api.ActionsImport;
import vigil.actions.api.ActionsPart;
import vigil.actions.api.ActionsPart.ActionPart;
import vigil.actions.api.ActionsPart.PolicyPart;
import vigil.audit.api.AuditEventType;
import vigil.audit.api.AuditEventView;
import vigil.audit.api.AuditQuery;
import vigil.audit.api.AuditService;
import vigil.incidents.api.DataCategory;
import vigil.incidents.api.EvidenceKind;
import vigil.incidents.api.EvidenceView;
import vigil.incidents.api.IncidentService;
import vigil.knowledge.api.KnowledgeService;
import vigil.knowledge.api.PastIncidentView;
import vigil.knowledge.api.SearchRequest;
import vigil.knowledge.api.SearchResult;
import vigil.knowledge.api.SimilarityResultView;
import vigil.notifications.NotificationsApp;
import vigil.notifications.NotificationsFixture;
import vigil.recovery.RecoveryFixture;
import vigil.recovery.api.RecoveryImport;
import vigil.runner.LabTarget;
import vigil.runner.RunnerFixture;
import vigil.runner.api.RunnerImport;
import vigil.shared.Actor;
import vigil.shared.Ids;
import vigil.shared.Key;
import vigil.shared.StepCall;
import vigil.shared.StepCancel;
import vigil.shared.TenantId;
import vigil.testsupport.VigilTestApp;

/**
 * The actions fixture of P-24 spec Section 6.12: a tenant of {@link NotificationsFixture} with the
 * lab target {@code lab-db-01} (its secrets, credential rows, connector instance and part 4), part
 * 13 and part 14, the grant {@code approve_action} of {@code noc-l1}; helpers that make incidents,
 * similarity results and evidence, step calls and inserted executions.
 */
public final class ActionsFixture {

  public static final JsonMapper JSON = JsonMapper.builder().build();
  public static final String APPROVAL = "{\"eligible_teams\": [\"db-ops\"], \"expiry\": 900}";
  private static final Path OPERATIONS =
      Path.of("../docs/31-demo-data/04-operations-actions-recovery.yaml");
  private static final Actor INVESTIGATION = Actor.module("vigil-investigation");

  public final NotificationsFixture notifications;
  public final VigilTestApp app;
  public final TenantId tenant;
  public final IncidentService incidents;
  public final KnowledgeService knowledge;

  private ActionsFixture(NotificationsFixture notifications) {
    this.notifications = notifications;
    this.app = notifications.app;
    this.tenant = notifications.tenant;
    this.incidents = app.context().getBean(IncidentService.class);
    this.knowledge = app.context().getBean(KnowledgeService.class);
  }

  /** A tenant with the demo part 14. */
  public static ActionsFixture create(NotificationsApp servers, String name) {
    return create(servers, name, demoPart());
  }

  /** A tenant with the given part 14. */
  public static ActionsFixture create(NotificationsApp servers, String name, ActionsPart part14) {
    NotificationsFixture f = servers.tenant(name);
    LabTarget lab = LabTarget.shared();
    lab.secrets().forEach(servers.secrets::replace);
    JdbcClient postgres = f.postgres();
    UUID diag = credential(postgres, f.tenant, "target_diagnostic", "lab-db-01-diag");
    UUID action = credential(postgres, f.tenant, "target_action", "lab-db-01-action");
    ObjectNode settings = JSON.createObjectNode();
    settings.put("port", lab.port());
    settings.put("host_key_sha256", lab.fingerprint());
    UUID instance = Ids.newId();
    postgres
        .sql(
            "INSERT INTO connectors.connector_instance (id, tenant_id, created_at, updated_at,"
                + " name, kind, address, settings, credential_ref_id, active, updated_by_kind,"
                + " updated_by_module) VALUES (:id, :t, now(), now(), 'Subscriber DB server"
                + " (lab)', 'linux_server', :address, CAST(:settings AS jsonb), NULL, true,"
                + " 'module', 'vigil-setup')")
        .param("id", instance)
        .param("t", f.tenant.value())
        .param("address", lab.host())
        .param("settings", JSON.writeValueAsString(settings))
        .update();
    f.app
        .context()
        .getBean(RunnerImport.class)
        .importPart(
            f.tenant,
            RunnerFixture.demoPart(),
            Instant.now(),
            Map.of(
                "cred-lab-db-01-diag", diag,
                "cred-lab-db-01-action", action,
                "linux-lab-db-01", instance));
    f.app
        .context()
        .getBean(RecoveryImport.class)
        .importPart(f.tenant, RecoveryFixture.demoPart(), Instant.now(), Map.of());
    f.app
        .context()
        .getBean(ActionsImport.class)
        .importPart(f.tenant, part14, Instant.now(), Map.of());
    postgres
        .sql(
            "INSERT INTO identity.permission_grant (id, tenant_id, created_at, updated_at,"
                + " user_id, permission, active) VALUES (:id, :t, now(), now(), :user,"
                + " 'approve_action', true)")
        .param("id", Ids.newId())
        .param("t", f.tenant.value())
        .param("user", f.userId("noc-l1"))
        .update();
    return new ActionsFixture(f);
  }

  /** The sections {@code registered_actions} and {@code action_policies} of the demo data. */
  public static ActionsPart demoPart() {
    JsonNode file;
    try {
      file =
          YAMLMapper.builder()
              .build()
              .readTree(Files.readString(OPERATIONS, StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    List<ActionPart> actions = new ArrayList<>();
    for (JsonNode a : file.get("registered_actions")) {
      actions.add(
          new ActionPart(
              a.get("action_key").asString(),
              a.get("name").asString(),
              a.get("operation_key").asString(),
              a.get("parameter_values"),
              a.get("limits"),
              a.get("attempt_limit").asString(),
              a.get("precondition"),
              a.get("reconciliation"),
              a.get("recovery_check_key").asString()));
    }
    List<PolicyPart> policies = new ArrayList<>();
    for (JsonNode p : file.get("action_policies")) {
      JsonNode service = p.get("service_key");
      policies.add(
          new PolicyPart(
              p.get("action_key").asString(),
              service == null || service.isNull() ? null : service.asString(),
              p.get("outcome").asString()));
    }
    return new ActionsPart(List.copyOf(actions), List.copyOf(policies));
  }

  /** The demo part with this attempt limit and these policies. */
  public static ActionsPart part(long attemptLimit, PolicyPart... policies) {
    ActionsPart demo = demoPart();
    ActionPart a = demo.actions().getFirst();
    return new ActionsPart(
        List.of(
            new ActionPart(
                a.actionKey(),
                a.name(),
                a.operationKey(),
                a.parameterValues(),
                a.limits(),
                Long.toString(attemptLimit),
                a.precondition(),
                a.reconciliation(),
                a.recoveryCheckKey())),
        policies.length == 0 ? demo.policies() : List.of(policies));
  }

  /** A policy of the demo action; {@code service} {@code null} for every service. */
  public static PolicyPart policy(String service, String outcome) {
    return new PolicyPart("purge-archived-txlogs", service, outcome);
  }

  // Incidents, results and evidence.

  /**
   * An open incident with hypothesis srv-db-01, affected services svc-balance-check and
   * svc-bundle-purchase and one diagnostic entry.
   */
  public UUID openIncident() {
    return notifications.openIncident();
  }

  /** An open incident with one alert on srv-db-01 and no affected service. */
  public UUID incidentWithoutServices() {
    return notifications.ticketing.investigation.openIncident(
        List.<String[]>of(NotificationsFixture.ALERTS.get(0)), "srv-db-01");
  }

  /** An open incident with no hypothesis. */
  public UUID incidentWithoutHypothesis() {
    return notifications.ticketing.investigation.openIncident(NotificationsFixture.ALERTS, null);
  }

  /** The search of PI-1001's alert types, services and component: a match with 1.00. */
  public SimilarityResultView match(UUID incident) {
    PastIncidentView past = knowledge.pastIncident(tenant, "PI-1001").orElseThrow();
    return searched(
        new SearchRequest(
            tenant,
            incident,
            null,
            past.alertTypes(),
            past.affectedServiceKeys(),
            past.hypothesisComponentKey()));
  }

  /** A search no past incident matches. */
  public SimilarityResultView noMatch(UUID incident) {
    return searched(
        new SearchRequest(tenant, incident, null, List.of("cpu_high"), List.of(), null));
  }

  private SimilarityResultView searched(SearchRequest request) {
    return ((SearchResult.Searched) knowledge.search(request)).result();
  }

  /** An evidence entry of the kind as its producer; returns its id. */
  public UUID evidence(UUID incident, EvidenceKind kind, String summary, Instant collectedAt) {
    Actor actor =
        switch (kind) {
          case DIAGNOSTIC_RESULT -> Actor.module("vigil-diagnostics");
          case AI_INVESTIGATION -> INVESTIGATION;
          case SIMILARITY_MATCH -> INVESTIGATION;
          default -> throw new IllegalArgumentException(kind.key());
        };
    DataCategory category =
        switch (kind) {
          case DIAGNOSTIC_RESULT -> DataCategory.DIAGNOSTIC_OUTPUT;
          case AI_INVESTIGATION -> DataCategory.INCIDENT_RECORD;
          default -> DataCategory.INCIDENT_HISTORY;
        };
    return incidents.addEvidence(
        tenant,
        actor,
        incident,
        kind,
        Ids.newId(),
        "test source",
        collectedAt,
        summary,
        "{}",
        category);
  }

  // Step calls.

  /** An Approval step call of the run with a new step execution. */
  public StepCall approval(UUID run, UUID incident, String parameters, List<UUID> earlier) {
    return approval(run, incident, Ids.newId(), parameters, earlier, "start");
  }

  public StepCall approval(
      UUID run,
      UUID incident,
      UUID stepExecution,
      String parameters,
      List<UUID> earlier,
      String wake) {
    return new StepCall(
        tenant,
        run,
        incident,
        stepExecution,
        new Key("s-approve"),
        StepCall.StepType.APPROVAL,
        parameters,
        1,
        null,
        new StepCall.WakeReason(wake),
        null,
        earlier);
  }

  /** An Action step call of the run with a new step execution. */
  public StepCall action(UUID run, UUID incident) {
    return action(run, incident, Ids.newId());
  }

  public StepCall action(UUID run, UUID incident, UUID stepExecution) {
    return new StepCall(
        tenant,
        run,
        incident,
        stepExecution,
        new Key("s-action"),
        StepCall.StepType.ACTION,
        "{}",
        1,
        null,
        new StepCall.WakeReason("start"),
        null,
        null);
  }

  public StepCancel cancel(StepCall call) {
    return new StepCancel(
        tenant,
        call.runId(),
        call.incidentId(),
        call.stepExecutionId(),
        call.stepId(),
        call.stepType());
  }

  // Rows.

  /**
   * An execution of the demo action for the incident in the state, inserted as {@code postgres};
   * {@code executingAt} {@code null} when it never reached {@code executing}. Returns its id.
   */
  public UUID insertExecution(
      UUID run, UUID incident, UUID proposal, String state, long attempt, Instant executingAt) {
    return insertExecution(
        run, incident, Ids.newId(), proposal, List.of(), state, attempt, 0, executingAt);
  }

  /** An execution with every column the restart cases need, inserted as {@code postgres}. */
  public UUID insertExecution(
      UUID run,
      UUID incident,
      UUID stepExecution,
      UUID proposal,
      List<UUID> requests,
      String state,
      long attempt,
      long recheckPass,
      Instant executingAt) {
    UUID id = Ids.newId();
    Timestamp now = Timestamp.from(Instant.now());
    postgres()
        .sql(
            "INSERT INTO actions.action_execution (id, tenant_id, created_at, updated_at,"
                + " incident_id, run_id, step_execution_id, proposal_id, action_key,"
                + " approval_request_ids, attempt_number, state, recheck_pass, recheck,"
                + " operation_run_id, reconciliation_run_ids, started_at, executing_at,"
                + " finished_at) VALUES (:id, :t, :now, :now, :incident, :run, :step, :proposal,"
                + " 'purge-archived-txlogs', CAST(:requests AS jsonb), :attempt, :state, :pass,"
                + " NULL, NULL, CAST('[]' AS jsonb), :now, :executingAt, NULL)")
        .param("id", id)
        .param("t", tenant.value())
        .param("now", now)
        .param("incident", incident)
        .param("run", run)
        .param("step", stepExecution)
        .param("proposal", proposal == null ? Ids.newId() : proposal)
        .param("requests", JSON.writeValueAsString(requests.stream().map(UUID::toString).toList()))
        .param("attempt", attempt)
        .param("state", state)
        .param("pass", recheckPass)
        .param("executingAt", executingAt == null ? null : Timestamp.from(executingAt))
        .update();
    return id;
  }

  /** The rows of an actions table of the tenant, by creation. */
  public List<Map<String, Object>> rows(String table) {
    return postgres()
        .sql("SELECT * FROM actions." + table + " WHERE tenant_id = :t ORDER BY created_at, id")
        .param("t", tenant.value())
        .query()
        .listOfRows();
  }

  /** The rows of an actions table of the tenant with this run. */
  public List<Map<String, Object>> rowsOfRun(String table, UUID run) {
    return postgres()
        .sql(
            "SELECT * FROM actions."
                + table
                + " WHERE tenant_id = :t AND run_id = :r ORDER BY created_at, id")
        .param("t", tenant.value())
        .param("r", run)
        .query()
        .listOfRows();
  }

  /** The audit events of the incident with these types, oldest first. */
  public List<AuditEventView> audits(UUID incident, AuditEventType... types) {
    List<AuditEventView> events =
        new ArrayList<>(
            app.context()
                .getBean(AuditService.class)
                .query(
                    tenant,
                    new AuditQuery(
                        null, null, null, Set.of(types), Set.of(incident), null, null, 50, null))
                .items());
    java.util.Collections.reverse(events);
    return events;
  }

  /** The incident's evidence entries of the kind, in order. */
  public List<EvidenceView> evidenceOf(UUID incident, EvidenceKind kind) {
    return incidents.evidence(tenant, incident).stream().filter(e -> e.kind() == kind).toList();
  }

  public UUID userId(String username) {
    return notifications.userId(username);
  }

  public JdbcClient postgres() {
    return app.database().postgres();
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
}
