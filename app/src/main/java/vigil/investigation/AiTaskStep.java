package vigil.investigation;

import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import vigil.incidents.api.AffectedServiceCopy;
import vigil.incidents.api.DataCategory;
import vigil.incidents.api.EvidenceKind;
import vigil.incidents.api.IncidentService;
import vigil.incidents.api.IncidentView;
import vigil.investigation.api.InvestigationStepHandler;
import vigil.investigation.api.Origin;
import vigil.investigation.api.Validation;
import vigil.knowledge.api.KnowledgeService;
import vigil.knowledge.api.PastIncidentView;
import vigil.knowledge.api.SearchOutcome;
import vigil.knowledge.api.SearchRequest;
import vigil.knowledge.api.SearchResult;
import vigil.knowledge.api.SimilarityResultView;
import vigil.shared.Completed;
import vigil.shared.Ids;
import vigil.shared.Key;
import vigil.shared.StepAnswer;
import vigil.shared.StepCall;
import vigil.shared.StepCancel;
import vigil.shared.TenantId;
import vigil.shared.TransactionGuard;

/**
 * The AI task step for SL-T-1, SL-T-2 and SL-T-3 (DM-S-04, PM-S-04; P-20 spec Section 6.11): {@code
 * start} and {@code evaluate} do the same and always complete with {@code done} or {@code
 * unavailable}; {@code cancel} does nothing.
 */
@Component
class AiTaskStep implements InvestigationStepHandler {

  static final String BAD_PARAMETERS = "ai task step parameters: task SL-T-1, SL-T-2 or SL-T-3";
  private static final Key DONE = new Key("done");
  private static final Key UNAVAILABLE = new Key("unavailable");

  private final Investigations investigations;
  private final IncidentService incidents;
  private final KnowledgeService knowledge;
  private final TransactionTemplate tx;

  AiTaskStep(
      Investigations investigations,
      IncidentService incidents,
      KnowledgeService knowledge,
      PlatformTransactionManager transactionManager) {
    this.investigations = investigations;
    this.incidents = incidents;
    this.knowledge = knowledge;
    this.tx = new TransactionTemplate(transactionManager);
  }

  @Override
  public StepAnswer start(StepCall call) {
    return step(call);
  }

  @Override
  public StepAnswer evaluate(StepCall call) {
    return step(call);
  }

  /** The step leaves nothing open (AR-R-21). */
  @Override
  public void cancel(StepCancel cancel) {
    // Nothing to cancel.
  }

  private StepAnswer step(StepCall call) {
    TransactionGuard.assertNoTransaction();
    String task = task(call);
    TenantId tenantId = call.tenantId();
    IncidentView incident =
        incidents
            .incident(tenantId, call.incidentId())
            .orElseThrow(
                () -> new IllegalStateException("incident " + call.incidentId() + " not found"));
    return switch (task) {
      case "SL-T-1" -> {
        Validation validation =
            investigations.investigate(
                tenantId,
                incident,
                Origin.WORKFLOW_STEP,
                call.runId(),
                Investigations.INVESTIGATION,
                Ids.newId());
        yield new Completed(validation == Validation.ACCEPTED ? DONE : UNAVAILABLE, null);
      }
      case "SL-T-2" -> {
        investigations.summarise(tenantId, incident, call.runId());
        yield new Completed(DONE, null);
      }
      default -> similar(call, incident);
    };
  }

  private StepAnswer similar(StepCall call, IncidentView incident) {
    TenantId tenantId = call.tenantId();
    SearchResult result =
        knowledge.search(
            new SearchRequest(
                tenantId,
                incident.id(),
                call.runId(),
                incident.alertTypes(),
                incident.affectedServices().stream().map(AffectedServiceCopy::serviceKey).toList(),
                incident.hypothesisComponentKey()));
    if (!(result instanceof SearchResult.Searched searched)) {
      return new Completed(UNAVAILABLE, null);
    }
    SimilarityResultView found = searched.result();
    if (found.outcome() == SearchOutcome.MATCH) {
      PastIncidentView past = searched.bestPastIncident();
      String kindName = investigations.kindName(tenantId, found.providerKey());
      ObjectNode content = InvestigationRecords.JSON.createObjectNode();
      content.put("similarity_search_result_id", found.id().toString());
      content.put("past_incident_key", past.pastIncidentKey());
      content.put("past_incident_title", past.title());
      content.put("record_label", past.recordLabel());
      content.put("score", found.score().setScale(2));
      content.put("success_count", found.successCount());
      content.put("fix_action_key", found.fixActionKey());
      content.put("provider_key", found.providerKey());
      tx.executeWithoutResult(
          status ->
              incidents.addEvidence(
                  tenantId,
                  Investigations.INVESTIGATION,
                  incident.id(),
                  EvidenceKind.SIMILARITY_MATCH,
                  found.id(),
                  Texts.similaritySource(kindName),
                  found.searchedAt(),
                  Texts.similaritySummary(
                      past.pastIncidentKey(),
                      past.title(),
                      past.recordLabel(),
                      found.score(),
                      found.successCount()),
                  InvestigationRecords.JSON.writeValueAsString(content),
                  DataCategory.INCIDENT_HISTORY));
    }
    return new Completed(DONE, null);
  }

  /** The task of the step's parameters. */
  private static String task(StepCall call) {
    if (call.stepType() != StepCall.StepType.AI_TASK) {
      throw new IllegalArgumentException(BAD_PARAMETERS);
    }
    JsonNode tree;
    try {
      tree = InvestigationRecords.JSON.readTree(call.parameters());
    } catch (JacksonException e) {
      throw new IllegalArgumentException(BAD_PARAMETERS, e);
    }
    JsonNode task = tree == null || !tree.isObject() ? null : tree.get("task");
    if (task == null
        || !task.isString()
        || !List.of("SL-T-1", "SL-T-2", "SL-T-3").contains(task.stringValue())) {
      throw new IllegalArgumentException(BAD_PARAMETERS);
    }
    return task.stringValue();
  }
}
