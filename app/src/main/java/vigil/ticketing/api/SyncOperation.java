package vigil.ticketing.api;

import java.util.Arrays;
import java.util.Optional;

/** The operations of a ticket sync record (DM-E-76; complete list). */
public enum SyncOperation {
  CREATE("create"),
  REOPEN("reopen"),
  RESOLVE("resolve"),
  RECORD_ESCALATION("record_escalation");

  private final String key;

  SyncOperation(String key) {
    this.key = key;
  }

  public String key() {
    return key;
  }

  /** The value with exactly this key; empty for {@code null} or an unknown key. */
  public static Optional<SyncOperation> fromKey(String key) {
    return Arrays.stream(values()).filter(value -> value.key.equals(key)).findFirst();
  }
}
