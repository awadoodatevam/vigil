package vigil.actions.api;

import java.util.Arrays;
import java.util.Optional;

/** The states of an approval request (DM-E-58, DM-SM-11, complete list). */
public enum RequestState {
  PENDING("pending"),
  APPROVED("approved"),
  REJECTED("rejected"),
  EXPIRED("expired"),
  PREAUTHORIZED("preauthorized"),
  BLOCKED("blocked"),
  REFUSED("refused"),
  CANCELLED("cancelled");

  private final String key;

  RequestState(String key) {
    this.key = key;
  }

  public String key() {
    return key;
  }

  /** The value with exactly this key; empty for {@code null} or an unknown key. */
  public static Optional<RequestState> fromKey(String key) {
    return Arrays.stream(values()).filter(value -> value.key.equals(key)).findFirst();
  }
}
