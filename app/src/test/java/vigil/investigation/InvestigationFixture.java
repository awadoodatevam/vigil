package vigil.investigation;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.springframework.jdbc.core.simple.JdbcClient;
import vigil.aigateway.api.AiGatewayPart;
import vigil.incidents.api.AffectedServiceCopy;
import vigil.incidents.api.AlertCopy;
import vigil.incidents.api.DataCategory;
import vigil.incidents.api.EvidenceKind;
import vigil.incidents.api.FinalReason;
import vigil.incidents.api.GroupingReason;
import vigil.incidents.api.HypothesisCopy;
import vigil.incidents.api.IdentityPairCopy;
import vigil.incidents.api.IncidentImport;
import vigil.incidents.api.IncidentService;
import vigil.incidents.api.IncidentSettingsPart;
import vigil.incidents.api.MembershipView;
import vigil.knowledge.KnowledgeFixture;
import vigil.knowledge.api.KnowledgePart;
import vigil.servicemap.api.AffectedService;
import vigil.servicemap.api.ComponentView;
import vigil.servicemap.api.ServiceMapService;
import vigil.servicemap.api.ServiceView;
import vigil.shared.Actor;
import vigil.shared.Ids;
import vigil.shared.Key;
import vigil.shared.StepCall;
import vigil.shared.TenantId;
import vigil.testsupport.VigilTestApp;

/**
 * The investigation fixture of P-20 spec Section 7: the tenant of {@link KnowledgeFixture} with the
 * incident settings (part 9); helpers that open incidents, add diagnostic entries, build AI task
 * step calls, merge and close incidents.
 */
public final class InvestigationFixture {

  private static final Actor CORRELATION = Actor.module("vigil-correlation");
  private static final Actor DIAGNOSTICS = Actor.module("vigil-diagnostics");

  public final VigilTestApp app;
  public final TenantId tenant;
  public final KnowledgeFixture knowledge;
  private final IncidentService incidents;
  private final ServiceMapService serviceMap;

  private InvestigationFixture(KnowledgeFixture knowledge) {
    this.app = knowledge.app;
    this.tenant = knowledge.tenant;
    this.knowledge = knowledge;
    this.incidents = app.context().getBean(IncidentService.class);
    this.serviceMap = app.context().getBean(ServiceMapService.class);
  }

  public static InvestigationFixture create(
      VigilTestApp app,
      String name,
      String host,
      Map<String, String> addresses,
      UnaryOperator<AiGatewayPart> part7,
      KnowledgePart part8) {
    KnowledgeFixture knowledge = KnowledgeFixture.create(app, name, host, addresses, part7, part8);
    app.context()
        .getBean(IncidentImport.class)
        .importPart(
            knowledge.tenant, new IncidentSettingsPart("300", "60"), Instant.now(), Map.of());
    return new InvestigationFixture(knowledge);
  }

  private static AlertCopy alert(String[] alert) {
    String component = alert[2];
    return new AlertCopy(
        Ids.newId(),
        "zabbix",
        "k-" + UUID.randomUUID(),
        alert[0],
        alert[1],
        alert[3],
        component,
        component == null ? null : new IdentityPairCopy("component", component),
        Instant.now());
  }

  private UUID forming(List<String[]> alerts) {
    UUID incident =
        incidents.create(
            tenant,
            CORRELATION,
            Ids.newId(),
            alert(alerts.getFirst()),
            new GroupingReason("created_incident", null, null, null, null));
    for (String[] more : alerts.subList(1, alerts.size())) {
      incidents.addAlert(
          tenant,
          CORRELATION,
          incident,
          Ids.newId(),
          alert(more),
          new GroupingReason("joined", Ids.newId(), "same_component", List.of("c"), 10L));
    }
    return incident;
  }

