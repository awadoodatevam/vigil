package vigil.actions;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import vigil.actions.api.RegisteredActionView;
import vigil.incidents.api.EvidenceKind;
import vigil.incidents.api.EvidenceView;
import vigil.knowledge.api.PastIncidentView;
import vigil.recovery.api.RecoveryCheckView;
import vigil.shared.CanonicalJson;

/**
 * The action snapshot (DM-E-57), the approval snapshot (DM-V-12) and its hash (AR-P-03), in the
 * JSON forms of P-24 spec Section 6.5.
 */
final class Snapshots {

  private static final Set<EvidenceKind> SNAPSHOT_KINDS =
      Set.of(
          EvidenceKind.DIAGNOSTIC_RESULT,
          EvidenceKind.AI_INVESTIGATION,
          EvidenceKind.SIMILARITY_MATCH);

  private Snapshots() {}

  /** The copy of the registered action's fields (DM-E-57 {@code action_snapshot}). */
  static ObjectNode action(RegisteredActionView action) {
    ObjectNode node = ActionRecords.JSON.createObjectNode();
    node.put("action_key", action.actionKey());
    node.put("name", action.name());
    node.put("operation_key", action.operationKey());
    node.set("parameter_values", ActionRecords.JSON.readTree(action.parameterValuesJson()));
    node.set("limits", ActionRecords.texts(action.limits()));
    node.put("attempt_limit", action.attemptLimit());
    node.set("precondition", StateChecks.json(action.precondition()));
    node.put("recovery_check_key", action.recoveryCheckKey());
    return node;
  }

  /**
   * The approval snapshot: every key always present, the evidence of the three kinds of DM-V-12 in
   * {@code collected_at} order, then id.
   */
  static ObjectNode approval(
      JsonNode actionSnapshot,
      String targetKey,
      String targetName,
      long attemptNumber,
      Instant expiresAt,
      List<EvidenceView> evidence,
      PastIncidentView pastIncident,
      BigDecimal score,
      long successCount,
      RecoveryCheckView recoveryCheck) {
    ObjectNode node = ActionRecords.JSON.createObjectNode();
    node.set("action_key", actionSnapshot.get("action_key"));
    node.set("action_name", actionSnapshot.get("name"));
    node.set("parameters", actionSnapshot.get("parameter_values"));
    node.put("target_key", targetKey);
    node.put("target_name", targetName);
    node.set("limits", actionSnapshot.get("limits"));
    node.put("attempt_number", attemptNumber);
    node.set("attempt_limit", actionSnapshot.get("attempt_limit"));
    node.put("expires_at", expiresAt.toString());
    ArrayNode entries = node.putArray("evidence");
    evidence.stream()
        .filter(entry -> SNAPSHOT_KINDS.contains(entry.kind()))
        .sorted(
            Comparator.comparing(EvidenceView::collectedAt)
                .thenComparing(entry -> entry.id().toString()))
        .forEach(
            entry ->
                entries
                    .addObject()
                    .put("evidence_entry_id", entry.id().toString())
                    .put("kind", entry.kind().key())
                    .put("summary", entry.summary())
                    .put("collected_at", entry.collectedAt().toString()));
    ObjectNode basedOn = node.putObject("based_on");
    basedOn.put("past_incident_key", pastIncident.pastIncidentKey());
    basedOn.put("past_incident_title", pastIncident.title());
    basedOn.put("record_label", pastIncident.recordLabel());
    basedOn.put("score", score.setScale(2, RoundingMode.HALF_UP).toPlainString());
    basedOn.put("success_count", successCount);
    ObjectNode checks = node.putObject("recovery_checks");
    checks.put("recovery_check_key", recoveryCheck.recoveryCheckKey());
    checks.put("name", recoveryCheck.name());
    checks.set("conditions", ActionRecords.texts(recoveryCheck.requirementTexts()));
    checks.put("observation_window", recoveryCheck.observationWindow().toSeconds());
    return node;
  }

  /** SHA-256 of the RFC 8785 form of the snapshot, 64 lowercase hexadecimal characters. */
  static String hash(JsonNode snapshot) {
    return CanonicalJson.sha256HexOfCanonical(ActionRecords.JSON.writeValueAsString(snapshot));
  }
}
