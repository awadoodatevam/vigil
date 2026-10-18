package vigil.connectors;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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

class ZabbixParseTest {

  private static final String FIRST = Recordings.body("0001-zabbix-lab.json");

  private static ParsedEntry.Normalised normalised(String body) {
    return (ParsedEntry.Normalised) single(body);
  }

  private static ParsedEntry single(String body) {
    ParseResult result = ZabbixPayloads.parse(Recordings.bytes(body));
    assertThat(result).isInstanceOf(ParseResult.Parsed.class);
    List<ParsedEntry> entries = ((ParseResult.Parsed) result).entries();
    assertThat(entries).hasSize(1);
    assertThat(entries.getFirst().entryIndex()).isZero();
    assertThat(entries.getFirst().payload()).isEqualTo(body);
    return entries.getFirst();
  }

  private static ObjectNode first() {
    return (ObjectNode) Recordings.tree(FIRST);
  }

  private static String text(JsonNode node) {
    return Recordings.JSON.writeValueAsString(node);
  }

  private static ObjectNode withTags(String tags) {
    ObjectNode body = first();
    body.set("tags", Recordings.tree(tags));
    return body;
  }

  @Test
  @DisplayName("AR-K-01 the recorded Zabbix messages are normalised as Section 12.3 says")
  void recordings() {
    DateTimeFormatter format = DateTimeFormatter.ofPattern("uuuu.MM.dd HH:mm:ss");
    List<String> names = Recordings.names("zabbix-lab");
    assertThat(names).hasSize(64);
    int firing = 0;
    int resolved = 0;
    for (String name : names) {
      String body = Recordings.body(name);
      JsonNode tree = Recordings.tree(body);
      ParsedEntry.Normalised entry = normalised(body);
      boolean problem = tree.get("event_value").stringValue().equals("1");
      String component = null;
      for (JsonNode tag : tree.get("tags")) {
        if (tag.get("tag").stringValue().equals("component")) {
          component = tag.get("value").stringValue();
          break;
        }
      }
      String vigil = null;
      for (JsonNode tag : tree.get("tags")) {
        if (tag.get("tag").stringValue().equals("vigil")) {
          vigil = tag.get("value").stringValue();
          break;
        }
      }
      String date = tree.get(problem ? "event_date" : "recovery_date").stringValue();
      String time = tree.get(problem ? "event_time" : "recovery_time").stringValue();
      assertThat(entry.alertKey()).as(name).isEqualTo(tree.get("event_id").stringValue());
      assertThat(entry.status()).isEqualTo(problem ? AlertStatus.FIRING : AlertStatus.RESOLVED);
      assertThat(entry.title()).isEqualTo(tree.get("event_name").stringValue());
      assertThat(entry.sourceSeverity()).isEqualTo("Not classified");
      assertThat(entry.identityPairs())
          .containsExactly(
              new IdentityPair("component", component),
              new IdentityPair("host", tree.get("host").stringValue()));
      assertThat(entry.mappingFields())
          .isEqualTo(
              Map.of(
                  "event_name", tree.get("event_name").stringValue(),
                  "host", tree.get("host").stringValue(),
                  "severity", tree.get("severity").stringValue(),
                  "tag.component", component,
                  "tag.vigil", vigil));
      assertThat(entry.sourceTime())
          .isEqualTo(LocalDateTime.parse(date + " " + time, format).toInstant(ZoneOffset.UTC));
      if (problem) {
        firing++;
      } else {
        resolved++;
      }
    }
    assertThat(firing).isEqualTo(32);
    assertThat(resolved).isEqualTo(32);

    ParsedEntry.Normalised one = normalised(FIRST);
    assertThat(one.alertKey()).isEqualTo("36");
    assertThat(one.status()).isEqualTo(AlertStatus.FIRING);
    assertThat(one.title()).isEqualTo("Database write errors on schema bundle_catalog");
    assertThat(one.sourceTime()).isEqualTo(Instant.parse("2026-10-15T19:00:43Z"));
    assertThat(one.identityPairs())
        .containsExactly(
            new IdentityPair("component", "db-subscriber"), new IdentityPair("host", "lab-db-01"));
    assertThat(one.mappingFields())
        .isEqualTo(
            Map.of(
                "event_name", "Database write errors on schema bundle_catalog",
                "host", "lab-db-01",
                "severity", "Not classified",
                "tag.component", "db-subscriber",
                "tag.vigil", "lab"));
    ParsedEntry.Normalised recovery = normalised(Recordings.body("0128-zabbix-lab.json"));
    assertThat(recovery.alertKey()).isEqualTo("50");
    assertThat(recovery.status()).isEqualTo(AlertStatus.RESOLVED);
    assertThat(recovery.title()).isEqualTo("SMS queue growing on route route-10");
    assertThat(recovery.sourceTime()).isEqualTo(Instant.parse("2026-10-15T19:03:16Z"));
    assertThat(recovery.identityPairs())
        .containsExactly(
            new IdentityPair("component", "sms-queue"), new IdentityPair("host", "lab-vas-01"));
  }

