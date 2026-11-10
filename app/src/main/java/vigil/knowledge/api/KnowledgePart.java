package vigil.knowledge.api;

import java.util.List;
import java.util.Map;

/**
 * Seed part 8 as read from {@code 08-runbooks.md}, {@code 09-past-incidents.csv} and the section
 * {@code knowledge_settings} of {@code 07-settings.yaml}; values as text.
 */
public record KnowledgePart(
    List<RunbookPart> runbooks, List<PastIncidentPart> pastIncidents, SettingsPart settings) {

  /** One runbook of {@code 08-runbooks.md}. */
  public record RunbookPart(
      String runbookKey,
      String version,
      String title,
      List<String> applicableAlertTypes,
      List<String> applicableComponentKinds,
      String text) {}

  /** One row of {@code 09-past-incidents.csv}: the 14 columns of DD-F-09 by name, as text. */
  public record PastIncidentPart(Map<String, String> fields) {}

  /** The section {@code knowledge_settings}. */
  public record SettingsPart(
      String minSimilarity,
      String runbookLimit,
      String embeddingProviderKey,
      String embeddingState) {}
}
