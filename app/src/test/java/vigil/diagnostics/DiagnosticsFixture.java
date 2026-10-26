package vigil.diagnostics;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import vigil.diagnostics.api.DiagnosticResultView;
import vigil.diagnostics.api.DiagnosticsImport;
import vigil.diagnostics.api.DiagnosticsPart;
import vigil.diagnostics.api.DiagnosticsPart.DefinitionPart;
import vigil.diagnostics.api.DiagnosticsService;
import vigil.incidents.api.AlertCopy;
import vigil.incidents.api.FinalReason;
import vigil.incidents.api.GroupingReason;
import vigil.incidents.api.HypothesisCopy;
import vigil.incidents.api.IdentityPairCopy;
import vigil.incidents.api.IncidentImport;
import vigil.incidents.api.IncidentService;
import vigil.incidents.api.IncidentSettingsPart;
import vigil.incidents.api.MembershipView;
import vigil.runner.RunnerFixture;
import vigil.runner.api.OperationRunView;
import vigil.runner.api.RunnerPart;
import vigil.runner.api.RunnerService;
import vigil.servicemap.api.ComponentView;
import vigil.servicemap.api.ServiceMapService;
import vigil.shared.Actor;
import vigil.shared.Ids;
import vigil.shared.Key;
import vigil.shared.StepCall;
import vigil.shared.TenantId;
import vigil.testsupport.TestTenants;
import vigil.testsupport.VigilTestApp;

/**
 * The diagnostics fixture of P-18 spec Section 7: an active tenant with the incident settings, the
 * lab target's connectors and runner parts, the demo service map and the demo diagnostic
 * definitions; helpers that open incidents, build step calls and read results and runs.
 */
public final class DiagnosticsFixture {

  private static final Actor CORRELATION = Actor.module("vigil-correlation");
  private static final Path SERVICE_MAP = Path.of("../docs/31-demo-data/02-service-map.yaml");

  public final VigilTestApp app;
  public final TenantId tenant;
  private final IncidentService incidents;

  private DiagnosticsFixture(VigilTestApp app, TenantId tenant) {
    this.app = app;
    this.tenant = tenant;
    this.incidents = app.context().getBean(IncidentService.class);
  }

  public static DiagnosticsFixture create(
      VigilTestApp app,
      String name,
      String host,
      String address,
      int port,
      String fingerprint,
      RunnerPart runnerPart) {
    TenantId tenant = new TestTenants(app).tenant(name).host(host).create().id();
    app.context()
        .getBean(IncidentImport.class)
        .importPart(tenant, new IncidentSettingsPart("300", "60"), Instant.now(), Map.of());
    RunnerFixture.importInto(app, tenant, address, port, fingerprint, runnerPart);
    try {
      app.context()
          .getBean(ServiceMapService.class)
          .importFile(
              tenant,
              "02-service-map.yaml",
              Files.readAllBytes(SERVICE_MAP),
              Actor.module("vigil-setup"));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    app.context()
        .getBean(DiagnosticsImport.class)
        .importPart(tenant, demoPart(), Instant.now(), Map.of());
    return new DiagnosticsFixture(app, tenant);
  }

  /** The section {@code diagnostic_definitions} as the adapter reads it. */
  public static DiagnosticsPart demoPart() {
    List<DefinitionPart> definitions = new ArrayList<>();
    for (JsonNode d : RunnerFixture.operationsFile().get("diagnostic_definitions")) {
      definitions.add(
          new DefinitionPart(
              d.get("diagnostic_key").stringValue(),
              d.get("name").stringValue(),
              d.get("operation_key").stringValue(),
              d.get("parameter_values"),
              d.get("summary_template").stringValue()));
    }
    return new DiagnosticsPart(definitions);
  }

  /**
   * An incident created with one zabbix alert on the component, given the hypothesis (when not
   * {@code null}) and opened, as {@code IncidentFixture} builds them.
   */
  public UUID openIncident(String hypothesisComponentKey) {
    String component = hypothesisComponentKey;
    AlertCopy alert =
        new AlertCopy(
            Ids.newId(),
            "zabbix",
            "k-" + UUID.randomUUID(),
            "disk_full",
            "Disk full",
            "Disk full on " + (component == null ? "an unknown component" : component),
            component,
            component == null ? null : new IdentityPairCopy("component", component),
            Instant.now());
    UUID incident =
        incidents.create(
            tenant,
            CORRELATION,
            Ids.newId(),
            alert,
            new GroupingReason("created_incident", null, null, null, null));
    if (hypothesisComponentKey != null) {
      ComponentView view =
          app.context()
              .getBean(ServiceMapService.class)
              .component(tenant, hypothesisComponentKey)
              .orElseThrow();
      HypothesisCopy hypothesis =
          new HypothesisCopy(
              1,
              hypothesisComponentKey,
              view.name(),
              view.kind().key(),
              view.criticality() == null ? null : view.criticality().key());
      incidents.setHypothesisAndImpact(
          tenant, CORRELATION, incident, hypothesis, Ids.newId(), List.of());
    }
    Map<UUID, FinalReason> reasons = new LinkedHashMap<>();
    for (MembershipView member : incidents.members(tenant, incident)) {
      reasons.put(member.alertId(), new FinalReason(null, FinalReason.ONLY_ALERT, null, null));
    }
    incidents.open(tenant, CORRELATION, incident, reasons);
    return incident;
  }

  /** A call of the step {@code s-diagnose} with the given diagnostic keys. */
  public StepCall call(
      UUID run,
      UUID incident,
      UUID stepExecution,
      long evaluationCount,
      String wakeReason,
      String... diagnosticKeys) {
    ObjectNode parameters = RunnerFixture.JSON.createObjectNode();
    parameters.putArray("diagnostics").addAll(diagnosticKeysNode(diagnosticKeys));
    parameters.put("target", "hypothesis_component");
    return new StepCall(
        tenant,
        run,
        incident,
        stepExecution,
        new Key("s-diagnose"),
        StepCall.StepType.DIAGNOSTIC,
        RunnerFixture.JSON.writeValueAsString(parameters),
        evaluationCount,
        null,
        new StepCall.WakeReason(wakeReason),
        null,
        null);
  }

  private static List<JsonNode> diagnosticKeysNode(String... keys) {
    List<JsonNode> nodes = new ArrayList<>();
    for (String key : keys) {
      nodes.add(RunnerFixture.JSON.getNodeFactory().stringNode(key));
    }
    return nodes;
  }

  /** The results of the incident of this step execution, in their order. */
  public List<DiagnosticResultView> results(UUID incident, UUID stepExecution) {
    return app.context().getBean(DiagnosticsService.class).results(tenant, incident).stream()
        .filter(result -> result.stepExecutionId().equals(stepExecution))
        .toList();
  }

  /** The runs whose request id starts with {@code {stepExecution}:}, by request id. */
  public List<OperationRunView> runs(UUID stepExecution) {
    RunnerService runner = app.context().getBean(RunnerService.class);
    return app
        .context()
        .getBean(JdbcClient.class)
        .sql(
            "SELECT id FROM runner.operation_run WHERE tenant_id = :t"
                + " AND starts_with(request_id, :prefix) ORDER BY request_id COLLATE \"C\"")
        .param("t", tenant.value())
        .param("prefix", stepExecution + ":")
        .query(UUID.class)
        .list()
        .stream()
        .map(id -> runner.operationRun(tenant, id).orElseThrow())
        .toList();
  }
}
