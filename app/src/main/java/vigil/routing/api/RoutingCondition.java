package vigil.routing.api;

import java.util.List;

/**
 * A comparison of a routing rule (DM-V-03): one value, or for {@code IN} and {@code NOT_IN} one or
 * more; an integer value as decimal text. In a save any part may be {@code null} or empty (an
 * incomplete condition); in a view every condition is complete.
 */
public record RoutingCondition(RoutingField field, RoutingOperator operator, List<String> values) {}
