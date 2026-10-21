package vigil.servicemap.api;

/**
 * The component an alert's identity pairs match (DM-R-05): {@code componentKey} is {@code null} for
 * "unknown component"; {@code generation} is 0 when the tenant has no generation.
 */
public record Match(long generation, String componentKey) {}
