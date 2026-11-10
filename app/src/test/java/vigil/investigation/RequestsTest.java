package vigil.investigation;

import static org.assertj.core.api.Assertions.assertThat;
import static vigil.investigation.TestViews.FIRED;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import vigil.aigateway.api.AiPart;
import vigil.aigateway.api.DataCategory;
import vigil.incidents.api.AffectedServiceCopy;
import vigil.incidents.api.EvidenceKind;
import vigil.incidents.api.EvidenceView;
import vigil.incidents.api.HypothesisCopy;
import vigil.incidents.api.IncidentView;
import vigil.incidents.api.MembershipView;
import vigil.investigation.api.InvestigationResultView;
import vigil.investigation.api.Origin;
import vigil.investigation.api.RunbookRef;
import vigil.investigation.api.Validation;
import vigil.investigation.api.Verdict;
import vigil.shared.Actor;
import vigil.shared.Ids;

class RequestsTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final HypothesisCopy HYPOTHESIS =
      new HypothesisCopy(1, "srv-db-01", "Subscriber DB server", "server", "critical");
  private static final List<AffectedServiceCopy> SERVICES =
      List.of(
          new AffectedServiceCopy(
              "svc-balance-check", "Balance Check", "critical", List.of("api-balance", "db-sub")),
          new AffectedServiceCopy("svc-sms", "SMS", null, List.of("sms-queue")));
  private static final List<MembershipView> MEMBERS =
      List.of(
          TestViews.member("disk_full", "Disk full", "srv-db-01", "Disk full on srv-db-01", FIRED),
          TestViews.member(
              "balance_api_errors", "Balance API errors", "api-balance", "Errors", FIRED),
          TestViews.member(
              "ussd_timeouts", "USSD timeouts", null, "Timeouts", FIRED.plusSeconds(3)));
  private static final EvidenceView DIAG_1 =
      TestViews.evidence(
          EvidenceKind.DIAGNOSTIC_RESULT,
          "/data 100% used",
          "{\"diagnostic_name\": \"Disk usage\"}");
  private static final EvidenceView AI =
      TestViews.evidence(EvidenceKind.AI_INVESTIGATION, "AI investigation by Claude", "{}");
  private static final EvidenceView DIAG_2 =
      TestViews.evidence(EvidenceKind.DIAGNOSTIC_RESULT, "archive 8.5 GiB", "{}");

  @Test
  @DisplayName("AR-G-12 the system texts and schemas of SL-T-1 and SL-T-2 exactly")
  void systemTextsAndSchemas() {
    assertThat(Requests.SYSTEM_SL_T_1)
        .isEqualTo(
            "You are an IT and service NOC investigator. Use only the information in the supplied"
                + " parts. Give the most likely cause of the incident in likely_cause. The part"
                + " labelled Hypothesis, when it is supplied, names the root-cause hypothesis: in"
                + " hypothesis_verdict, say whether the likely cause confirms or disputes that"
                + " hypothesis, and answer not_applicable only when no part labelled Hypothesis"
                + " is supplied. The evidence entries are the parts labelled E1, E2 and so on;"
                + " member alerts and runbooks are not evidence entries. In evidence_labels, list"
                + " the labels of the evidence entries the likely cause rests on, and no other"
                + " label. In uncertainty, state what remains uncertain. In runbooks_cited, list"
                + " the key of every supplied runbook whose steps address the likely cause, and"
                + " no other key. Do not decide severity, owner or any action. Answer only with"
                + " the JSON object.");
    assertThat(Requests.SYSTEM_SL_T_2)
        .isEqualTo(
            "Write a summary of at most 600 characters for NOC staff in summary, in plain"
                + " sentences, using only the supplied parts. Answer only with the JSON object.");
    assertThat(JSON.writeValueAsString(Requests.SCHEMA_SL_T_1))
        .isEqualTo(
            "{\"type\":\"object\",\"properties\":{\"likely_cause\":{\"type\":\"string\"},"
                + "\"hypothesis_verdict\":{\"type\":\"string\",\"enum\":[\"confirms\","
                + "\"disputes\",\"not_applicable\"]},\"evidence_labels\":{\"type\":\"array\","
                + "\"items\":{\"type\":\"string\"}},\"uncertainty\":{\"type\":\"string\"},"
                + "\"runbooks_cited\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}},"
                + "\"required\":[\"likely_cause\",\"hypothesis_verdict\",\"evidence_labels\","
                + "\"uncertainty\",\"runbooks_cited\"],\"additionalProperties\":false}");
    assertThat(JSON.writeValueAsString(Requests.SCHEMA_SL_T_2))
        .isEqualTo(
            "{\"type\":\"object\",\"properties\":{\"summary\":{\"type\":\"string\"}},"
                + "\"required\":[\"summary\"],\"additionalProperties\":false}");
    assertThat(Requests.NAME_SL_T_1).isEqualTo("sl_t_1");
    assertThat(Requests.NAME_SL_T_2).isEqualTo("sl_t_2");
    assertThat(Requests.MAX_UNITS_SL_T_1).isEqualTo(1500);
    assertThat(Requests.MAX_UNITS_SL_T_2).isEqualTo(600);
  }

  @Test
  @DisplayName("DM-R-43 the SL-T-1 parts with their labels, categories, texts and order")
  void slT1Parts() {
    IncidentView incident =
        TestViews.incident(
            "Title",
            3,
            List.of("balance_api_errors", "disk_full", "ussd_timeouts"),
            HYPOTHESIS,
            SERVICES);
    Requests.SlT1 request =
        Requests.slT1(
            incident,
            MEMBERS,
            List.of(DIAG_1, AI, DIAG_2),
            List.of("api-balance", "ussd-gw"),
            List.of(
                TestViews.runbook("RB-DB-01", "DB title", "DB text"),
                TestViews.runbook("RB-API-01", "API title", "API text")));
    assertThat(request.parts())
        .containsExactly(
            new AiPart(
                "Member alerts",
                DataCategory.ALERT_DATA,
                "- disk_full | srv-db-01 | zabbix | Disk full on srv-db-01 | fired"
                    + " 2026-11-10T09:15:02Z\n"
                    + "- balance_api_errors | api-balance | zabbix | Errors | fired"
                    + " 2026-11-10T09:15:02Z\n"
                    + "- ussd_timeouts | unknown | zabbix | Timeouts | fired 2026-11-10T09:15:05Z"),
            new AiPart(
                "Hypothesis",
                DataCategory.SERVICE_CONTEXT,
                "Root-cause hypothesis: Subscriber DB server (srv-db-01), kind server,"
                    + " criticality critical; 1 other affected components depend on it"),
            new AiPart(
                "Customer impact",
                DataCategory.SERVICE_CONTEXT,
                "Balance Check (svc-balance-check), criticality critical, through api-balance,"
                    + " db-sub\nSMS (svc-sms), criticality not set, through sms-queue"),
            new AiPart("E1", DataCategory.DIAGNOSTIC_OUTPUT, "Disk usage: /data 100% used"),
            new AiPart("E2", DataCategory.DIAGNOSTIC_OUTPUT, "archive 8.5 GiB"),
            new AiPart("RB-DB-01", DataCategory.RUNBOOK_TEXT, "DB title\n\nDB text"),
            new AiPart("RB-API-01", DataCategory.RUNBOOK_TEXT, "API title\n\nAPI text"));
    assertThat(request.labels())
        .containsExactly(
            java.util.Map.entry("E1", DIAG_1.id()), java.util.Map.entry("E2", DIAG_2.id()));
    assertThat(request.supplied())
        .containsExactly(new RunbookRef("RB-DB-01", 1), new RunbookRef("RB-API-01", 1));
    Requests.SlT1 plain =
        Requests.slT1(
            TestViews.incident("Title", 3, List.of("disk_full"), null, List.of()),
            MEMBERS,
            List.of(),
            List.of(),
            List.of());
    assertThat(plain.parts())
        .extracting(AiPart::label)
        .containsExactly("Member alerts", "Customer impact");
    assertThat(plain.parts().get(1).text()).isEqualTo("none identified");
  }

  @Test
  @DisplayName("AR-G-10 the SL-T-2 parts with and without an accepted result")
  void slT2Parts() {
    IncidentView incident =
        TestViews.incident(
            "Storm title", 7, List.of("disk_full", "balance_api_errors"), HYPOTHESIS, SERVICES);
    InvestigationResultView accepted =
        new InvestigationResultView(
            Ids.newId(),
            incident.id(),
            Origin.WORKFLOW_STEP,
            Ids.newId(),
            Actor.module("vigil-investigation"),
            Ids.newId(),
            "claude",
            "m",
            "The cause",
            Verdict.CONFIRMS,
            List.of(),
            "Not known",
            List.of(),
            List.of(),
            "{}",
            false,
            Validation.ACCEPTED,
            List.of(),
            java.time.Instant.now());
    List<AiPart> parts =
        Requests.slT2(incident, MEMBERS, List.of("api-balance"), accepted, List.of(DIAG_1, AI));
    assertThat(parts)
        .containsExactly(
            new AiPart("Title", DataCategory.INCIDENT_RECORD, "Storm title"),
            new AiPart(
                "Investigation",
                DataCategory.INCIDENT_RECORD,
                "Likely cause: The cause\nUncertainty: Not known"),
            new AiPart(
                "Alerts",
                DataCategory.ALERT_DATA,
                "7 alerts of 2 types: balance_api_errors, disk_full"),
            new AiPart(
                "Customer impact",
                DataCategory.SERVICE_CONTEXT,
                "Balance Check (svc-balance-check), criticality critical, through api-balance,"
                    + " db-sub\nSMS (svc-sms), criticality not set, through sms-queue"),
            new AiPart(
                "Hypothesis",
                DataCategory.SERVICE_CONTEXT,
                "Root-cause hypothesis: Subscriber DB server (srv-db-01), kind server,"
                    + " criticality critical; 1 other affected components depend on it"),
            new AiPart("E1", DataCategory.DIAGNOSTIC_OUTPUT, "/data 100% used"),
            new AiPart("E2", DataCategory.INCIDENT_RECORD, "AI investigation by Claude"));
    assertThat(Requests.slT2(incident, MEMBERS, List.of(), null, List.of()))
        .extracting(AiPart::label)
        .containsExactly("Title", "Alerts", "Customer impact", "Hypothesis");
  }
}
