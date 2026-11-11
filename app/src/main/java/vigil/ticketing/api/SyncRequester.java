package vigil.ticketing.api;

import java.util.Arrays;
import java.util.Optional;

/** Who requested a ticket sync record (DM-E-76; complete list). */
public enum SyncRequester {
  WORKFLOW_STEP("workflow_step"),
  ROUTING("routing");

  private final String key;

  SyncRequester(String key) {
    this.key = key;
  }

  public String key() {
    return key;
  }

  /** The value with exactly this key; empty for {@code null} or an unknown key. */
  public static Optional<SyncRequester> fromKey(String key) {
    return Arrays.stream(values()).filter(value -> value.key.equals(key)).findFirst();
  }
}
