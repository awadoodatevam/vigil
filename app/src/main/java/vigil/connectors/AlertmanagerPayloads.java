package vigil.connectors;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.JsonNode;
import vigil.connectors.api.AlertStatus;
import vigil.connectors.api.IdentityPair;
import vigil.connectors.api.ParseResult;
import vigil.connectors.api.ParsedEntry;

/**
 * Normalisation of an Alertmanager webhook message ({@code 50-architecture} Section 12.4, AR-K-02):
 * one entry per element of {@code alerts}, its payload the element's exact text in the body.
 */
final class AlertmanagerPayloads {

  private static final String ALERTS = "alerts";

  private AlertmanagerPayloads() {}

  static ParseResult parse(byte[] body) {
    Optional<String> text = JsonBodies.decode(body);
    if (text.isEmpty()) {
      return new ParseResult.Failed(JsonBodies.NOT_UTF_8);
    }
    Optional<JsonNode> object = JsonBodies.object(text.get());
    if (object.isEmpty()) {
      return new ParseResult.Failed(JsonBodies.NOT_AN_OBJECT);
    }
    JsonNode alerts = object.get().get(ALERTS);
    if (alerts == null || !alerts.isArray()) {
      return new ParseResult.Failed("the body has no alerts array");
    }
    List<String> payloads = alertTexts(text.get());
    if (payloads == null || payloads.size() != alerts.size()) {
      return new ParseResult.Failed(JsonBodies.NOT_AN_OBJECT);
    }
    List<ParsedEntry> entries = new ArrayList<>();
    for (int i = 0; i < alerts.size(); i++) {
      entries.add(entry(i, payloads.get(i), alerts.get(i)));
    }
    return new ParseResult.Parsed(entries);
  }

  /**
   * The exact text of each element of the last top-level {@code alerts} array, located with the
   * character offsets of a streaming parser over the decoded text (AR-P-01).
   */
  private static List<String> alertTexts(String text) {
    try (JsonParser parser = JsonBodies.JSON.createParser(text)) {
      List<String> elements = null;
      parser.nextToken();
      while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
        String name = parser.currentName();
        JsonToken value = parser.nextToken();
        if (name.equals(ALERTS) && value == JsonToken.START_ARRAY) {
          elements = new ArrayList<>();
          JsonToken token;
          while ((token = parser.nextToken()) != JsonToken.END_ARRAY) {
            int start = (int) parser.currentTokenLocation().getCharOffset();
            int end;
            if (token.isStructStart()) {
              parser.skipChildren();
              end = (int) parser.currentTokenLocation().getCharOffset() + 1;
            } else {
              parser.finishToken();
              end = (int) parser.currentLocation().getCharOffset();
            }
            elements.add(text.substring(start, end));
          }
        } else {
          parser.skipChildren();
          if (name.equals(ALERTS)) {
            elements = null;
          }
        }
      }
      return elements;
    } catch (JacksonException e) {
      return null;
    }
  }

  private static ParsedEntry entry(int index, String payload, JsonNode alert) {
    if (!alert.isObject()) {
      return new ParsedEntry.Failed(index, payload, "the entry is not a JSON object");
    }
    String fingerprint = JsonBodies.present(alert, "fingerprint");
    if (fingerprint == null) {
      return new ParsedEntry.Failed(index, payload, "no fingerprint");
    }
    String statusText = JsonBodies.string(alert, "status");
    AlertStatus status;
    if ("firing".equals(statusText)) {
      status = AlertStatus.FIRING;
    } else if ("resolved".equals(statusText)) {
      status = AlertStatus.RESOLVED;
    } else {
      return new ParsedEntry.Failed(index, payload, "unknown status");
    }
    JsonNode labels = objectOrNull(alert.get("labels"));
    JsonNode annotations = objectOrNull(alert.get("annotations"));
    String title = annotations == null ? null : JsonBodies.present(annotations, "summary");
    if (title == null && labels != null) {
      title = JsonBodies.present(labels, "alertname");
    }
    List<IdentityPair> pairs = new ArrayList<>();
    Map<String, String> fields = new LinkedHashMap<>();
    String severity = null;
    if (labels != null) {
      severity = JsonBodies.present(labels, "severity");
      for (String name : List.of("component", "instance")) {
        String value = JsonBodies.present(labels, name);
        if (value != null) {
          pairs.add(new IdentityPair(name, value));
        }
      }
      stringFields(labels, "label.", fields);
    }
    if (annotations != null) {
      stringFields(annotations, "annotation.", fields);
    }
    Instant sourceTime =
        time(JsonBodies.present(alert, status == AlertStatus.FIRING ? "startsAt" : "endsAt"));
    return new ParsedEntry.Normalised(
        index, payload, fingerprint, status, title, sourceTime, severity, pairs, fields);
  }

  private static JsonNode objectOrNull(JsonNode node) {
    return node != null && node.isObject() ? node : null;
  }

  private static void stringFields(JsonNode object, String prefix, Map<String, String> fields) {
    for (Map.Entry<String, JsonNode> property : object.properties()) {
      if (property.getValue().isString()) {
        fields.put(prefix + property.getKey(), property.getValue().stringValue());
      }
    }
  }

  private static Instant time(String value) {
    if (value == null) {
      return null;
    }
    try {
      return OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
          .toInstant()
          .truncatedTo(ChronoUnit.MILLIS);
    } catch (DateTimeParseException e) {
      return null;
    }
  }
}
