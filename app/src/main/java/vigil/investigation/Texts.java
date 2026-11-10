package vigil.investigation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import vigil.incidents.api.AffectedServiceCopy;
import vigil.incidents.api.IncidentView;
import vigil.incidents.api.MembershipView;
import vigil.investigation.api.RunbookRef;
import vigil.investigation.api.Verdict;

/**
 * The evidence, audit and template texts of vigil-investigation (DC-TX-03, DC-TX-10, DM-E69-R1).
 */
final class Texts {

  static final Comparator<String> CODE_POINTS =
      Comparator.comparing(text -> text.codePoints().toArray(), Arrays::compare);
  static final int SUMMARY_MAX = 2000;
  private static final Map<String, String> KIND_NAMES =
      Map.of(
          "claude", "Claude",
          "openai", "OpenAI",
          "local_model", "Local model",
          "local_embedding", "Local embedding model");

  private Texts() {}

  /** DC-TX-01 "Provider kind name" of a kind key; the key itself for another kind. */
  static String kindName(String kindKey) {
    return KIND_NAMES.getOrDefault(kindKey, kindKey);
  }

  /** The source system of an {@code ai_investigation} entry. */
  static String investigationSource(String kindName, String modelName) {
    return kindName + ", model " + modelName;
  }

  /** The summary of an {@code ai_investigation} entry (DC-TX-03), keys in cited order. */
  static String investigationSummary(
      String kindName, Verdict verdict, String hypothesisName, List<RunbookRef> refs) {
    String cites =
        "cites " + String.join(", ", refs.stream().map(RunbookRef::runbookKey).toList()) + ".";
    if (verdict == null) {
      return "AI investigation by " + kindName + "; " + cites;
    }
    return "AI investigation by "
        + kindName
        + ": "
        + verdict.key()
        + " the hypothesis "
        + hypothesisName
        + "; "
        + cites;
  }

  /** The source system of a {@code similarity_match} entry. */
  static String similaritySource(String kindName) {
    return "Incident history, " + kindName;
  }

  /** The summary of a {@code similarity_match} entry (DC-TX-03). */
  static String similaritySummary(
      String pastIncidentKey,
      String title,
      String recordLabel,
      BigDecimal score,
      long successCount) {
    return "Similar past incident "
        + pastIncidentKey
        + " '"
        + title
        + "' ("
        + recordLabel
        + "), similarity "
        + score.setScale(2, RoundingMode.HALF_UP).toPlainString()
        + "; fix succeeded "
        + successCount
        + (successCount == 1 ? " time." : " times.");
  }

  /** The summary of {@code ai_result_accepted} (DC-TX-10). */
  static String auditAccepted(String kindName) {
    return "Incident investigation by " + kindName + " accepted.";
  }

  /** The summary of {@code ai_result_rejected} (DC-TX-10). */
  static String auditRejected(String kindName, List<String> reasons) {
    return "Incident investigation by "
        + kindName
        + " rejected: "
        + String.join("; ", reasons)
        + ".";
  }

  /** The template summary (DM-E69-R1), cut to 2,000. */
  static String template(IncidentView incident, List<MembershipView> members, int evidenceCount) {
    Map<String, String> names = new LinkedHashMap<>();
    for (MembershipView member : members) {
      names.putIfAbsent(member.alert().alertType(), member.alert().alertTypeName());
    }
    List<String> typeNames = new ArrayList<>();
    for (String type : incident.alertTypes()) {
      typeNames.add(names.getOrDefault(type, type));
    }
    List<String> services =
        incident.affectedServices().stream().map(AffectedServiceCopy::name).toList();
    String text =
        incident.title()
            + ". "
            + incident.alertCount()
            + " alerts ("
            + String.join(", ", typeNames.stream().sorted(CODE_POINTS).toList())
            + "). Customer impact: "
            + (services.isEmpty() ? "none identified" : String.join(", ", services))
            + ". Hypothesis: "
            + (incident.hypothesis() == null ? "none" : incident.hypothesis().name())
            + ". Evidence: "
            + evidenceCount
            + " items.";
    return OutputChecks.cut(text, SUMMARY_MAX);
  }
}
