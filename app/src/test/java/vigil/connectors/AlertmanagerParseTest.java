package vigil.connectors;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import vigil.connectors.api.AlertStatus;
import vigil.connectors.api.IdentityPair;
import vigil.connectors.api.ParseResult;
import vigil.connectors.api.ParsedEntry;

class AlertmanagerParseTest {

  private static final String FIRST = Recordings.body("0101-alertmanager-lab.json");
  private static final String RESOLVED = Recordings.body("0153-alertmanager-lab.json");

  private static List<ParsedEntry> entries(String body) {
    ParseResult result = AlertmanagerPayloads.parse(Recordings.bytes(body));
    assertThat(result).isInstanceOf(ParseResult.Parsed.class);
    return ((ParseResult.Parsed) result).entries();
  }

  private static ParsedEntry.Normalised only(String body) {
    List<ParsedEntry> entries = entries(body);
    assertThat(entries).hasSize(1);
    return (ParsedEntry.Normalised) entries.getFirst();
  }

  /** The text of the body's one alert element, as the recordings write it. */
  private static String alertText(String body) {
    int start = body.indexOf("\"alerts\":[") + "\"alerts\":[".length();
    int end = body.indexOf("],\"notification_reason\"");
    return body.substring(start, end);
  }

  private static ObjectNode alert(String body) {
    return (ObjectNode) Recordings.tree(alertText(body));
  }

  private static String message(String... alerts) {
    return "{\"alerts\":[" + String.join(",", alerts) + "]}";
  }

  private static String text(JsonNode node) {
    return Recordings.JSON.writeValueAsString(node);
  }

  @Test
  @DisplayName("AR-K-02 the recorded Alertmanager messages are normalised as Section 12.4 says")
  void recordings() {
    List<String> names = Recordings.names("alertmanager-lab");
    assertThat(names).hasSize(190);
    int firing = 0;
    int resolved = 0;
    for (String name : names) {
      String body = Recordings.body(name);
      JsonNode alert = Recordings.tree(body).get("alerts").get(0);
      ParsedEntry.Normalised entry = only(body);
      assertThat(entry.entryIndex()).isZero();
      assertThat(entry.payload()).as(name).isEqualTo(alertText(body));
      assertThat(Recordings.tree(entry.payload())).isEqualTo(alert);
      boolean isFiring = alert.get("status").stringValue().equals("firing");
      JsonNode labels = alert.get("labels");
      assertThat(entry.alertKey()).isEqualTo(alert.get("fingerprint").stringValue());
      assertThat(entry.status()).isEqualTo(isFiring ? AlertStatus.FIRING : AlertStatus.RESOLVED);
      assertThat(entry.title()).isEqualTo(alert.get("annotations").get("summary").stringValue());
      assertThat(entry.sourceTime())
          .isEqualTo(Instant.parse(alert.get(isFiring ? "startsAt" : "endsAt").stringValue()));
      assertThat(entry.sourceSeverity()).isNull();
      assertThat(entry.identityPairs())
          .containsExactly(
              new IdentityPair("component", labels.get("component").stringValue()),
              new IdentityPair("instance", labels.get("instance").stringValue()));
      Map<String, String> fields = new LinkedHashMap<>();
      for (Map.Entry<String, JsonNode> label : labels.properties()) {
        fields.put("label." + label.getKey(), label.getValue().stringValue());
      }
      fields.put("annotation.summary", alert.get("annotations").get("summary").stringValue());
      assertThat(entry.mappingFields()).isEqualTo(fields);
      if (isFiring) {
        firing++;
      } else {
        resolved++;
      }
    }
    assertThat(firing).isEqualTo(95);
    assertThat(resolved).isEqualTo(95);

    ParsedEntry.Normalised one = only(FIRST);
    assertThat(one.alertKey()).isEqualTo("f0cc97320dabe6ac");
    assertThat(one.status()).isEqualTo(AlertStatus.FIRING);
    assertThat(one.title()).isEqualTo("Synthetic USSD session failed: rawalpindi, bundle-list");
    assertThat(one.sourceTime()).isEqualTo(Instant.parse("2026-10-15T19:01:06.865Z"));
    assertThat(one.identityPairs())
        .containsExactly(
            new IdentityPair("component", "ussd-probe"),
            new IdentityPair("instance", "lab-probe-01:9200"));
    assertThat(one.mappingFields())
        .isEqualTo(
            Map.of(
                "label.alertname", "SyntheticUssdSessionFailed",
                "label.city", "rawalpindi",
                "label.component", "ussd-probe",
                "label.instance", "lab-probe-01:9200",
                "label.job", "lab-probe",
                "label.journey", "bundle-list",
                "annotation.summary", "Synthetic USSD session failed: rawalpindi, bundle-list"));
    assertThat(one.payload()).contains("\\u0026").endsWith("\"fingerprint\":\"f0cc97320dabe6ac\"}");
    ParsedEntry.Normalised two = only(RESOLVED);
    assertThat(two.alertKey()).isEqualTo("d925f9c0e9778519");
    assertThat(two.status()).isEqualTo(AlertStatus.RESOLVED);
    assertThat(two.title()).isEqualTo("Disk full on lab-db-01 /data");
    assertThat(two.sourceTime()).isEqualTo(Instant.parse("2026-10-15T19:03:21.865Z"));
    assertThat(two.identityPairs())
        .containsExactly(
            new IdentityPair("component", "srv-db-01"),
            new IdentityPair("instance", "lab-db-01:9100"));
  }

