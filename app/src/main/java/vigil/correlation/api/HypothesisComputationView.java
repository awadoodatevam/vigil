package vigil.correlation.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A hypothesis computation (DM-E-30); {@code affectedComponentKeys} sorted, {@code dependentCounts}
 * in key order, {@code resultComponentKey} and {@code previousComponentKey} {@code null} when
 * empty.
 */
public record HypothesisComputationView(
    UUID id,
    UUID incidentId,
    long generation,
    List<String> affectedComponentKeys,
    Map<String, Long> dependentCounts,
    String resultComponentKey,
    TieBreak tieBreak,
    String previousComponentKey,
    Instant computedAt) {}
