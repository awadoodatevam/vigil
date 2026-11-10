package vigil.aigateway.api;

/** Where a provider runs (DM-E-70 {@code location}). */
public enum ProviderLocation {
  EXTERNAL("external"),
  IN_HOUSE("in_house");

  private final String key;

  ProviderLocation(String key) {
    this.key = key;
  }

  public String key() {
    return key;
  }
}
