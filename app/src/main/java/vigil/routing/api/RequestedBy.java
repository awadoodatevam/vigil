package vigil.routing.api;

import java.util.Arrays;
import java.util.Optional;

/** The module request that asked for a routing evaluation (DM-E-43, complete list). */
public enum RequestedBy {
  CORRELATION("correlation"),
  WORKFLOWS("workflows"),
  TEST_REPLAY("test_replay");

  private final String key;

  RequestedBy(String key) {
    this.key = key;
  }

  public String key() {
    return key;
  }

  /** The value with exactly this key; empty for {@code null} or an unknown key. */
  public static Optional<RequestedBy> fromKey(String key) {
    return Arrays.stream(values()).filter(value -> value.key.equals(key)).findFirst();
  }
}