  @Test
  @DisplayName("PM-K-01 zabbix parse of unusual and wrong bodies")
  void unusualBodies() {
    ObjectNode value2 = first();
    value2.put("event_value", "2");
    String body2 = text(value2);
    assertThat(single(body2)).isEqualTo(new ParsedEntry.Failed(0, body2, "unknown event_value"));

    ObjectNode noId = first();
    noId.remove("event_id");
    ObjectNode emptyId = first();
    emptyId.put("event_id", "");
    ObjectNode numberId = first();
    numberId.put("event_id", 36);
    for (ObjectNode body : List.of(noId, emptyId, numberId)) {
      assertThat(single(text(body)))
          .isEqualTo(new ParsedEntry.Failed(0, text(body), "no event_id"));
    }

    for (String body : List.of("[]", "{", "\"text\"", "")) {
      assertThat(ZabbixPayloads.parse(Recordings.bytes(body)))
          .isEqualTo(new ParseResult.Failed("the body is not a JSON object"));
    }
    assertThat(ZabbixPayloads.parse(new byte[] {(byte) 0xC3, (byte) 0x28}))
        .isEqualTo(new ParseResult.Failed("the body is not valid UTF-8"));

    ObjectNode noTags = first();
    noTags.remove("tags");
    ParsedEntry.Normalised untagged = normalised(text(noTags));
    assertThat(untagged.identityPairs()).containsExactly(new IdentityPair("host", "lab-db-01"));
    assertThat(untagged.mappingFields().keySet()).noneMatch(key -> key.startsWith("tag."));

    ParsedEntry.Normalised twice =
        normalised(
            text(
                withTags(
                    "[{\"tag\":\"component\",\"value\":\"a\"},"
                        + "{\"tag\":\"component\",\"value\":\"b\"}]")));
    assertThat(twice.identityPairs().getFirst()).isEqualTo(new IdentityPair("component", "a"));
    assertThat(twice.mappingFields()).containsEntry("tag.component", "a");

    ParsedEntry.Normalised notTags =
        normalised(
            text(
                withTags(
                    "[\"x\", {\"tag\":\"component\"}, {\"tag\":\"component\",\"value\":\"x\"}]")));
    assertThat(notTags.identityPairs().getFirst()).isEqualTo(new IdentityPair("component", "x"));
    assertThat(notTags.mappingFields()).containsEntry("tag.component", "x");

    ParsedEntry.Normalised emptyComponent =
        normalised(
            text(
                withTags(
                    "[{\"tag\":\"component\",\"value\":\"\"},"
                        + "{\"tag\":\"component\",\"value\":\"x\"}]")));
    assertThat(emptyComponent.identityPairs())
        .containsExactly(new IdentityPair("host", "lab-db-01"));
    assertThat(emptyComponent.mappingFields()).containsEntry("tag.component", "");

    ParsedEntry.Normalised objectTags =
        normalised(text(withTags("{\"tag\":\"component\",\"value\":\"x\"}")));
    assertThat(objectTags.identityPairs()).containsExactly(new IdentityPair("host", "lab-db-01"));
    assertThat(objectTags.mappingFields().keySet()).noneMatch(key -> key.startsWith("tag."));

    ObjectNode emptyHost = first();
    emptyHost.put("host", "");
    ParsedEntry.Normalised hostless = normalised(text(emptyHost));
    assertThat(hostless.identityPairs())
        .containsExactly(new IdentityPair("component", "db-subscriber"));
    assertThat(hostless.mappingFields()).containsEntry("host", "");

    ObjectNode recovery = first();
    recovery.put("event_value", "0");
    recovery.put("recovery_date", "");
    recovery.put("recovery_time", "19:05:00");
    ParsedEntry.Normalised recovered = normalised(text(recovery));
    assertThat(recovered.status()).isEqualTo(AlertStatus.RESOLVED);
    assertThat(recovered.sourceTime()).isNull();

    ObjectNode dashed = first();
    dashed.put("event_date", "2026-10-15");
    assertThat(normalised(text(dashed)).sourceTime()).isNull();

    String spaced = "  \n" + FIRST + " \n ";
    assertThat(normalised(spaced).payload()).isEqualTo(spaced);

    ObjectNode unicode = first();
    unicode.put("event_name", "Störung ✓ €");
    String unicodeBody = text(unicode);
    ParsedEntry.Normalised named = normalised(unicodeBody);
    assertThat(named.title()).isEqualTo("Störung ✓ €");
    assertThat(named.payload()).isEqualTo(unicodeBody);
  }
}
