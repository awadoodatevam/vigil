package vigil.investigation;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import vigil.aigateway.api.AiPart;
import vigil.aigateway.api.DataCategory;
import vigil.incidents.api.AffectedServiceCopy;
import vigil.incidents.api.EvidenceKind;
import vigil.incidents.api.EvidenceView;
import vigil.incidents.api.HypothesisCopy;
import vigil.incidents.api.IncidentView;
import vigil.incidents.api.MembershipView;
import vigil.investigation.api.InvestigationResultView;
import vigil.investigation.api.RunbookRef;
import vigil.knowledge.api.RunbookView;

/**
 * The SL-T-1 and SL-T-2 requests (DM-R-43, AR-G-10 to AR-G-12; P-20 spec Section 6.10): parts with
 * their data categories, system texts and output schemas. Pure: no bean, no database.
 */
final class Requests {

  static final String SYSTEM_SL_T_1 =
      "You are an IT and service NOC investigator. Use only the information in the supplied"
          + " parts. Give the most likely cause of the incident in likely_cause. The part labelled"
          + " Hypothesis, when it is supplied, names the root-cause hypothesis: in"
          + " hypothesis_verdict, say whether the likely cause confirms or disputes that"
          + " hypothesis, and answer not_applicable only when no part labelled Hypothesis is"
          + " supplied. The evidence entries are the parts labelled E1, E2 and so on; member"
          + " alerts and runbooks are not evidence entries. In evidence_labels, list the labels of"
          + " the evidence entries the likely cause rests on, and no other label. In uncertainty,"
          + " state what remains uncertain. In runbooks_cited, list the key of every supplied"
          + " runbook whose steps address the likely cause, and no other key. Do not decide"
          + " severity, owner or any action. Answer only with the JSON object.";
  static final String SYSTEM_SL_T_2 =
      "Write a summary of at most 600 characters for NOC staff in summary, in plain sentences,"
          + " using only the supplied parts. Answer only with the JSON object.";
  static final JsonNode SCHEMA_SL_T_1 =
      InvestigationRecords.JSON.readTree(
          "{\"type\": \"object\", \"properties\": {\"likely_cause\": {\"type\": \"string\"},"
              + " \"hypothesis_verdict\": {\"type\": \"string\", \"enum\": [\"confirms\","
              + " \"disputes\", \"not_applicable\"]}, \"evidence_labels\": {\"type\": \"array\","
              + " \"items\": {\"type\": \"string\"}}, \"uncertainty\": {\"type\": \"string\"},"
              + " \"runbooks_cited\": {\"type\": \"array\", \"items\": {\"type\": \"string\"}}},"
              + " \"required\": [\"likely_cause\", \"hypothesis_verdict\", \"evidence_labels\","
              + " \"uncertainty\", \"runbooks_cited\"], \"additionalProperties\": false}");
  static final JsonNode SCHEMA_SL_T_2 =
      InvestigationRecords.JSON.readTree(
          "{\"type\": \"object\", \"properties\": {\"summary\": {\"type\": \"string\"}},"
              + " \"required\": [\"summary\"], \"additionalProperties\": false}");
  static final String NAME_SL_T_1 = "sl_t_1";
  static final String NAME_SL_T_2 = "sl_t_2";
  static final int MAX_UNITS_SL_T_1 = 1500;
  static final int MAX_UNITS_SL_T_2 = 600;

  private static final DateTimeFormatter TIME = DateTimeFormatter.ISO_INSTANT;

  /** The SL-T-1 parts, the labels {@code E1} … of the evidence entries, the supplied runbooks. */
  record SlT1(List<AiPart> parts, Map<String, UUID> labels, List<RunbookRef> supplied) {

    SlT1 {
      parts = List.copyOf(parts);
      labels = Collections.unmodifiableMap(new LinkedHashMap<>(labels));
      supplied = List.copyOf(supplied);
    }
  }

  private Requests() {}

