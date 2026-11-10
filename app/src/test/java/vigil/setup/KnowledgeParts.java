package vigil.setup;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import vigil.knowledge.api.KnowledgePart;
import vigil.knowledge.api.KnowledgePart.PastIncidentPart;
import vigil.knowledge.api.KnowledgePart.RunbookPart;
import vigil.knowledge.api.KnowledgePart.SettingsPart;

/** Seed part 8 as the adapter reads it from {@code docs/31-demo-data/}, or changed. */
public final class KnowledgeParts {

  private static final Path DATA = Path.of("../docs/31-demo-data");

  private KnowledgeParts() {}

  /** The demo files read with the two readers and {@code knowledge_settings} of DD-F-07. */
  public static KnowledgePart demoPart() {
    RunbookFile.Read runbooks = RunbookFile.read(read("08-runbooks.md"));
    PastIncidentFile.Read past = PastIncidentFile.read(read("09-past-incidents.csv"));
    if (!runbooks.problems().isEmpty() || !past.problems().isEmpty()) {
      throw new IllegalStateException("the demo files have problems");
    }
    JsonNode settings = SeedYaml.read(read("07-settings.yaml")).get("knowledge_settings");
    return new KnowledgePart(
        runbooks.runbooks(),
        past.rows().stream().map(PastIncidentPart::new).toList(),
        new SettingsPart(
            SeedShapes.text(settings.get("min_similarity")),
            SeedShapes.text(settings.get("runbook_limit")),
            SeedShapes.text(settings.get("embedding_provider_key")),
            SeedShapes.text(settings.get("embedding_state"))));
  }

  public static KnowledgePart withSettings(KnowledgePart part, SettingsPart settings) {
    return new KnowledgePart(part.runbooks(), part.pastIncidents(), settings);
  }

  public static KnowledgePart withRunbooks(KnowledgePart part, List<RunbookPart> runbooks) {
    return new KnowledgePart(runbooks, part.pastIncidents(), part.settings());
  }

  public static KnowledgePart withPastIncidents(
      KnowledgePart part, List<PastIncidentPart> pastIncidents) {
    return new KnowledgePart(part.runbooks(), pastIncidents, part.settings());
  }

  /** A past-incident row with its fields changed. */
  public static PastIncidentPart withFields(PastIncidentPart row, Map<String, String> changes) {
    Map<String, String> fields = new java.util.LinkedHashMap<>(row.fields());
    fields.putAll(changes);
    return new PastIncidentPart(fields);
  }

  private static String read(String file) {
    try {
      return Files.readString(DATA.resolve(file));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
