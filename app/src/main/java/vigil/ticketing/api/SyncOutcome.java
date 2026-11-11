package vigil.ticketing.api;

import java.util.Arrays;
import java.util.Optional;

/** The outcomes of a ticket sync record (DM-E-76; complete list). */
public enum SyncOutcome {
  PENDING("pending"),
  DONE("done"),
  FAILED("failed");

  private final String key;

  SyncOutcome(String key) {
    this.key = key;
  }

  public String key() {
    return key;
  }

  /** The value with exactly this key; empty for {@code null} or an unknown key. */
  public static Optional<SyncOutcome> fromKey(String key) {
    return Arrays.stream(values()).filter(value -> value.key.equals(key)).findFirst();
  }
}
