package vigil.aigateway.api;

/** A provider configuration as other modules read it (DM-E-70); {@code providerKind} its key. */
public record ProviderView(
    String providerKey,
    String providerKind,
    ProviderLocation location,
    String modelName,
    boolean active) {}