  static SlT1 slT1(
      IncidentView incident,
      List<MembershipView> members,
      List<EvidenceView> evidence,
      Collection<String> dependents,
      List<RunbookView> runbooks) {
    List<AiPart> parts = new ArrayList<>();
    List<String> lines = new ArrayList<>();
    for (MembershipView member : members) {
      var alert = member.alert();
      lines.add(
          "- "
              + alert.alertType()
              + " | "
              + (alert.componentKey() == null ? "unknown" : alert.componentKey())
              + " | "
              + alert.sourceKind()
              + " | "
              + alert.title()
              + " | fired "
              + time(alert.firedAt()));
    }
    parts.add(new AiPart("Member alerts", DataCategory.ALERT_DATA, String.join("\n", lines)));
    if (incident.hypothesis() != null) {
      parts.add(hypothesis(incident.hypothesis(), members, dependents));
    }
    parts.add(impact(incident));
    Map<String, UUID> labels = new LinkedHashMap<>();
    for (EvidenceView entry : evidence) {
      if (entry.kind() == EvidenceKind.AI_INVESTIGATION) {
        continue;
      }
      String label = "E" + (labels.size() + 1);
      labels.put(label, entry.id());
      parts.add(new AiPart(label, category(entry), evidenceText(entry)));
    }
    List<RunbookRef> supplied = new ArrayList<>();
    for (RunbookView runbook : runbooks) {
      supplied.add(new RunbookRef(runbook.runbookKey(), runbook.version()));
      parts.add(
          new AiPart(
              runbook.runbookKey(),
              DataCategory.RUNBOOK_TEXT,
              runbook.title() + "\n\n" + runbook.text()));
    }
    return new SlT1(parts, labels, supplied);
  }

  static List<AiPart> slT2(
      IncidentView incident,
      List<MembershipView> members,
      Collection<String> dependents,
      InvestigationResultView accepted,
      List<EvidenceView> evidence) {
    List<AiPart> parts = new ArrayList<>();
    parts.add(new AiPart("Title", DataCategory.INCIDENT_RECORD, incident.title()));
    if (accepted != null) {
      parts.add(
          new AiPart(
              "Investigation",
              DataCategory.INCIDENT_RECORD,
              "Likely cause: "
                  + accepted.likelyCause()
                  + "\nUncertainty: "
                  + accepted.uncertainty()));
    }
    List<String> types = incident.alertTypes().stream().sorted(Texts.CODE_POINTS).toList();
    parts.add(
        new AiPart(
            "Alerts",
            DataCategory.ALERT_DATA,
            incident.alertCount()
                + " alerts of "
                + types.size()
                + " types: "
                + String.join(", ", types)));
    parts.add(impact(incident));
    if (incident.hypothesis() != null) {
      parts.add(hypothesis(incident.hypothesis(), members, dependents));
    }
    int k = 0;
    for (EvidenceView entry : evidence) {
      k++;
      parts.add(new AiPart("E" + k, category(entry), entry.summary()));
    }
    return List.copyOf(parts);
  }

  private static AiPart hypothesis(
      HypothesisCopy hypothesis, List<MembershipView> members, Collection<String> dependents) {
    Set<String> dependent = new HashSet<>(dependents);
    long n =
        members.stream()
            .map(member -> member.alert().componentKey())
            .filter(key -> key != null && !key.equals(hypothesis.componentKey()))
            .distinct()
            .filter(dependent::contains)
            .count();
    return new AiPart(
        "Hypothesis",
        DataCategory.SERVICE_CONTEXT,
        "Root-cause hypothesis: "
            + hypothesis.name()
            + " ("
            + hypothesis.componentKey()
            + "), kind "
            + hypothesis.kind()
            + ", criticality "
            + (hypothesis.criticality() == null ? "not set" : hypothesis.criticality())
            + "; "
            + n
            + " other affected components depend on it");
  }

  private static AiPart impact(IncidentView incident) {
    List<String> lines = new ArrayList<>();
    for (AffectedServiceCopy service : incident.affectedServices()) {
      lines.add(
          service.name()
              + " ("
              + service.serviceKey()
              + "), criticality "
              + (service.criticality() == null ? "not set" : service.criticality())
              + ", through "
              + String.join(", ", service.viaComponentKeys()));
    }
    return new AiPart(
        "Customer impact",
        DataCategory.SERVICE_CONTEXT,
        lines.isEmpty() ? "none identified" : String.join("\n", lines));
  }

  private static String evidenceText(EvidenceView entry) {
    if (entry.kind() == EvidenceKind.DIAGNOSTIC_RESULT) {
      JsonNode content = InvestigationRecords.JSON.readTree(entry.contentJson());
      JsonNode name = content == null ? null : content.get("diagnostic_name");
      if (name != null && name.isString()) {
        return name.stringValue() + ": " + entry.summary();
      }
    }
    return entry.summary();
  }

  /** The gateway's category with the key of the entry's category (CTX-D-062 (9)). */
  static DataCategory category(EvidenceView entry) {
    return DataCategory.fromKey(entry.dataCategory().key()).orElseThrow();
  }

  /** ISO 8601 in UTC to the second. */
  static String time(Instant instant) {
    return TIME.format(instant.truncatedTo(ChronoUnit.SECONDS));
  }
}
