package vigil.incidents;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import vigil.incidents.api.AffectedServiceCopy;
import vigil.incidents.api.AlertCopy;
import vigil.incidents.api.FinalReason;
import vigil.incidents.api.GroupingReason;
import vigil.incidents.api.HypothesisCopy;
import vigil.incidents.api.IdentityPairCopy;

/**
 * The stored JSON of vigil-incidents (P-14 spec Section 6.3): keys in snake case, empty members as
 * JSON {@code null}, timestamps as {@code YYYY-MM-DDTHH:MM:SS.sssZ}, durations as whole seconds.
 */
final class IncidentJson {

  static final JsonMapper JSON = JsonMapper.builder().build();
  private static final DateTimeFormatter TIME =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  private IncidentJson() {}

  /** A timestamp as AR-I-01 writes it. */
  static String time(Instant instant) {
    return TIME.format(instant);
  }

  static String write(JsonNode node) {
    return JSON.writeValueAsString(node);
  }

  static JsonNode read(String json) {
    return JSON.readTree(json);
  }

  /** True when {@code json} is a JSON object. */
  static boolean isObject(String json) {
    try {
      JsonNode node = JSON.readTree(json);
      return node != null && node.isObject();
    } catch (JacksonException e) {
      return false;
    }
  }

  /** The keys as a JSON array in code point order. */
  static String keys(Collection<String> keys) {
    ArrayNode array = JSON.createArrayNode();
    keys.stream().sorted(IncidentTexts.CODE_POINTS).forEach(array::add);
    return write(array);
  }

  static List<String> strings(String json) {
    List<String> values = new ArrayList<>();
    read(json).forEach(value -> values.add(value.stringValue()));
    return List.copyOf(values);
  }

  /** DM-E-34 {@code alert_snapshot}. */
  static String alertSnapshot(AlertCopy alert) {
    ObjectNode node = JSON.createObjectNode();
    node.put("source_kind", alert.sourceKind());
    node.put("alert_key", alert.alertKey());
    node.put("alert_type", alert.alertType());
    node.put("alert_type_name", alert.alertTypeName());
    node.put("title", alert.title());
    node.put("component_key", alert.componentKey());
    if (alert.firstIdentityPair() == null) {
      node.putNull("first_identity_pair");
    } else {
      ObjectNode pair = node.putObject("first_identity_pair");
      pair.put("name", alert.firstIdentityPair().name());
      pair.put("value", alert.firstIdentityPair().value());
    }
    node.put("fired_at", time(alert.firedAt()));
    return write(node);
  }

  static AlertCopy alertCopy(UUID alertId, String snapshot) {
    JsonNode node = read(snapshot);
    JsonNode pair = node.get("first_identity_pair");
    return new AlertCopy(
        alertId,
        node.get("source_kind").stringValue(),
        node.get("alert_key").stringValue(),
        node.get("alert_type").stringValue(),
        node.get("alert_type_name").stringValue(),
        node.get("title").stringValue(),
        text(node, "component_key"),
        pair == null || pair.isNull()
            ? null
            : new IdentityPairCopy(pair.get("name").stringValue(), pair.get("value").stringValue()),
        Instant.parse(node.get("fired_at").stringValue()));
  }

  /** DM-E-34 {@code reason} of a correlation decision. */
  static String reason(GroupingReason reason) {
    ObjectNode node = JSON.createObjectNode();
    node.put("decision", reason.decision());
    relation(
        node,
        reason.relatedAlertId(),
        reason.relation(),
        reason.relationPath(),
        reason.timeDeltaSeconds());
    return write(node);
  }

  /** DM-E-34 {@code reason} of a membership made by a merge. */
  static String movedByMerge(UUID fromIncidentId, String originalReason) {
    ObjectNode node = JSON.createObjectNode();
    node.put("decision", "moved_by_merge");
    node.put("from_incident_id", fromIncidentId.toString());
    node.set("original_reason", read(originalReason));
    return write(node);
  }

  /** DM-E-34 {@code final_reason}. */
  static String finalReason(FinalReason reason) {
    ObjectNode node = JSON.createObjectNode();
    if (FinalReason.ONLY_ALERT.equals(reason.relation())) {
      node.put("relation", FinalReason.ONLY_ALERT);
      return write(node);
    }
    relation(
        node,
        reason.relatedAlertId(),
        reason.relation(),
        reason.relationPath(),
        reason.timeDeltaSeconds());
    return write(node);
  }

  /** The {@code final_reason} of a decision's relation fields (DM-E-34, DM-R-21). */
  static String finalReason(GroupingReason reason) {
    return finalReason(
        new FinalReason(
            reason.relatedAlertId(),
            reason.relation(),
            reason.relationPath(),
            reason.timeDeltaSeconds()));
  }

  private static void relation(
      ObjectNode node, UUID relatedAlertId, String relation, List<String> path, Long timeDelta) {
    node.put("related_alert_id", relatedAlertId == null ? null : relatedAlertId.toString());
    node.put("relation", relation);
    if (path == null) {
      node.putNull("relation_path");
    } else {
      ArrayNode array = node.putArray("relation_path");
      path.forEach(array::add);
    }
    node.put("time_delta", timeDelta);
  }

  /** DM-E-32 {@code hypothesis_snapshot}; {@code null} without a hypothesis. */
  static String hypothesisSnapshot(HypothesisCopy hypothesis) {
    if (hypothesis == null) {
      return null;
    }
    ObjectNode node = JSON.createObjectNode();
    node.put("generation", hypothesis.generation());
    node.put("name", hypothesis.name());
    node.put("kind", hypothesis.kind());
    node.put("criticality", hypothesis.criticality());
    return write(node);
  }

  static HypothesisCopy hypothesis(String componentKey, String snapshot) {
    if (componentKey == null || snapshot == null) {
      return null;
    }
    JsonNode node = read(snapshot);
    return new HypothesisCopy(
        node.get("generation").longValue(),
        componentKey,
        node.get("name").stringValue(),
        node.get("kind").stringValue(),
        text(node, "criticality"));
  }

  /** DM-E-32 {@code affected_services}, in the given order. */
  static String affectedServices(List<AffectedServiceCopy> services) {
    ArrayNode array = JSON.createArrayNode();
    for (AffectedServiceCopy service : services) {
      ObjectNode node = array.addObject();
      node.put("service_key", service.serviceKey());
      node.put("name", service.name());
      node.put("criticality", service.criticality());
      ArrayNode via = node.putArray("via_component_keys");
      service.viaComponentKeys().forEach(via::add);
    }
    return write(array);
  }

  static List<AffectedServiceCopy> affectedServices(String json) {
    List<AffectedServiceCopy> services = new ArrayList<>();
    for (JsonNode node : read(json)) {
      List<String> via = new ArrayList<>();
      node.get("via_component_keys").forEach(key -> via.add(key.stringValue()));
      services.add(
          new AffectedServiceCopy(
              node.get("service_key").stringValue(),
              node.get("name").stringValue(),
              text(node, "criticality"),
              via));
    }
    return List.copyOf(services);
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? null : value.stringValue();
  }
}
