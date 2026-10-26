package vigil.diagnostics.api;

import java.util.Arrays;
import java.util.Optional;

/** The outcomes of a diagnostic result (DM-E-54, complete list). */
public enum DiagnosticOutcome {
  SUCCEEDED("succeeded"),
  FAILED("failed");

  private final String key;

  DiagnosticOutcome(String key) {
    this.key = key;
  }

  public String key() {
    return key;
  }

  /** The value with exactly this key; empty for {@code null} or an unknown key. */
  public static Optional<DiagnosticOutcome> fromKey(String key) {
    return Arrays.stream(values()).filter(value -> value.key.equals(key)).findFirst();
  }
}
