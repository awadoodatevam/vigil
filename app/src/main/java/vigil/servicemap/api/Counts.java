package vigil.servicemap.api;

/** The number of entries of each section of a service map file (DM-E-24 {@code counts}). */
public record Counts(int services, int components, int identityKeys, int dependencies) {}
