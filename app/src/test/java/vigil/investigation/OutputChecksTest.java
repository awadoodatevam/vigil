package vigil.investigation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vigil.aigateway.api.AnswerFinish;
import vigil.investigation.api.RunbookRef;
import vigil.investigation.api.Verdict;
import vigil.shared.Ids;

class OutputChecksTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Path RECORDINGS = Path.of("src/test/resources/recordings");
  private static final UUID E1 = Ids.newId();
  private static final UUID E2 = Ids.newId();
  private static final UUID E3 = Ids.newId();
  private static final Map<String, UUID> LABELS = labels();
  private static final List<RunbookRef> SUPPLIED =
      List.of(
          new RunbookRef("RB-API-01", 1),
          new RunbookRef("RB-DB-01", 1),
          new RunbookRef("RB-USSD-01", 1));

  private static Map<String, UUID> labels() {
    Map<String, UUID> labels = new LinkedHashMap<>();
    labels.put("E1", E1);
    labels.put("E2", E2);
    labels.put("E3", E3);
    return labels;
  }

  private static String answer(
      String cause, String verdict, String labels, String uncertainty, String runbooks) {
    StringBuilder text = new StringBuilder("{");
    List<String> fields = new java.util.ArrayList<>();
    if (cause != null) {
      fields.add("\"likely_cause\": " + cause);
    }
    if (verdict != null) {
      fields.add("\"hypothesis_verdict\": " + verdict);
    }
    if (labels != null) {
      fields.add("\"evidence_labels\": " + labels);
    }
    if (uncertainty != null) {
      fields.add("\"uncertainty\": " + uncertainty);
    }
    if (runbooks != null) {
      fields.add("\"runbooks_cited\": " + runbooks);
    }
    return text.append(String.join(", ", fields)).append("}").toString();
  }

  private static final String GOOD =
      answer(
          "\"cause\"",
          "\"confirms\"",
          "[\"E2\", \"E1\", \"E2\"]",
          "\"unknown\"",
          "[\"RB-DB-01\", \"RB-API-01\", \"RB-DB-01\"]");

  private static List<String> reasons(String text) {
    return OutputChecks.slT1(text, AnswerFinish.COMPLETE, LABELS, SUPPLIED, true).reasons();
  }

  @Test
  @DisplayName("AR-G-11 evidence_labels and runbooks_cited mapped back in the answer's order")
  void mapping() {
    OutputChecks.Checked checked =
        OutputChecks.slT1(GOOD, AnswerFinish.COMPLETE, LABELS, SUPPLIED, true);
    assertThat(checked.reasons()).isEmpty();
    assertThat(checked.likelyCause()).isEqualTo("cause");
    assertThat(checked.uncertainty()).isEqualTo("unknown");
    assertThat(checked.verdict()).isEqualTo(Verdict.CONFIRMS);
    assertThat(checked.evidenceEntryIds()).containsExactly(E2, E1);
    assertThat(checked.runbookRefs())
        .containsExactly(new RunbookRef("RB-DB-01", 1), new RunbookRef("RB-API-01", 1));
  }

  @Test
  @DisplayName("DC-TX-11 each rejection reason alone and several in their order")
  void reasonTexts() {
    String l = "[\"E1\"]";
    String r = "[\"RB-DB-01\"]";
    assertThat(reasons(answer(null, "\"confirms\"", l, "\"u\"", r)))
        .containsExactly("no likely cause");
    assertThat(reasons(answer("\"  \"", "\"confirms\"", l, "\"u\"", r)))
        .containsExactly("no likely cause");
    assertThat(reasons(answer("\"c\\u0000\"", "\"confirms\"", l, "\"u\"", r)))
        .containsExactly("no likely cause");
    assertThat(reasons(answer("\"c\"", "\"not_applicable\"", l, "\"u\"", r)))
        .containsExactly("does not say whether it confirms or disputes the hypothesis");
    assertThat(reasons(answer("\"c\"", "\"disputes\"", l, "7", r)))
        .containsExactly("no uncertainty stated");
    assertThat(reasons(answer("\"c\"", "\"disputes\"", "[]", "\"u\"", r)))
        .containsExactly("no evidence reference");
    assertThat(reasons(answer("\"c\"", "\"disputes\"", "[\"E1\", 3]", "\"u\"", r)))
        .containsExactly("no evidence reference");
    assertThat(reasons(answer("\"c\"", "\"disputes\"", "[\"E1\", \"E9\"]", "\"u\"", r)))
        .containsExactly("refers to evidence that is not on the incident");
    assertThat(reasons(answer("\"c\"", "\"disputes\"", l, "\"u\"", null)))
        .containsExactly("no runbook cited");
    assertThat(reasons(answer("\"c\"", "\"disputes\"", l, "\"u\"", "[\"RB-X-99\"]")))
        .containsExactly("cites a runbook that was not supplied");
    assertThat(reasons(answer(null, null, "[\"E9\"]", null, "[\"RB-X-99\"]")))
        .containsExactly(
            "no likely cause",
            "does not say whether it confirms or disputes the hypothesis",
            "no uncertainty stated",
            "refers to evidence that is not on the incident",
            "cites a runbook that was not supplied");
  }

  @Test
  @DisplayName("AR-G-13 an output that cannot be read gives only 'no likely cause'")
  void unreadable() {
    for (AnswerFinish finish : List.of(AnswerFinish.TRUNCATED, AnswerFinish.REFUSED)) {
      OutputChecks.Checked checked = OutputChecks.slT1(GOOD, finish, LABELS, SUPPLIED, true);
      assertThat(checked.reasons()).containsExactly("no likely cause");
      assertThat(checked.likelyCause()).isNull();
      assertThat(checked.evidenceEntryIds()).isEmpty();
    }
    assertThat(OutputChecks.slT1(null, AnswerFinish.COMPLETE, LABELS, SUPPLIED, true).reasons())
        .containsExactly("no likely cause");
    assertThat(reasons("not json")).containsExactly("no likely cause");
    assertThat(reasons("[1, 2]")).containsExactly("no likely cause");
    assertThat(reasons(GOOD.replace("{", "{\"extra\": 1, "))).containsExactly("no likely cause");
  }

  @Test
  @DisplayName("DM-E-68 without a hypothesis the verdict is not checked and stays empty")
  void noHypothesis() {
    for (String verdict : List.of("\"not_applicable\"", "\"confirms\"")) {
      OutputChecks.Checked checked =
          OutputChecks.slT1(
              answer("\"c\"", verdict, "[\"E1\"]", "\"u\"", "[\"RB-DB-01\"]"),
              AnswerFinish.COMPLETE,
              LABELS,
              SUPPLIED,
              false);
      assertThat(checked.reasons()).isEmpty();
      assertThat(checked.verdict()).isNull();
    }
  }

  @Test
  @DisplayName(
      "PM-M17-03 the recorded SL-T-1 answers: Claude and OpenAI accepted, the local"
          + " model's rejected")
  void recordings() throws IOException {
    assertThat(check(claude("claude/0001-sl-t-1.json"))).isEmpty();
    assertThat(check(openai("openai/0001-sl-t-1.json"))).isEmpty();
    for (int k = 1; k <= 5; k++) {
      assertThat(check(ollama("local-llm/000" + k + "-chat-" + k + ".json")))
          .as("local-llm %d", k)
          .containsExactly(
              "does not say whether it confirms or disputes the hypothesis",
              "refers to evidence that is not on the incident");
    }
  }

  private record Output(String text, AnswerFinish finish) {}

  private static List<String> check(Output output) {
    return OutputChecks.slT1(output.text(), output.finish(), LABELS, SUPPLIED, true).reasons();
  }

  private static JsonNode body(String recording) throws IOException {
    JsonNode file = JSON.readTree(Files.readString(RECORDINGS.resolve(recording)));
    return JSON.readTree(file.get("response").get("body").stringValue());
  }

  private static Output claude(String recording) throws IOException {
    JsonNode body = body(recording);
    String text = null;
    for (JsonNode item : body.get("content")) {
      if (item.get("type").stringValue().equals("text")) {
        text = item.get("text").stringValue();
        break;
      }
    }
    String stop = body.get("stop_reason").stringValue();
    AnswerFinish finish =
        switch (stop) {
          case "max_tokens" -> AnswerFinish.TRUNCATED;
          case "refusal" -> AnswerFinish.REFUSED;
          default -> AnswerFinish.COMPLETE;
        };
    return new Output(text, finish);
  }

  private static Output openai(String recording) throws IOException {
    JsonNode body = body(recording);
    StringBuilder text = new StringBuilder();
    for (JsonNode item : body.get("output")) {
      if (item.get("type").stringValue().equals("message")) {
        for (JsonNode content : item.get("content")) {
          if (content.get("type").stringValue().equals("output_text")) {
            text.append(content.get("text").stringValue());
          }
        }
      }
    }
    AnswerFinish finish =
        body.get("status").stringValue().equals("incomplete")
            ? AnswerFinish.TRUNCATED
            : AnswerFinish.COMPLETE;
    return new Output(text.toString(), finish);
  }

  private static Output ollama(String recording) throws IOException {
    JsonNode body = body(recording);
    AnswerFinish finish =
        "length".equals(body.get("done_reason").stringValue())
            ? AnswerFinish.TRUNCATED
            : AnswerFinish.COMPLETE;
    return new Output(body.get("message").get("content").stringValue(), finish);
  }
}
