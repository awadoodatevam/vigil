package vigil.correlation.api;

import java.time.Duration;

/** The correlation settings of a tenant (DM-E-31). */
public record CorrelationSettingsView(
    Duration groupingWindow,
    Duration settlePeriod,
    Duration maxForming,
    Duration stabilityCheckInterval) {}
