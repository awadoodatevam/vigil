package vigil.correlation.api;

/**
 * Seed part 10: the mapping {@code correlation_settings} of {@code 07-settings.yaml}, each value's
 * text as the seed import reads it ({@code null} when absent).
 */
public record CorrelationSettingsPart(
    String groupingWindowS,
    String settlePeriodS,
    String maxFormingS,
    String stabilityCheckIntervalS) {}
