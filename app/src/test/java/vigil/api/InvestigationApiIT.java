package vigil.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import tools.jackson.databind.JsonNode;
import vigil.actions.ActionsFixture;
import vigil.incidents.api.AiStatus;
import vigil.incidents.api.AiUnavailableReason;
import vigil.incidents.api.EvidenceKind;
import vigil.shared.Actor;
import vigil.shared.Ids;
import vigil.testsupport.ApiClient.Response;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InvestigationApiIT {

  private ApprovalApiFixture api;
  private ActionsFixture f;

  @BeforeAll
  void start() {
    api = new ApprovalApiFixture();
    f = api.fixture;
  }

  @AfterAll
  void stop() {
    api.close();
  }

  private JsonNode read(UUID incident) {
    Response response =
        api.as(f, "presenter", api.client.get("/api/v1/incidents/" + incident + "/investigation"))
            .send();
    assertThat(response.status()).isEqualTo(200);
    return response.json();
  }

  private static List<String> texts(JsonNode array) {
    return array.valueStream().map(JsonNode::stringValue).toList();
  }

  @Test
  @DisplayName("AR-I-25 the AI investigation section: result, summary and AI status")
  void investigation() {
    UUID incident = f.openIncident();
    Instant t0 = api.clock.instant();
    JsonNode empty = read(incident);
    assertThat(empty.get("ai_status").stringValue()).isEqualTo("not_run");
    assertThat(empty.get("ai_unavailable_reason").isNull()).isTrue();
    assertThat(empty.get("result").isNull()).isTrue();
    assertThat(empty.get("summary").isNull()).isTrue();

    UUID disk = f.evidence(incident, EvidenceKind.DIAGNOSTIC_RESULT, "/data is 100% full", t0);
    UUID similar = f.evidence(incident, EvidenceKind.SIMILARITY_MATCH, "PI-1001 matched", t0);
    UUID workflow =
        api.result(
            f, incident, "workflow_step", null, true, List.of(similar, Ids.newId(), disk), t0);
    JsonNode first = read(incident).get("result");
    assertThat(first.get("likely_cause").stringValue())
        .isEqualTo("The archive catch-up run filled /data (" + workflow + ").");
    assertThat(first.get("verdict").stringValue()).isEqualTo("confirms");
    assertThat(first.get("evidence_used")).hasSize(2);
    assertThat(first.get("evidence_used").get(0).get("evidence_entry_id").stringValue())
        .isEqualTo(similar.toString());
    assertThat(first.get("evidence_used").get(0).get("summary").stringValue())
        .isEqualTo("PI-1001 matched");
    assertThat(first.get("evidence_used").get(1).get("evidence_entry_id").stringValue())
        .isEqualTo(disk.toString());
    assertThat(first.get("evidence_used").get(1).get("summary").stringValue())
        .isEqualTo("/data is 100% full");
    assertThat(first.get("uncertainty").stringValue())
        .isEqualTo("Whether the archive job will run again.");
    assertThat(texts(first.get("runbooks_supplied")))
        .containsExactly("RB-DB-01", "RB-API-01", "RB-USSD-01");
    assertThat(texts(first.get("runbooks_cited"))).containsExactly("RB-DB-01", "RB-USSD-01");
    assertThat(first.get("provider_kind").stringValue()).isEqualTo("claude");
    assertThat(first.get("model_name").stringValue()).isEqualTo("claude-sonnet-5-5");
    assertThat(first.get("created_at").stringValue()).isEqualTo(ApiTimes.format(t0));
    assertThat(first.get("origin").stringValue()).isEqualTo("workflow_step");
    assertThat(first.get("requested_by").isNull()).isTrue();

    UUID engineer = f.userId("dbops-engineer");
    UUID rerun = api.result(f, incident, "rerun", engineer, true, List.of(disk), t0.plusSeconds(1));
    api.result(f, incident, "rerun", engineer, false, List.of(), t0.plusSeconds(2));
    JsonNode second = read(incident).get("result");
    assertThat(second.get("likely_cause").stringValue()).contains(rerun.toString());
    assertThat(second.get("origin").stringValue()).isEqualTo("rerun");
    assertThat(second.get("created_at").stringValue())
        .isEqualTo(ApiTimes.format(t0.plusSeconds(1)));
    assertThat(second.get("requested_by").get("user_id").stringValue())
        .isEqualTo(engineer.toString());
    assertThat(second.get("requested_by").get("display_name").stringValue())
        .isEqualTo("Usman Tariq (DB Ops)");

    api.summary(f, incident, "template", "Template summary.", t0);
    api.summary(f, incident, "ai", "AI summary.", t0.plusSeconds(1));
    JsonNode summary = read(incident).get("summary");
    assertThat(summary.get("text").stringValue()).isEqualTo("AI summary.");
    assertThat(summary.get("source").stringValue()).isEqualTo("ai");

    UUID group = Ids.newId();
    f.incidents.setAiStatus(
        f.tenant,
        Actor.module("vigil-investigation"),
        incident,
        AiStatus.UNAVAILABLE,
        AiUnavailableReason.NO_PROVIDER_REACHABLE,
        group);
    JsonNode unavailable = read(incident);
    assertThat(unavailable.get("ai_status").stringValue()).isEqualTo("unavailable");
    assertThat(unavailable.get("ai_unavailable_reason").stringValue())
        .isEqualTo("no_provider_reachable");
    assertThat(unavailable.get("ai_status_changed_at").stringValue())
        .isEqualTo(ApiTimes.format(api.clock.instant()));
    assertThat(unavailable.get("ai_last_request_group_id").stringValue())
        .isEqualTo(group.toString());
    assertThat(unavailable.get("result").get("origin").stringValue()).isEqualTo("rerun");

    Response merged =
        api.as(
                f,
                "presenter",
                api.client.get("/api/v1/incidents/" + api.mergedIncident(f) + "/investigation"))
            .send();
    assertThat(merged.status()).isEqualTo(404);
    assertThat(merged.json().get("error").get("code").stringValue()).isEqualTo("not_found");
  }
}