  @Test
  @DisplayName("PM-K-02 alertmanager parse of unusual and wrong bodies")
  void unusualBodies() {
    String a = alertText(FIRST);
    String b = alertText(RESOLVED);
    String c = a.replace("\"status\":\"firing\"", "\"status\":\"pending\"");
    List<ParsedEntry> three = entries("{\"alerts\":[ " + a + " ,\n " + b + "," + c + "]}");
    assertThat(three).extracting(ParsedEntry::payload).containsExactly(a, b, c);
    assertThat(three).extracting(ParsedEntry::entryIndex).containsExactly(0, 1, 2);
    assertThat(three.get(0)).isInstanceOf(ParsedEntry.Normalised.class);
    assertThat(three.get(1)).isInstanceOf(ParsedEntry.Normalised.class);
    assertThat(three.get(2)).isEqualTo(new ParsedEntry.Failed(2, c, "unknown status"));

    ObjectNode noFingerprint = alert(FIRST);
    noFingerprint.remove("fingerprint");
    assertThat(entries(message(text(noFingerprint))).getFirst())
        .isEqualTo(new ParsedEntry.Failed(0, text(noFingerprint), "no fingerprint"));
    assertThat(entries(message("42")).getFirst())
        .isEqualTo(new ParsedEntry.Failed(0, "42", "the entry is not a JSON object"));
    assertThat(entries("{\"alerts\":[]}")).isEmpty();
    for (String body : List.of("{}", "{\"alerts\":{}}")) {
      assertThat(AlertmanagerPayloads.parse(Recordings.bytes(body)))
          .isEqualTo(new ParseResult.Failed("the body has no alerts array"));
    }
    assertThat(AlertmanagerPayloads.parse(Recordings.bytes("[]")))
        .isEqualTo(new ParseResult.Failed("the body is not a JSON object"));
    assertThat(AlertmanagerPayloads.parse(new byte[] {(byte) 0xC3, (byte) 0x28}))
        .isEqualTo(new ParseResult.Failed("the body is not valid UTF-8"));

    ObjectNode emptySummary = alert(FIRST);
    ((ObjectNode) emptySummary.get("annotations")).put("summary", "");
    assertThat(only(message(text(emptySummary))).title()).isEqualTo("SyntheticUssdSessionFailed");
    ObjectNode noAnnotations = alert(FIRST);
    noAnnotations.remove("annotations");
    assertThat(only(message(text(noAnnotations))).title()).isEqualTo("SyntheticUssdSessionFailed");
    ObjectNode neither = alert(FIRST);
    neither.remove("annotations");
    ((ObjectNode) neither.get("labels")).remove("alertname");
    assertThat(only(message(text(neither))).title()).isNull();

    ObjectNode severe = alert(FIRST);
    ((ObjectNode) severe.get("labels")).put("severity", "critical");
    assertThat(only(message(text(severe))).sourceSeverity()).isEqualTo("critical");

    assertThat(startsAt("2026-10-15T19:01:06.865123456Z"))
        .isEqualTo(Instant.parse("2026-10-15T19:01:06.865Z"));
    assertThat(startsAt("2026-10-16T00:01:06+05:00"))
        .isEqualTo(Instant.parse("2026-10-15T19:01:06Z"));
    assertThat(startsAt("yesterday")).isNull();

    ObjectNode numberLabel = alert(FIRST);
    ((ObjectNode) numberLabel.get("labels")).put("count", 3);
    assertThat(only(message(text(numberLabel))).mappingFields()).doesNotContainKey("label.count");

    ObjectNode tricky = alert(FIRST);
    ((ObjectNode) tricky.get("annotations")).put("description", "a ] b } c \" d");
    String trickyText = text(tricky);
    assertThat(trickyText).contains("\\\"");
    assertThat(only(message(trickyText)).payload()).isEqualTo(trickyText);
  }

  private static Instant startsAt(String value) {
    ObjectNode alert = alert(FIRST);
    alert.put("startsAt", value);
    return only(message(text(alert))).sourceTime();
  }
}
