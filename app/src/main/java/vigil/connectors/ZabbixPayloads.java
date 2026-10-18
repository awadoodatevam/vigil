package vigil.connectors;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import vigil.connectors.api.AlertStatus;
import vigil.connectors.api.IdentityPair;
import vigil.connectors.api.ParseResult;
import vigil.connectors.api.ParsedEntry;

/** Normalisation of a Zabbix webhook message ({@code 50-architecture} Section 12.3, AR-K-01). */
final class ZabbixPayloads {

  private static final DateTimeFormatter TIME =
      DateTimeFormatter.ofPattern("uuuu.MM.dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);

  private ZabbixPayloads() {}

  static ParseResult parse(byte[] body) {
    Optional<String> text = JsonBodies.decode(body);
    if (text.isEmpty()) {
      return new ParseResult.Failed(JsonBodies.NOT_UTF_8);
    }
    Optional<JsonNode> object = JsonBodies.object(text.get());
    if (object.isEmpty()) {
      return new ParseResult.Failed(JsonBodies.NOT_AN_OBJECT);
    }
    return new ParseResult.Parsed(List.of(entry(text.get(), object.get())));
  }

  private static ParsedEntry entry(String payload, JsonNode message) {
    String eventId = JsonBodies.present(message, "event_id");
    if (eventId == null) {
      return new ParsedEntry.Failed(0, payload, "no event_id");
    }
    String eventValue = JsonBodies.string(message, "event_value");
    AlertStatus status;
    if ("1".equals(eventValue)) {
      status = AlertStatus.FIRING;
    } else if ("0".equals(eventValue)) {
      status = AlertStatus.RESOLVED;
    } else {
      return new ParsedEntry.Failed(0, payload, "unknown event_value");
    }
    Instant sourceTime =
        status == AlertStatus.FIRING
            ? time(message, "event_date", "event_time")
            : time(message, "recovery_date", "recovery_time");
    List<IdentityPair> pairs = new ArrayList<>();
    Map<String, String> fields = new LinkedHashMap<>();
    for (String name : List.of("event_name", "host", "severity")) {
      String value = JsonBodies.string(message, name);
      if (value != null) {
        fields.put(name, value);
      }
    }
    Set<String> tagNames = new HashSet<>();
    JsonNode tags = message.get("tags");
    if (tags != null && tags.isArray()) {
      for (JsonNode tag : tags) {
        if (!tag.isObject()) {
          continue;
        }
        String name = JsonBodies.string(tag, "tag");
        String value = JsonBodies.string(tag, "value");
        if (name == null || value == null || !tagNames.add(name)) {
          continue;
        }
        fields.put("tag." + name, value);
        if (name.equals("component") && !value.isEmpty()) {
          pairs.add(new IdentityPair("component", value));
        }
      }
    }
    String host = JsonBodies.present(message, "host");
    if (host != null) {
      pairs.add(new IdentityPair("host", host));
    }
    return new ParsedEntry.Normalised(
        0,
        payload,
        eventId,
        status,
        JsonBodies.present(message, "event_name"),
        sourceTime,
        JsonBodies.present(message, "severity"),
        pairs,
        fields);
  }

  private static Instant time(JsonNode message, String dateField, String timeField) {
    String date = JsonBodies.present(message, dateField);
    String time = JsonBodies.present(message, timeField);
    if (date == null || time == null) {
      return null;
    }
    try {
      return LocalDateTime.parse(date + " " + time, TIME)
          .toInstant(ZoneOffset.UTC)
          .truncatedTo(ChronoUnit.MILLIS);
    } catch (DateTimeParseException e) {
      return null;
    }
  }
}
