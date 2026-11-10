package vigil.investigation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import vigil.aigateway.api.AnswerFinish;
import vigil.investigation.api.RunbookRef;
import vigil.investigation.api.Verdict;

/**
 * The output checks of SL-T-1 (PM-M17-03, AR-G-11, AR-G-13, DM-E68-R3) with the reasons of DC-TX-11
 * (P-20 spec Section 6.11). Pure: no bean, no database.
 */
final class OutputChecks {

  static final String NO_LIKELY_CAUSE = "no likely cause";
  static final String NO_VERDICT = "does not say whether it confirms or disputes the hypothesis";
  static final String NO_UNCERTAINTY = "no uncertainty stated";
  static final String NO_EVIDENCE = "no evidence reference";
  static final String UNKNOWN_EVIDENCE = "refers to evidence that is not on the incident";
  static final String NO_RUNBOOK = "no runbook cited";
  static final String UNKNOWN_RUNBOOK = "cites a runbook that was not supplied";
  static final int TEXT_MAX = 2000;
  private static final Set<String> PROPERTIES =
      Set.of(
          "likely_cause", "hypothesis_verdict", "evidence_labels", "uncertainty", "runbooks_cited");

  /** The reasons (empty for an accepted output) and the values read. */
  record Checked(
      List<String> reasons,
      String likelyCause,
      Verdict verdict,
      List<UUID> evidenceEntryIds,
      String uncertainty,
      List<RunbookRef> runbookRefs) {

    Checked {
      reasons = List.copyOf(reasons);
      evidenceEntryIds = List.copyOf(evidenceEntryIds);
      runbookRefs = List.copyOf(runbookRefs);
    }
  }

  private OutputChecks() {}

  static Checked slT1(
      String outputText,
      AnswerFinish finish,
      Map<String, UUID> labels,
      List<RunbookRef> supplied,
      boolean hasHypothesis) {
    JsonNode answer = readable(outputText, finish);
    if (answer == null) {
      return new Checked(List.of(NO_LIKELY_CAUSE), null, null, List.of(), null, List.of());
    }
    List<String> reasons = new ArrayList<>();
    String likelyCause = text(answer.get("likely_cause"));
    if (likelyCause == null) {
      reasons.add(NO_LIKELY_CAUSE);
    }
    Verdict verdict = null;
    if (hasHypothesis) {
      String value = string(answer.get("hypothesis_verdict"));
      verdict =
          Verdict.fromKey(value)
              .filter(v -> v == Verdict.CONFIRMS || v == Verdict.DISPUTES)
              .orElse(null);
      if (verdict == null) {
        reasons.add(NO_VERDICT);
      }
    }
    String uncertainty = text(answer.get("uncertainty"));
    if (uncertainty == null) {
      reasons.add(NO_UNCERTAINTY);
    }
    List<String> evidenceLabels = strings(answer.get("evidence_labels"));
    List<UUID> ids = new ArrayList<>();
    if (evidenceLabels == null || evidenceLabels.isEmpty()) {
      reasons.add(NO_EVIDENCE);
    } else {
      boolean unknown = false;
      for (String label : new LinkedHashSet<>(evidenceLabels)) {
        UUID id = labels.get(label);
        if (id == null) {
          unknown = true;
        } else if (!ids.contains(id)) {
          ids.add(id);
        }
      }
      if (unknown) {
        reasons.add(UNKNOWN_EVIDENCE);
      }
    }
    List<String> cited = strings(answer.get("runbooks_cited"));
    List<RunbookRef> refs = new ArrayList<>();
    if (cited == null || cited.isEmpty()) {
      reasons.add(NO_RUNBOOK);
    } else {
      boolean unknown = false;
      for (String key : new LinkedHashSet<>(cited)) {
        RunbookRef ref =
            supplied.stream().filter(r -> r.runbookKey().equals(key)).findFirst().orElse(null);
        if (ref == null) {
          unknown = true;
        } else if (!refs.contains(ref)) {
          refs.add(ref);
        }
      }
      if (unknown) {
        reasons.add(UNKNOWN_RUNBOOK);
      }
    }
    return new Checked(
        reasons,
        likelyCause == null ? null : cut(likelyCause, TEXT_MAX),
        verdict,
        ids,
        uncertainty == null ? null : cut(uncertainty, TEXT_MAX),
        refs);
  }

  /** The answer's JSON object, or {@code null} when nothing can be read. */
  private static JsonNode readable(String outputText, AnswerFinish finish) {
    if (finish != AnswerFinish.COMPLETE || outputText == null) {
      return null;
    }
    JsonNode tree;
    try {
      tree = InvestigationRecords.JSON.readTree(outputText);
    } catch (JacksonException e) {
      return null;
    }
    if (tree == null || !tree.isObject()) {
      return null;
    }
    for (String name : tree.propertyNames()) {
      if (!PROPERTIES.contains(name)) {
        return null;
      }
    }
    return tree;
  }

  /** A string value without U+0000; else {@code null}. */
  static String string(JsonNode node) {
    if (node == null || !node.isString() || node.stringValue().indexOf('\u0000') >= 0) {
      return null;
    }
    return node.stringValue();
  }

  /** A string value with other than white space; else {@code null}. */
  private static String text(JsonNode node) {
    String value = string(node);
    return value == null || value.isBlank() ? null : value;
  }

  /** An array of strings without U+0000; else {@code null}. */
  private static List<String> strings(JsonNode node) {
    if (node == null || !node.isArray()) {
      return null;
    }
    List<String> values = new ArrayList<>();
    for (JsonNode element : node) {
      String value = string(element);
      if (value == null) {
        return null;
      }
      values.add(value);
    }
    return values;
  }

  static String cut(String text, int max) {
    if (text.codePointCount(0, text.length()) <= max) {
      return text;
    }
    return text.substring(0, text.offsetByCodePoints(0, max));
  }
}
