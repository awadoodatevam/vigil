package vigil.setup.api;

import java.util.Map;

/** One part of a seed import record; {@code error} is {@code null} unless it failed. */
public record PartView(
    String module, PartOutcome outcome, Map<String, Integer> counts, String error) {}
