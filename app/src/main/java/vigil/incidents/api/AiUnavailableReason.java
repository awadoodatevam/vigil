package vigil.incidents.api;

import java.util.Arrays;
import java.util.Optional;

/**
 * Why the AI investigation is unavailable (DM-E-32 {@code ai_unavailable_reason}, complete list).
 */
public enum AiUnavailableReason {
  NO_PROVIDER_REACHABLE("no_provider_reachable"),
  NO_PROVIDER_ALLOWED("no_provider_allowed"),
  OUTPUT_FAILED_CHECKS("output_failed_checks");

  private final String key;

  AiUnavailableReason(String key) {
    this.key = key;
  }

  public String key() {
    return key;
  }

  /** The value with exactly this key; empty for {@code null} or an unknown key. */
  public static Optional<AiUnavailableReason> fromKey(String key) {
    return Arrays.stream(values()).filter(value -> value.key.equals(key)).findFirst();
  }
}
