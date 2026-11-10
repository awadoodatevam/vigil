package vigil.aigateway.api;

/** The outcome of one provider call (DM-E-74 {@code outcome}). */
public enum UsageOutcome {
  SUCCEEDED("succeeded"),
  UNREACHABLE("unreachable"),
  PROVIDER_ERROR("provider_error"),
  REFUSED_BY_DATA_ROUTING("refused_by_data_routing");

  private final String key;

  UsageOutcome(String key) {
    this.key = key;
  }

  public String key() {
    return key;
  }
}
