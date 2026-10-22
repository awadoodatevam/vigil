package vigil.intake.api;

import java.util.Arrays;
import java.util.Optional;

/** The outcome of a connection test (DM-E-15 {@code last_test_outcome}). */
public enum TestOutcome {
  PASSED("passed"),
  FAILED("failed");

  private final String key;

  TestOutcome(String key) {
    this.key = key;
  }

  public String key() {
    return key;
  }

  /** The outcome with exactly this key; empty for {@code null} or an unknown key. */
  public static Optional<TestOutcome> fromKey(String key) {
    return Arrays.stream(values()).filter(outcome -> outcome.key.equals(key)).findFirst();
  }
}
