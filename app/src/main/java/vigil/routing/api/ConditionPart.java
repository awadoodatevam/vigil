package vigil.routing.api;

import java.util.List;

/** A condition of a rule in seed part 11; a single YAML value becomes a list of one. */
public record ConditionPart(String field, String operator, List<String> values) {}
