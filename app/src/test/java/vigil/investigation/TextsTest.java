package vigil.investigation;

import static org.assertj.core.api.Assertions.assertThat;
import static vigil.investigation.TestViews.FIRED;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vigil.incidents.api.AffectedServiceCopy;
import vigil.incidents.api.HypothesisCopy;
import vigil.incidents.api.IncidentView;
import vigil.incidents.api.MembershipView;
import vigil.investigation.api.RunbookRef;
import vigil.investigation.api.Verdict;

class TextsTest {

  private static final HypothesisCopy HYPOTHESIS =
      new HypothesisCopy(1, "srv-db-01", "Subscriber DB server", "server", "critical");
  private static final List<RunbookRef> CITED =
      List.of(new RunbookRef("RB-DB-01", 1), new RunbookRef("RB-API-01", 1));

  @Test
  @DisplayName(
      "DC-TX-03 the evidence summaries and sources of ai_investigation and similarity_match")
  void evidenceTexts() {
    assertThat(Texts.kindName("claude")).isEqualTo("Claude");
    assertThat(Texts.kindName("openai")).isEqualTo("OpenAI");
    assertThat(Texts.kindName("local_model")).isEqualTo("Local model");
    assertThat(Texts.kindName("local_embedding")).isEqualTo("Local embedding model");
    assertThat(Texts.investigationSource("Claude", "claude-sonnet-5-5"))
        .isEqualTo("Claude, model claude-sonnet-5-5");
    assertThat(
            Texts.investigationSummary("Claude", Verdict.CONFIRMS, "Subscriber DB server", CITED))
        .isEqualTo(
            "AI investigation by Claude: confirms the hypothesis Subscriber DB server; cites"
                + " RB-DB-01, RB-API-01.");
    assertThat(
            Texts.investigationSummary("OpenAI", Verdict.DISPUTES, "Subscriber DB server", CITED))
        .isEqualTo(
            "AI investigation by OpenAI: disputes the hypothesis Subscriber DB server; cites"
                + " RB-DB-01, RB-API-01.");
    assertThat(Texts.investigationSummary("Local model", null, null, CITED))
        .isEqualTo("AI investigation by Local model; cites RB-DB-01, RB-API-01.");
    assertThat(Texts.similaritySource("Local embedding model"))
        .isEqualTo("Incident history, Local embedding model");
    assertThat(
            Texts.similaritySummary(
                "PI-1001",
                "Subscriber DB /data full",
                "Fictional record",
                new BigDecimal("1.00"),
                4))
        .isEqualTo(
            "Similar past incident PI-1001 'Subscriber DB /data full' (Fictional record),"
                + " similarity 1.00; fix succeeded 4 times.");
    assertThat(Texts.similaritySummary("PI-1", "T", "L", new BigDecimal("0.9"), 1))
        .isEqualTo("Similar past incident PI-1 'T' (L), similarity 0.90; fix succeeded 1 time.");
    assertThat(Texts.auditAccepted("Claude"))
        .isEqualTo("Incident investigation by Claude accepted.");
  }

  @Test
  @DisplayName("DC-TX-10 the audit summaries of ai_result_accepted and ai_result_rejected")
  void auditTexts() {
    assertThat(Texts.auditAccepted("OpenAI"))
        .isEqualTo("Incident investigation by OpenAI accepted.");
    assertThat(
            Texts.auditRejected(
                "Local model",
                List.of(
                    "does not say whether it confirms or disputes the hypothesis",
                    "refers to evidence that is not on the incident")))
        .isEqualTo(
            "Incident investigation by Local model rejected: does not say whether it confirms or"
                + " disputes the hypothesis; refers to evidence that is not on the incident.");
  }

  @Test
  @DisplayName("DM-E-69 the template summary with and without impact and hypothesis")
  void template() {
    List<MembershipView> members =
        List.of(
            TestViews.member("disk_full", "Disk full", "srv-db-01", "t", FIRED),
            TestViews.member(
                "balance_api_errors", "Balance API errors", "api-balance", "t", FIRED));
    IncidentView incident =
        TestViews.incident(
            "Subscriber DB server: Disk full — 2 services affected",
            120,
            List.of("balance_api_errors", "disk_full", "sms_queue_growth"),
            HYPOTHESIS,
            List.of(
                new AffectedServiceCopy("svc-b", "Bundle Purchase", null, List.of("api-bundle")),
                new AffectedServiceCopy("svc-a", "Balance Check", null, List.of("api-balance"))));
    assertThat(Texts.template(incident, members, 3))
        .isEqualTo(
            "Subscriber DB server: Disk full — 2 services affected. 120 alerts (Balance API"
                + " errors, Disk full, sms_queue_growth). Customer impact: Bundle Purchase, Balance"
                + " Check. Hypothesis: Subscriber DB server. Evidence: 3 items.");
    IncidentView plain = TestViews.incident("Disk full", 1, List.of("disk_full"), null, List.of());
    assertThat(Texts.template(plain, members, 0))
        .isEqualTo(
            "Disk full. 1 alerts (Disk full). Customer impact: none identified. Hypothesis: none."
                + " Evidence: 0 items.");
  }
}