  /**
   * An incident with the alerts ({type key, type name, component key or null, title}), the
   * hypothesis with its copy from the service map and the affected services over the alerts'
   * components, opened.
   */
  public UUID openIncident(List<String[]> alerts, String hypothesisComponentKey) {
    UUID incident = forming(alerts);
    Set<String> components = new LinkedHashSet<>();
    alerts.stream().map(a -> a[2]).filter(c -> c != null).forEach(components::add);
    List<AffectedServiceCopy> services =
        serviceMap.affectedServices(tenant, components).stream().map(this::copy).toList();
    HypothesisCopy hypothesis = null;
    if (hypothesisComponentKey != null) {
      ComponentView view = serviceMap.component(tenant, hypothesisComponentKey).orElseThrow();
      hypothesis =
          new HypothesisCopy(
              1,
              hypothesisComponentKey,
              view.name(),
              view.kind().key(),
              view.criticality() == null ? null : view.criticality().key());
    }
    incidents.setHypothesisAndImpact(
        tenant,
        CORRELATION,
        incident,
        hypothesis,
        hypothesis == null ? null : Ids.newId(),
        services);
    Map<UUID, FinalReason> reasons = new LinkedHashMap<>();
    for (MembershipView member : incidents.members(tenant, incident)) {
      reasons.put(member.alertId(), new FinalReason(null, FinalReason.ONLY_ALERT, null, null));
    }
    incidents.open(tenant, CORRELATION, incident, reasons);
    return incident;
  }

  private AffectedServiceCopy copy(AffectedService service) {
    ServiceView view = serviceMap.service(tenant, service.serviceKey()).orElseThrow();
    return new AffectedServiceCopy(
        service.serviceKey(),
        service.name(),
        view.criticality() == null ? null : view.criticality().key(),
        service.viaComponentKeys());
  }

  /** A {@code diagnostic_result} entry as vigil-diagnostics; returns its id. */
  public UUID addDiagnostic(UUID incident, String diagnosticName, String summary) {
    return incidents.addEvidence(
        tenant,
        DIAGNOSTICS,
        incident,
        EvidenceKind.DIAGNOSTIC_RESULT,
        Ids.newId(),
        "lab-db-01 via runner, operation disk_usage",
        Instant.now(),
        summary,
        InvestigationRecords.JSON.writeValueAsString(
            InvestigationRecords.JSON.createObjectNode().put("diagnostic_name", diagnosticName)),
        DataCategory.DIAGNOSTIC_OUTPUT);
  }

  /** A call of an {@code ai_task} step for the task (step ids by task). */
  public StepCall call(UUID run, UUID incident, UUID stepExecution, String task) {
    String stepId =
        switch (task) {
          case "SL-T-1" -> "s-investigate";
          case "SL-T-2" -> "s-summarise";
          default -> "s-similar";
        };
    return new StepCall(
        tenant,
        run,
        incident,
        stepExecution,
        new Key(stepId),
        StepCall.StepType.AI_TASK,
        "{\"task\": \"" + task + "\"}",
        1,
        null,
        new StepCall.WakeReason("start"),
        null,
        null);
  }

  /** Two forming incidents, the second merged into the first; returns the merged one. */
  public UUID mergedIncident() {
    String[] alert = {"disk_full", "Disk full", "srv-db-01", "Disk full on srv-db-01"};
    UUID survivor = forming(List.<String[]>of(alert));
    UUID absorbed = forming(List.<String[]>of(alert));
    incidents.merge(tenant, CORRELATION, survivor, List.of(absorbed));
    return absorbed;
  }

  /** Sets {@code state} {@code closed} and {@code closed_at} as {@code postgres}. */
  public void close(UUID incident) {
    postgres()
        .sql(
            "UPDATE incidents.incident SET state = 'closed', closed_at = now()"
                + " WHERE tenant_id = :t AND id = :i")
        .param("t", tenant.value())
        .param("i", incident)
        .update();
  }

  public JdbcClient postgres() {
    return app.database().postgres();
  }
}
