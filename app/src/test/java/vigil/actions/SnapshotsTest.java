package vigil.actions;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import vigil.actions.api.RegisteredActionView;
import vigil.actions.api.StateCheck;
import vigil.actions.api.StateCheckOperator;
import vigil.incidents.api.DataCategory;
import vigil.incidents.api.EvidenceKind;
import vigil.incidents.api.EvidenceView;
import vigil.knowledge.api.PastIncidentView;
import vigil.recovery.api.ConditionKind;
import vigil.recovery.api.RecoveryCheckView;
import vigil.recovery.api.RecoveryCondition;
import vigil.shared.Actor;
import vigil.shared.CanonicalJson;

class SnapshotsTest {

  static final Instant T0 = Instant.parse("2026-11-12T09:00:00Z");
  static final List<String> KEYS =
      List.of(
          "action_key",
          "action_name",
          "parameters",
          "target_key",
          "target_name",
          "limits",
          "attempt_number",
          "attempt_limit",
          "expires_at",
          "evidence",
          "based_on",
          "recovery_checks");

  private static EvidenceView entry(EvidenceKind kind, String summary, int second) {
    return new EvidenceView(
        UUID.randomUUID(),
        UUID.randomUUID(),
        kind,
        "m",
        UUID.randomUUID(),
        "s",
        T0.plusSeconds(second),
        summary,
        "{}",
        DataCategory.INCIDENT_RECORD);
  }

  private static ObjectNode snapshot() {
    StateCheck check =
        new StateCheck(
            "fs_used_pct",
            "{\"mount\":\"/data\"}",
            "value",
            StateCheckOperator.GREATER_OR_EQUAL,
            "90");
    RegisteredActionView action =
        new RegisteredActionView(
            "purge-archived-txlogs",
            "Purge archived transaction logs",
            "purge_old_archives",
            "{\"path\":\"/data/txlog_archive\",\"older_than_minutes\":1440}",
            List.of("limit one", "limit two"),
            1,
            check,
            check,
            "rc-subscriber-db",
            true,
            Actor.module("vigil-setup"));
    PastIncidentView past =
        new PastIncidentView(
            UUID.randomUUID(),
            "PI-1001",
            "Subscriber DB /data full after archive catch-up run",
            "Fictional record",
            T0,
            null,
            null,
            31,
            List.of(),
            List.of(),
            "srv-db-01",
            "summary",
            "fix",
            "purge-archived-txlogs",
            4,
            true);
    RecoveryCheckView recovery =
        new RecoveryCheckView(
            "rc-subscriber-db",
            "Subscriber DB services recovered",
            List.of(new RecoveryCondition(ConditionKind.INCIDENT_ALERTS_RESOLVED, null, List.of())),
            Duration.ofSeconds(60),
            false,
            true,
            List.of("Every alert of the incident is resolved at its source"));
    return Snapshots.approval(
        Snapshots.action(action),
        "lab-db-01",
        "Subscriber DB server",
        1,
        T0.plusSeconds(900),
        List.of(
            entry(EvidenceKind.SIMILARITY_MATCH, "similar", 30),
            entry(EvidenceKind.APPROVAL, "approval", 5),
            entry(EvidenceKind.DIAGNOSTIC_RESULT, "disk", 10),
            entry(EvidenceKind.AI_INVESTIGATION, "ai", 20),
            entry(EvidenceKind.ACTION_EXECUTION, "execution", 25)),
        past,
        new BigDecimal("1"),
        4,
        recovery);
  }

  @Test
  @DisplayName("AR-P-03 the hash is SHA-256 of the canonical snapshot; any change changes it")
  void hash() {
    ObjectNode snapshot = snapshot();
    String hash = Snapshots.hash(snapshot);
    assertThat(hash)
        .isEqualTo(
            CanonicalJson.sha256HexOfCanonical(ActionRecords.JSON.writeValueAsString(snapshot)))
        .matches("^[0-9a-f]{64}$");
    for (String key : KEYS) {
      ObjectNode changed = snapshot.deepCopy();
      JsonNode value = changed.get(key);
      if (value.isObject()) {
        ((ObjectNode) value).put("changed", true);
      } else if (value.isArray()) {
        ((tools.jackson.databind.node.ArrayNode) value).add("changed");
      } else if (value.isNumber()) {
        changed.put(key, value.longValue() + 1);
      } else {
        changed.put(key, value.asString() + "x");
      }
      assertThat(Snapshots.hash(changed)).as(key).isNotEqualTo(hash);
    }
  }

  @Test
  @DisplayName("DM-V-12 every key of the snapshot with its value")
  void parts() {
    ObjectNode s = snapshot();
    assertThat(s.propertyNames()).containsExactlyElementsOf(KEYS);
    assertThat(s.get("action_key").asString()).isEqualTo("purge-archived-txlogs");
    assertThat(s.get("action_name").asString()).isEqualTo("Purge archived transaction logs");
    assertThat(s.get("parameters").toString())
        .isEqualTo("{\"path\":\"/data/txlog_archive\",\"older_than_minutes\":1440}");
    assertThat(s.get("target_key").asString()).isEqualTo("lab-db-01");
    assertThat(s.get("target_name").asString()).isEqualTo("Subscriber DB server");
    assertThat(s.get("limits").toString()).isEqualTo("[\"limit one\",\"limit two\"]");
    assertThat(s.get("attempt_number").asLong()).isEqualTo(1);
    assertThat(s.get("attempt_limit").asLong()).isEqualTo(1);
    assertThat(s.get("expires_at").asString()).isEqualTo("2026-11-12T09:15:00Z");
    assertThat(s.get("evidence").valueStream().map(e -> e.get("summary").asString()).toList())
        .containsExactly("disk", "ai", "similar");
    assertThat(s.get("evidence").get(0).propertyNames())
        .containsExactly("evidence_entry_id", "kind", "summary", "collected_at");
    assertThat(s.get("evidence").get(0).get("kind").asString()).isEqualTo("diagnostic_result");
    assertThat(s.get("evidence").get(0).get("collected_at").asString())
        .isEqualTo("2026-11-12T09:00:10Z");
    JsonNode basedOn = s.get("based_on");
    assertThat(basedOn.get("past_incident_key").asString()).isEqualTo("PI-1001");
    assertThat(basedOn.get("past_incident_title").asString())
        .isEqualTo("Subscriber DB /data full after archive catch-up run");
    assertThat(basedOn.get("record_label").asString()).isEqualTo("Fictional record");
    assertThat(basedOn.get("score").asString()).isEqualTo("1.00");
    assertThat(basedOn.get("success_count").asLong()).isEqualTo(4);
    JsonNode checks = s.get("recovery_checks");
    assertThat(checks.get("recovery_check_key").asString()).isEqualTo("rc-subscriber-db");
    assertThat(checks.get("name").asString()).isEqualTo("Subscriber DB services recovered");
    assertThat(checks.get("conditions").toString())
        .isEqualTo("[\"Every alert of the incident is resolved at its source\"]");
    assertThat(checks.get("observation_window").asLong()).isEqualTo(60);
  }
}
