package vigil.setup;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import vigil.knowledge.api.KnowledgeImport;
import vigil.knowledge.api.KnowledgePart;
import vigil.knowledge.api.KnowledgePart.PastIncidentPart;
import vigil.knowledge.api.KnowledgePart.SettingsPart;
import vigil.setup.SeedShapes.Row;
import vigil.shared.TenantId;

/**
 * Seed part 8, knowledge, imported through {@link KnowledgeImport} (DM-R-56 (8), DD-F-07 to
 * DD-F-09, DD-R-09).
 */
@Component
class KnowledgeAdapter implements SeedPartImporter {

  private final KnowledgeImport knowledgeImport;

  KnowledgeAdapter(KnowledgeImport knowledgeImport) {
    this.knowledgeImport = knowledgeImport;
  }

  @Override
  public int part() {
    return 8;
  }

  @Override
  public PartResult importPart(
      TenantId tenantId,
      SeedBundle bundle,
      Map<String, JsonNode> files,
      Instant importStartedAt,
      Map<String, UUID> handles) {
    SeedShapes shapes = new SeedShapes();
    Row settings =
        shapes.mapping(
            files.get(SeedParts.SETTINGS),
            "knowledge_settings",
            List.of(
                "min_similarity", "runbook_limit", "embedding_provider_key", "embedding_state"));
    RunbookFile.Read runbooks = RunbookFile.read(bundle.texts().get(SeedParts.RUNBOOKS));
    PastIncidentFile.Read pastIncidents =
        PastIncidentFile.read(bundle.texts().get(SeedParts.PAST_INCIDENTS));
    runbooks.problems().forEach(shapes::add);
    pastIncidents.problems().forEach(shapes::add);
    shapes.refuseIfAny(SeedParts.part(part()).name());
    knowledgeImport.importPart(
        tenantId,
        new KnowledgePart(
            runbooks.runbooks(),
            pastIncidents.rows().stream().map(PastIncidentPart::new).toList(),
            new SettingsPart(
                settings.get("min_similarity"),
                settings.get("runbook_limit"),
                settings.get("embedding_provider_key"),
                settings.get("embedding_state"))),
        importStartedAt,
        handles);
    Map<String, Integer> counts = new LinkedHashMap<>();
    counts.put("runbooks", runbooks.runbooks().size());
    counts.put("past_incidents", pastIncidents.rows().size());
    counts.put("knowledge_settings", 1);
    return new PartResult(counts, Map.of());
  }
}
