package vigil.incidents.api;

/** The tenant's {@code forming} incidents: their number and the sum of their alert counts. */
public record FormingSummary(long incidents, long alerts) {}
