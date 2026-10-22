package vigil.routing;

import java.util.List;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import vigil.incidents.api.AffectedServiceCopy;
import vigil.incidents.api.IncidentView;
import vigil.routing.api.RoutingField;

/**
 * The values of the nine routing fields of an incident (DM-R-25); the enum and key fields are
 * {@code null} without a value, the sets and integers always have one.
 */
record RoutingInputs(
    String hypothesisComponent,
    String hypothesisComponentKind,
    String hypothesisCriticality,
    List<String> affectedServices,
    String highestServiceCriticality,
    long alertCount,
    List<String> alertTypes,
    List<String> alertSources) {

  private static final List<String> CRITICALITIES =
      RoutingField.HIGHEST_SERVICE_CRITICALITY.allowedValues();

  static RoutingInputs of(IncidentView incident) {
    String highest = null;
    for (AffectedServiceCopy service : incident.affectedServices()) {
      String criticality = service.criticality();
      if (criticality != null
          && (highest == null
              || CRITICALITIES.indexOf(criticality) < CRITICALITIES.indexOf(highest))) {
        highest = criticality;
      }
    }
    boolean hypothesis = incident.hypothesis() != null;
    return new RoutingInputs(
        incident.hypothesisComponentKey(),
        hypothesis ? incident.hypothesis().kind() : null,
        hypothesis ? incident.hypothesis().criticality() : null,
        incident.affectedServices().stream().map(AffectedServiceCopy::serviceKey).toList(),
        highest,
        incident.alertCount(),
        List.copyOf(incident.alertTypes()),
        List.copyOf(incident.alertSources()));
  }

  /**
   * The field's value: a {@code String}, a {@code List<String>}, a {@code Long} or {@code null}.
   */
  Object value(RoutingField field) {
    return switch (field) {
      case HYPOTHESIS_COMPONENT -> hypothesisComponent;
      case HYPOTHESIS_COMPONENT_KIND -> hypothesisComponentKind;
      case HYPOTHESIS_CRITICALITY -> hypothesisCriticality;
      case AFFECTED_SERVICES -> affectedServices;
      case AFFECTED_SERVICE_COUNT -> (long) affectedServices.size();
      case HIGHEST_SERVICE_CRITICALITY -> highestServiceCriticality;
      case ALERT_COUNT -> alertCount;
      case ALERT_TYPES -> alertTypes;
      case ALERT_SOURCES -> alertSources;
    };
  }

  /** DM-E-43 {@code inputs}: the nine fields in the order of DM-R-25. */
  String json() {
    ObjectNode node = RoutingJson.JSON.createObjectNode();
    for (RoutingField field : RoutingField.values()) {
      Object value = value(field);
      switch (value) {
        case null -> node.putNull(field.key());
        case String text -> node.put(field.key(), text);
        case Long number -> node.put(field.key(), number);
        case List<?> list -> {
          ArrayNode array = node.putArray(field.key());
          list.forEach(item -> array.add((String) item));
        }
        default -> throw new IllegalStateException("unexpected value of " + field.key());
      }
    }
    return RoutingJson.write(node);
  }
}
